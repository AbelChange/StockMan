# Project-Specific Engineering Rules

## Source Layout

- Backend entry: `server/src/main/kotlin/com/liaobusi/stockman/monitor/Server.kt`.
- Backend engine: `MarketEngine.kt`.
- SQLite store: `StockDatabase.kt`.
- Realtime sync: `RealtimeStockSync.kt` and
  `sync/strategy/RealtimeStockStrategies.kt`.
- History sync: `HistoryStockSync.kt` and
  `sync/strategy/HistoryStockStrategies.kt`.
- Market calendar: `ChinaMarketCalendar.kt`.
- Frontend app: `web/src/jsMain/kotlin/com/liaobusi/stockman/monitor/web/App.kt`.
- Frontend API models: `web/src/jsMain/kotlin/com/liaobusi/stockman/monitor/web/Models.kt`.

## Runtime Contracts

- Backend listens on `0.0.0.0:8080`.
- Frontend dev server listens on `8081`.
- WebSocket endpoint is `/ws`.
- DB viewer is `/db`.
- Main APIs include:
  - `GET /api/snapshot`
  - `GET /api/sync/status`
  - `GET /api/sync/history/status`
  - `POST /api/sync/stocks?source=sina|eastmoney`
  - `POST /api/sync/history/start`
  - `POST /api/sync/history/stop`
  - `GET /api/db/tables`
  - `GET /api/db/table/{name}`
  - `POST /api/tick`

## Data Safety

- Runtime DB files under `server/data/` are local state, not source code.
- Logs under `run-logs/` are runtime output, not source code.
- Schema initialization must be idempotent.
- Table/column names from HTTP input must be allowlisted or validated before
  dynamic SQL.
- Stock code values must remain strings.

## Sync Behavior

- Realtime full-market sync must reject incomplete payloads instead of replacing
  good data with partial data.
- Sina is safe for normal scheduled refresh.
- EastMoney should remain manual or low frequency unless explicitly requested.
- Failed source requests should return useful status/errors and preserve old data.

## Frontend Behavior

- Monitor page must remain usable during disconnects and failed source refreshes.
- Alert list should stay bounded.
- Browser notifications require permission and monitoring enabled.
- Trading-time checks should stop monitoring outside trading time.
- Tables should remain dense and sortable/readable where currently designed.

## Validation Selection

- `server/**`: `./gradlew :server:build`.
- `web/**`: `./gradlew :web:jsBrowserDevelopmentWebpack`.
- `*.gradle.kts`, shared contracts, or broad changes: `./gradlew check`.
- `scripts/**`: run or shell-check by direct execution when safe.
- REST changes: start backend and run targeted curl checks.
