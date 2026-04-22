# tools

An MCP server that exposes three top-level tools — `safe`, `unsafe`, and `search` — and dispatches `safe`/`unsafe` calls to concrete handlers resolved from var metadata. `search` runs hybrid (dense + lexical) queries over a Qdrant index of tool metadata.

## Run

```sh
./start.sh
```

`start.sh` sources `.env` (if present — gitignored), brings up Docker's Qdrant container (ports 7533 REST / 7534 gRPC), waits for readiness, then launches the JVM. Pedestal listens on `127.0.0.1:4301/mcp` (HTTP JSON-RPC); nREPL on `4302`. All ports in `resources/config.edn`.

### Prerequisites

- Docker Desktop running.
- `OPENAI_API_KEY` available — either in the interactive shell that runs `start.sh`, or in a project-local `.env` file:
  ```sh
  echo 'OPENAI_API_KEY=sk-...' > .env
  ```
  `.env` is gitignored. The file form is needed for launchd, which doesn't inherit interactive shell env.

### Register with an MCP client

```json
"tools": { "type": "http", "url": "http://127.0.0.1:4301/mcp" }
```

## Run as a launchd agent

A plist is checked in at `launchd/com.sudorock.tools.plist`. It invokes `start.sh`, keeps the process alive, and writes stdout/stderr to `~/Library/Logs/com.sudorock.tools/`.

```sh
mkdir -p ~/Library/Logs/com.sudorock.tools
ln -sf "$PWD/launchd/com.sudorock.tools.plist" ~/Library/LaunchAgents/com.sudorock.tools.plist
launchctl load -w ~/Library/LaunchAgents/com.sudorock.tools.plist
```

Tail logs / check status / unload:

```sh
tail -F ~/Library/Logs/com.sudorock.tools/stderr.log
launchctl list | rg com.sudorock.tools
launchctl unload ~/Library/LaunchAgents/com.sudorock.tools.plist
```

Restart — three options, in order of preference:

```sh
# 1. Modern. SIGTERMs; KeepAlive respawns. `-k` kills first if running.
launchctl kickstart -k gui/$(id -u)/com.sudorock.tools

# 2. Stop; KeepAlive handles the respawn. Simpler, slightly less deterministic.
launchctl stop com.sudorock.tools

# 3. Only after editing the plist itself (paths, env, RunAtLoad, etc.).
#    kickstart/stop won't re-read the plist; unload + load will.
launchctl unload ~/Library/LaunchAgents/com.sudorock.tools.plist
launchctl load   ~/Library/LaunchAgents/com.sudorock.tools.plist
```

Verify after a restart — the PID in `launchctl list` should change and the port should still be bound:

```sh
launchctl list com.sudorock.tools
lsof -nP -iTCP:4301 -sTCP:LISTEN
```

Hardcoded paths assume the repo is at `/Users/indy/dev/tools` and Homebrew lives at `/opt/homebrew`. Edit the plist if either differs.

## How it works

An MCP client sees three tools:

- `safe`   — `readOnlyHint: true`,  `destructiveHint: false`
- `unsafe` — `readOnlyHint: false`, `destructiveHint: true`
- `search` — `readOnlyHint: true`,  `destructiveHint: false`

`safe` and `unsafe` take `{ "action": "<namespaced/name>", "params": { ... } }`. The dispatcher (`src/tools/mcp/tools.clj`) scans loaded namespaces for vars tagged with matching metadata, enforces the safety tag, malli-validates `params`, and invokes the handler. Handler's raw return wrapped as `{result}`; any throw becomes `{error}`.

`search` takes `{ "query": "<text>", "limit": <1-50, default 10> }` and returns hits of the form `{action, description, safety, input_schema, score}` — ranked by RRF over a dense OpenAI embedding (`text-embedding-3-large`) plus a client-side lexical score. Each hit's payload is the full metadata snapshot taken at the last sync, so the caller can decide whether to route to `safe` or `unsafe` and how to fill the schema without a second round-trip.

## Sync — how Qdrant stays aligned with code

`tools.sync/sync!` enumerates every var tagged with `:tool/name`, diffs it against Qdrant via `clojure.data/diff` on id-indexed maps, and either upserts (with a fresh embedding of `"<action-name>\n\n<description>"`), updates the payload in-place (when only non-content fields changed), or deletes (for tools removed from code). It runs **exactly once per JVM lifecycle — inside the `:pedestal/server` init-key, just before Jetty starts**. Any sync error aborts startup; launchd's `KeepAlive` restart-loops until the cause is fixed.

