# HexCLI HTTP endpoint (for browsers)

Optional, **off by default**. A thin HTTP/JSON adapter over the same `HexCliOps` layer that the
console commands and the TCP remote client use — casting logic is not duplicated here.

- Protocol spec (authoritative): [HexSimulator `docs/PROTOCOL.md`](https://github.com/xm1221/HexSimulator/blob/main/docs/PROTOCOL.md)
- Chinese: [hexcli-http.zh-CN.md](./hexcli-http.zh-CN.md)
- HexCLI overview: [../HEXCLI.md](../HEXCLI.md)

## Why

The terminal client talks to hexcli over loopback TCP with newline-delimited JSON. Browsers cannot
open raw TCP sockets, so the mod also exposes the same commands as `POST /v1/rpc` over HTTP when
explicitly enabled. The TCP endpoint is unchanged; both can run at the same time and share one token.

## Enable

Requires HexCLI itself (`-Dmielib.hexcli=true` or `enableHexCli = true` in `config/mielib/server.toml`)
and hexParse installed.

```bash
java -Dmielib.hexcli=true -Dmielib.hexcli.http=true -Dmielib.hexcli.http.port=8742 -jar server.jar nogui
```

| Property | Meaning | Default |
|----------|---------|---------|
| `-Dmielib.hexcli.http=true` | Enable the HTTP endpoint (still needs HexCLI enabled) | `false` |
| `-Dmielib.hexcli.http.port=<n>` | Port; `0` = pick a free one | `0` (auto) |
| `-Dmielib.hexcli.http.bind=<addr>` | Bind address | `127.0.0.1` |
| `-Dmielib.hexcli.http.cors=<list>` | CORS allowlist; `*`, or comma-separated origins, `http://host:*` wildcards the port | `http://127.0.0.1:*,http://localhost:*` |

The same four settings exist in the MieLib server config as `enableHexCliHttp`, `hexCliHttpPort`,
`hexCliHttpBind`, `hexCliHttpCors` (server-side only; never synced to clients, like `enableHexCli`).
System properties always win.

`bind` defaults to the literal `127.0.0.1` and is resolved with `InetAddress.getByName`, **not**
`InetAddress.getLoopbackAddress()` — on Forge the latter often resolves to `::1`, while clients and
docs use `127.0.0.1`, and IPv4 clients then fail to connect. Set `-Dmielib.hexcli.http.bind=0.0.0.0`
to expose it on a LAN (only if you understand the risk: the endpoint can cast spells).

## Endpoints

### `GET /v1/health` (no token)

```json
{"ok": true, "protocol": 1, "server": "mielib-hexcli", "version": "0.0.2.1"}
```

`version` is the MieLib mod version (`dev` in a dev run).

### `POST /v1/rpc`

```http
POST /v1/rpc HTTP/1.1
Content-Type: application/json
Authorization: Bearer <token>

{"id": 1, "cmd": "run", "code": "num_1 num_2 add"}
```

Response:

```json
{"id": 1, "ok": true, "lines": ["..."], "data": { "cast": { "stack": [ /* iotas, bottom → top */ ] } }}
```

`cmd` values (identical to the TCP remote types): `ping`, `run` (`code`), `reset`, `stack`,
`step.begin` (`code`, optional `label`), `step.next`, `step.abort`, `complete`,
`profile.list`, `profile.show`, `profile.use` (`name`), `env.list`, `env.use` (`env`, `"auto"` ok).
Unknown `cmd` → `ok: false` with `data.error`.

Every game operation runs on the server thread via `server.submit(...).get(...)` (120 s timeout);
HTTP worker threads only parse JSON.

## `hexcli/http.json`

Written on server start and deleted on stop, next to the TCP client's `client.json`:

```json
{"host": "127.0.0.1", "port": 8742, "token": "…", "protocol": 1, "version": 1}
```

The token is **the same** as `client.json` (the HTTP endpoint reuses the TCP remote's token), so a
terminal client and a browser page can share it. Treat it as a secret; do not commit it.

Startup log line:

```
[HexCLI] HTTP endpoint http://127.0.0.1:8742/v1 (see hexcli/http.json)
```

## Auth

`Authorization: Bearer <token>`, or a `"token"` field in the JSON body. Compared in constant time.
Health is unauthenticated. Wrong / missing token → HTTP 401 with `{"ok": false, "data": {"error": "bad token"}}`.

## CORS

- Allowlist from `-Dmielib.hexcli.http.cors` / `hexCliHttpCors`.
- An `Origin` on the allowlist is echoed in `Access-Control-Allow-Origin`; anything else gets HTTP 403.
- `Allow-Headers: Authorization, Content-Type`, `Allow-Methods: GET,POST,OPTIONS`; `OPTIONS` preflight → 204.
- `*` is meant for local debugging only.

The default allowlist accepts any port on `127.0.0.1` / `localhost`, which covers a dev server on
`http://localhost:5173` etc.

## Mixed content

An `https://` page cannot call `http://127.0.0.1` in most browsers. Run the page over plain `http`
locally, put TLS in front of HexCLI, or use a same-origin reverse proxy. This endpoint has no TLS.

## curl

```bash
TOKEN=$(python3 -c 'import json;print(json.load(open("run/hexcli/http.json"))["token"])')

curl -s http://127.0.0.1:8742/v1/health
curl -s -X POST http://127.0.0.1:8742/v1/rpc \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":1,"cmd":"run","code":"num_1 num_2 add"}'
```

## Iota JSON

Unchanged from the TCP remote, plus (for browser rendering):

| Field | When |
|-------|------|
| `value` | `DoubleIota` → number, `BooleanIota` → boolean, `NullIota` → `null` |
| `pattern` | `PatternIota` → `{"startDir": "NORTH_EAST", "angles": "qaq"}` for mini glyphs |
| `entity` | `EntityIota` — `{type, name, pos, uuid}` |
| `children` | `ListIota`, recursive |

## See also

- [HexSimulator PROTOCOL.md](https://github.com/xm1221/HexSimulator/blob/main/docs/PROTOCOL.md) — wire format, step object, frontend rules
- [../HEXCLI.md](../HEXCLI.md) — terminal client, TCP protocol, profiles
