# tools

An MCP server that exposes three top-level tools — `safe`, `unsafe`, and `search` — and dispatches `safe`/`unsafe` calls to concrete handlers resolved from var metadata. `search` runs hybrid (dense + lexical) queries over a Qdrant index of tool metadata.

## Run

```sh
./start.sh
```

`start.sh` sources `.env` (if present — gitignored), brings up Docker's Qdrant container (ports 7533 REST / 7534 gRPC), waits for readiness, then launches the JVM. Pedestal listens on `127.0.0.1:4301/mcp` (HTTP JSON-RPC); nREPL on `4302`. All ports in `resources/config.edn`.

### Prerequisites

- Docker Desktop running.
- `OPENAI_API_KEY` available in the shell that runs `start.sh`, or in a project-local `.env` file:
  ```sh
  echo 'OPENAI_API_KEY=sk-...' > .env
  ```
  `.env` is gitignored.

### Register with an MCP client

```json
"tools": { "type": "http", "url": "http://127.0.0.1:4301/mcp" }
```

## How it works

An MCP client sees three tools:

- `safe`   — `readOnlyHint: true`,  `destructiveHint: false`
- `unsafe` — `readOnlyHint: false`, `destructiveHint: true`
- `search` — `readOnlyHint: true`,  `destructiveHint: false`

`safe` and `unsafe` take `{ "action": "<namespaced/name>", "params": { ... } }`. The dispatcher (`src/tools/mcp/tools.clj`) scans loaded namespaces for vars tagged with matching metadata, enforces the safety tag, malli-validates `params`, and invokes the handler. Handler's raw return wrapped as `{result}`; any throw becomes `{error}`.

`search` takes `{ "query": "<text>", "limit": <1-50, default 10> }` and returns hits of the form `{action, description, safety, input_schema, score}` — ranked by RRF over a dense OpenAI embedding (`text-embedding-3-large`) plus a client-side lexical score. Each hit's payload is the full metadata snapshot taken at the last sync, so the caller can decide whether to route to `safe` or `unsafe` and how to fill the schema without a second round-trip.

## Sync — how Qdrant stays aligned with code

`tools.qdrant.sync/sync!` enumerates every var tagged with `:tool/name`, diffs it against Qdrant via `clojure.data/diff` on id-indexed maps, and either upserts (with a fresh embedding of `"<action-name>\n\n<description>"`), updates the payload in-place (when only non-content fields changed), or deletes (for tools removed from code). It runs **exactly once per JVM lifecycle — inside the `:pedestal/server` init-key, just before Jetty starts**. Any sync error aborts startup.

Restart the JVM to re-sync after editing tool metadata.

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
   [tools.actions.token]
   [tools.actions.my-new-thing]))  ;; <- add
```

`tools.actions` is loaded by `tools.main` after `tools.system`, which avoids the cycle an action would otherwise create by depending on `tools.system`.

## Built-in actions

| action | safety | params | effect |
|---|---|---|---|
| `echo/echo`        | safe   | any object                   | returns params unchanged |
| `token/count-text` | safe   | `{text, encoding?}`          | count tokens in a string via tiktoken |
| `token/count-file` | safe   | `{path, encoding?}`          | count tokens in a file's UTF-8 contents via tiktoken |

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
  system.clj               glass.system init/halt
  pedestal.clj             jetty connector start/stop
  service.clj              /mcp route + ctx interceptor
  utils.clj                find-vars-by-meta
  qdrant/migration.clj     ensure `tools` collection
  qdrant/sync.clj          diff var metadata ↔ qdrant, upsert/set/delete
  actions.clj              barrel — requires every action ns
  actions/echo.clj         sample safe action
  actions/token.clj        token/count-{text,file} (safe, via tiktoken)
  mcp/http.clj             pedestal interceptor, JSON-RPC framing
  mcp/server.clj           initialize / ping / tools/list / tools/call
  mcp/tools.clj            list-tools + safe/unsafe/search dispatcher
  mcp/search.clj           hybrid search (dense + lexical, RRF-merged)
resources/config.edn       python, openai, qdrant, nrepl, pedestal
deps.edn                   clojure, glass, malli, pedestal, nrepl
docker-compose.yml         qdrant service
start.sh                   .env + docker + wait + clojure -M:dev -m tools.main
```