Consequences:
- Cold boot (and launchd kickstart) → sync runs.
- `unsafe tools/restart {}` → halt + refresh-all + system/init fires the pedestal init-key again → sync re-runs.
- `unsafe tools/refresh {}` → code reloads, sync does **not** run. `search` will return stale results until the next restart. Prefer `restart` after editing tool metadata.

## Defining an action

A handler is just a function whose var carries four keys:

```clojure
(ns tools.actions.echo)

(defn ^{:tool/name         :echo/echo
        :tool/safety       :tool.safety/safe
        :tool/input-schema :map
        :tool/description  "Echo params back"}
  echo
  [_ctx params]
  params)
```

- `:tool/name` — namespaced keyword; clients address it as the string form (`"echo/echo"`).
- `:tool/safety` — `:tool.safety/safe` or `:tool.safety/unsafe`. Strictly enforced: calling `safe` on an unsafe action (or vice versa) returns a `safety mismatch` error.
- `:tool/input-schema` — malli schema. Params are decoded with the JSON transformer and humanised errors are returned on validation failure.
- `:tool/description` — free text.

Then require the ns from `src/tools/actions.clj` so it loads at startup:

```clojure
(ns tools.actions
  (:require
   [tools.actions.echo]
   [tools.actions.system]
   [tools.actions.my-new-thing]))  ;; <- add
```

`tools.actions` is loaded by `tools.main` after `tools.system`, which avoids the cycle an action would otherwise create by depending on `tools.system`.

## Built-in actions

| action | safety | params | effect |
|---|---|---|---|
| `echo/echo`        | safe   | any object                   | returns params unchanged |
| `token/count-text` | safe   | `{text, encoding?}`          | count tokens in a string via tiktoken |
| `token/count-file` | safe   | `{path, encoding?}`          | count tokens in a file's UTF-8 contents via tiktoken |
| `tools/refresh`    | unsafe | `{}`                         | `clojure.tools.namespace.repl/refresh-all` over `src/` |
| `tools/restart`    | unsafe | `{}`                         | halts the system, refreshes, re-inits; runs async so the HTTP response flushes first |

Token actions require a Python 3 interpreter with `tiktoken` installed and a `libpython3.X.dylib` (or `.so`) findable by `libpython-clj`. Configure the executable in `resources/config.edn` under `:python/runtime {:python-executable ...}` (default `python3`). Default encoding is `cl100k_base` when `encoding` is omitted.

## Wire example

```sh
curl -s -XPOST http://127.0.0.1:4301/mcp -H 'content-type: application/json' -d '
{"jsonrpc":"2.0","id":1,"method":"tools/call",
 "params":{"name":"safe",
           "arguments":{"action":"echo/echo","params":{"hello":"world"}}}}'
```

Response:

```json
{"jsonrpc":"2.0","id":1,"result":{
  "content":[{"type":"text","text":"{\"hello\":\"world\"}"}],
  "structuredContent":{"data":{"hello":"world"}},
  "isError":false}}
```

## Layout

```
src/tools/
  main.clj                 -main; requires system + actions barrel
  system.clj               glass.system init/halt; marked no-unload
  pedestal.clj             jetty connector start/stop
  service.clj              /mcp route + ctx interceptor
  utils.clj                find-vars-by-meta
  sync.clj                 diff var metadata ↔ qdrant, upsert/set/delete
  qdrant/migration.clj     ensure `tools` collection + payload indexes
  actions.clj              barrel — requires every action ns
  actions/echo.clj         sample safe action
  actions/token.clj        token/count-{text,file} (safe, via tiktoken)
  actions/system.clj       tools/refresh + tools/restart (unsafe)
  mcp/http.clj             pedestal interceptor, JSON-RPC framing
  mcp/server.clj           initialize / ping / tools/list / tools/call
  mcp/tools.clj            list-tools + safe/unsafe/search dispatcher
  mcp/search.clj           hybrid search (dense + lexical, RRF-merged)
resources/config.edn       python, openai, qdrant, nrepl, pedestal
deps.edn                   clojure, glass, malli, pedestal, nrepl, tools.namespace
docker-compose.yml         qdrant service
start.sh                   .env + docker + wait + clojure -M:dev -m tools.main
```

## Notes

- `refresh` reloads code dispatched through vars (the MCP pipeline, handlers). It does *not* swap values baked into Jetty's interceptor chain (routes, `mcp-handler` object). For those, use `restart`.
- Refresh is scoped to `src/` via `clojure.tools.namespace.repl/set-refresh-dirs` — otherwise it tries to reload every `.clj` under every classpath dep.
- `tools.system` carries `^{:clojure.tools.namespace.repl/load false :unload false}` so `-sys-vol` survives refreshes.
- nREPL (4302) is bounced by `restart` too — any connected REPL sessions drop.
