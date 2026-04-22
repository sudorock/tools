# tools

An MCP server that exposes exactly two generic tools — `safe` and `unsafe` — and dispatches each call to a concrete handler resolved from var metadata.

## Run

```sh
./start.sh
```

Starts Pedestal on `127.0.0.1:4301/mcp` (HTTP JSON-RPC) and nREPL on `4302`. Both ports are configured in `resources/config.edn`.

Register with an MCP client that supports streamable HTTP:

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

An MCP client sees two tools:

- `safe`   — `readOnlyHint: true`,  `destructiveHint: false`
- `unsafe` — `readOnlyHint: false`, `destructiveHint: true`

Both take the same args:

```json
{ "action": "<namespaced/name>", "params": { ... } }
```

The dispatcher (`src/tools/mcp/tools.clj`) scans loaded namespaces for vars tagged with matching metadata, enforces the safety tag, malli-validates `params`, and invokes the handler. The handler's raw return is wrapped as `{result}`; any throw becomes `{error}`.

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
  actions.clj              barrel — requires every action ns
  actions/echo.clj         sample safe action
  actions/system.clj       refresh + restart (unsafe)
  mcp/http.clj             pedestal interceptor, JSON-RPC framing
  mcp/server.clj           initialize / ping / tools/list / tools/call
  mcp/tools.clj            list-tools + generic safe/unsafe dispatcher
resources/config.edn       nrepl + pedestal config
deps.edn                   clojure, glass, malli, pedestal, nrepl, tools.namespace
start.sh                   clojure -M:dev -m tools.main
```

## Notes

- `refresh` reloads code dispatched through vars (the MCP pipeline, handlers). It does *not* swap values baked into Jetty's interceptor chain (routes, `mcp-handler` object). For those, use `restart`.
- Refresh is scoped to `src/` via `clojure.tools.namespace.repl/set-refresh-dirs` — otherwise it tries to reload every `.clj` under every classpath dep.
- `tools.system` carries `^{:clojure.tools.namespace.repl/load false :unload false}` so `-sys-vol` survives refreshes.
- nREPL (4302) is bounced by `restart` too — any connected REPL sessions drop.
