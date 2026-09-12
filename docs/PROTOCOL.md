# Music Assistant protocol notes

Facts verified against a live Music Assistant 2.10.1 server (schema 65, min supported
schema 28) and the official Python client. Useful background if you're touching
`app/src/main/java/io/github/superthom196/matv/ma/`.

- WebSocket `ws://host:8095/ws`. Server sends `ServerInfoMessage` first. First command
  must be `auth` with `{token}` or `{username, password, device_name}`; result
  `{authenticated, user}`.
- `POST /auth/login {credentials:{username,password}}` returns `{success, token, user}`
  (used for first login); `auth/token/create {name}` returns a 10-year token, which the
  app stores.
- Requests: `{message_id, command, args}`; replies `{message_id, result}` or
  `{message_id, error_code, details}`; events `{event, object_id, data}`.
- Full command list: `GET /api-docs/commands.json` (309 commands); object shapes
  `GET /api-docs/schemas.json`.
- Discovery: `GET /info` (unauthenticated) and mDNS `_mass._tcp`.
- Artwork: `{base}/imageproxy/{proxy_id}?size=N` or legacy
  `{base}/imageproxy?path=<double-encoded>&provider=..&size=N`, sizes
  80/160/256/512/1024.
- `music/albums/library_items`'s `order_by` is a free-text field on the `Album` DB row
  (`sort_name`, `timestamp_added`, ...) — there's no artist column to sort by server-side
  (`artists` is a many-to-many relation), so sorting albums by artist has to happen
  client-side after fetching the whole library. `music/albums/count` returns the total
  album count.

## Scope

Connect (discovery + manual) → Login (once) → Pick player → Library (artists / albums /
folders, drill-down) → Now Playing (artwork, transport, progress, volume) → media keys
live on every screen.

Not yet implemented: multi-server, LMS/Subsonic support, HA OAuth login.
