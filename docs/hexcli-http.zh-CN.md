# HexCLI HTTP 端点（浏览器用）

可选，**默认关闭**。它在控制台命令 / TCP 远程客户端共用的 `HexCliOps` 上包一层薄薄的 HTTP/JSON，
施法逻辑不在这里重写。

- 协议权威文档：[HexSimulator `docs/PROTOCOL.md`](https://github.com/xm1221/HexSimulator/blob/main/docs/PROTOCOL.md)（中文镜像 `PROTOCOL.zh-CN.md`）
- English: [hexcli-http.md](./hexcli-http.md)
- HexCLI 总览：[../HEXCLI.md](../HEXCLI.md)

## 为什么

终端客户端用回环 TCP + 换行分隔 JSON 和 HexCLI 通信；浏览器开不了裸 TCP，所以在显式开启时，
mod 另外把同一批命令暴露成 `POST /v1/rpc`。TCP 端点不变，两者可同时运行，并共用同一个 token。

## 启用

需要 HexCLI 本身已启用（`-Dmielib.hexcli=true`，或 `config/mielib/server.toml` 里 `enableHexCli = true`），
且装了 hexParse。

```bash
java -Dmielib.hexcli=true -Dmielib.hexcli.http=true -Dmielib.hexcli.http.port=8742 -jar server.jar nogui
```

| 属性 | 含义 | 默认 |
|------|------|------|
| `-Dmielib.hexcli.http=true` | 启用 HTTP 端点（仍需 HexCLI 已启用） | `false` |
| `-Dmielib.hexcli.http.port=<n>` | 端口；`0` = 自动挑空闲端口 | `0`（自动） |
| `-Dmielib.hexcli.http.bind=<addr>` | 绑定地址 | `127.0.0.1` |
| `-Dmielib.hexcli.http.cors=<list>` | CORS 白名单；`*`，或逗号分隔 origin，`http://host:*` 通配端口 | `http://127.0.0.1:*,http://localhost:*` |

同样四项也在 MieLib 服务端配置里：`enableHexCliHttp`、`hexCliHttpPort`、`hexCliHttpBind`、`hexCliHttpCors`
（仅服务端，和 `enableHexCli` 一样不参与网络同步）。**系统属性优先**。

`bind` 默认是字面量 `127.0.0.1`，并用 `InetAddress.getByName` 解析，**不是**
`InetAddress.getLoopbackAddress()`——后者在 Forge 上常解析成 `::1`，而前端和文档都写 `127.0.0.1`，
IPv4 客户端会连不上。要开到局域网用 `-Dmielib.hexcli.http.bind=0.0.0.0`（先想清楚风险：这个端点能施法）。

## 端点

### `GET /v1/health`（免 token）

```json
{"ok": true, "protocol": 1, "server": "mielib-hexcli", "version": "0.0.2.1"}
```

`version` 是 MieLib 的 mod 版本（开发运行时是 `dev`）。

### `POST /v1/rpc`

```http
POST /v1/rpc HTTP/1.1
Content-Type: application/json
Authorization: Bearer <token>

{"id": 1, "cmd": "run", "code": "num_1 num_2 add"}
```

响应：

```json
{"id": 1, "ok": true, "lines": ["..."], "data": { "cast": { "stack": [ /* iota，底 → 顶 */ ] } }}
```

`cmd` 取值（与 TCP 远程的类型一一对应）：`ping`、`run`（`code`）、`reset`、`stack`、
`step.begin`（`code`，可选 `label`）、`step.next`、`step.abort`、`complete`、
`profile.list`、`profile.show`、`profile.use`（`name`）、`env.list`、`env.use`（`env`，`"auto"` 可）。
未知 `cmd` → `ok: false`，`data.error` 说明原因。

所有游戏操作都通过 `server.submit(...).get(...)` 在服务器线程执行（最长等 120 秒）；
HTTP 工作线程只做 JSON 解析。

## `hexcli/http.json`

服务器启动时写入、停止时删除，和 TCP 客户端的 `client.json` 并排：

```json
{"host": "127.0.0.1", "port": 8742, "token": "…", "protocol": 1, "version": 1}
```

token 与 `client.json` 里的**是同一个**（HTTP 端点复用 TCP 远程的 token），终端客户端和浏览器页面可以共用。
把它当密钥，不要提交进仓库。

启动日志一行：

```
[HexCLI] HTTP endpoint http://127.0.0.1:8742/v1 (see hexcli/http.json)
```

## 鉴权

`Authorization: Bearer <token>`，或 JSON body 里的 `"token"` 字段；常量时间比较。
health 不需要鉴权。token 错误 / 缺失 → HTTP 401，`{"ok": false, "data": {"error": "bad token"}}`。

## CORS

- 白名单来自 `-Dmielib.hexcli.http.cors` / `hexCliHttpCors`。
- 命中白名单的 `Origin` 会回显到 `Access-Control-Allow-Origin`，其它一律 HTTP 403。
- `Allow-Headers: Authorization, Content-Type`、`Allow-Methods: GET,POST,OPTIONS`；`OPTIONS` 预检 → 204。
- `*` 仅供本地调试。

默认白名单接受 `127.0.0.1` / `localhost` 的任意端口，覆盖 `http://localhost:5173` 之类的开发服务器。

## 混合内容

`https://` 页面在多数浏览器里无法请求 `http://127.0.0.1`。本地请页面也用纯 `http`，或在 HexCLI 前面加 TLS /
同源反代。这个端点本身不带 TLS。

## curl

```bash
TOKEN=$(python3 -c 'import json;print(json.load(open("run/hexcli/http.json"))["token"])')

curl -s http://127.0.0.1:8742/v1/health
curl -s -X POST http://127.0.0.1:8742/v1/rpc \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":1,"cmd":"run","code":"num_1 num_2 add"}'
```

## Iota JSON

与 TCP 远程一致，另外为浏览器渲染加了：

| 字段 | 何时出现 |
|------|----------|
| `value` | `DoubleIota` → number，`BooleanIota` → boolean，`NullIota` → `null` |
| `pattern` | `PatternIota` → `{"startDir": "NORTH_EAST", "angles": "qaq"}`，画迷你图案用 |
| `entity` | `EntityIota` — `{type, name, pos, uuid}` |
| `children` | `ListIota`，递归 |

## 相关

- [HexSimulator PROTOCOL.md](https://github.com/xm1221/HexSimulator/blob/main/docs/PROTOCOL.md) — 线格式、step 对象、前端约定
- [../HEXCLI.md](../HEXCLI.md) — 终端客户端、TCP 协议、profile


## `patterns.list`

`POST /v1/rpc`，`cmd` 为 `patterns.list`（或 `patterns`）。从**当前进程的动作注册表**导出全部图案（含已加载附属），供 HexSimulator 等前端在首次连接时拉取，而不是打包静态 `patterns.json`。

响应 `data` 大致为：

```json
{
  "version": 1,
  "patterns": [
    {
      "id": "hexcasting:add",
      "startdir": "NORTH_EAST",
      "signature": "waaw",
      "aliases": ["add", "hexcasting:add"],
      "perWorld": false,
      "nameKey": "hexcasting.action.hexcasting:add",
      "names": { "en_us": "…" }
    }
  ],
  "numbers": {},
  "count": 0,
  "perWorldCount": 0
}
```

`stack` 的 `data.ravenmind` 与 `cast.ravenmind` 一样，是 iota JSON 对象（或 `null`），不再是纯字符串。

## Private Network Access / 混合内容

预检若带 `Access-Control-Request-Private-Network: true`，会回 `Access-Control-Allow-Private-Network: true`，便于 **https** 的 pages.dev 访问本机 `http://127.0.0.1`。若浏览器仍因混合内容拦截，请改用本地 `yarn dev`（http），或给 HexCLI 加 TLS / 反向代理。
