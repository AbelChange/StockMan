# AGENTS.md

This file is the operating guide for AI agents working in `stockman-monitor`.
The goal is that the user can provide only a product requirement, and the AI team
will decompose, implement, verify, and deliver the change with minimal follow-up.

## Project Scope

`stockman-monitor` is a local Web monitor extracted from the Android StockMan
unusual-movement tracking logic.

- `server`: Ktor + SQLite backend. It syncs realtime stock data, persists data,
  detects unusual movements, exposes REST endpoints, and pushes WebSocket updates.
- `web`: Kotlin Compose for Web frontend. It renders monitor pages, market tables,
  DB viewer workflows, KPL live replay, alerts, and browser notifications.
- Runtime database: `server/data/stockman-monitor.db`.
- Local ports: backend `8080`, frontend dev server `8081`.

## AI Team Workflow

For every requirement, follow `.ai-team/workflow.md` unless the user explicitly
asks for a narrower action.

### Orchestrator

- Owns the end-to-end flow and final answer.
- Reads the requirement, checks current code, chooses the smallest safe scope,
  assigns PM/Dev/QA responsibilities, and keeps the work moving.
- Decides which validation commands are necessary based on changed files.
- Blocks delivery if build/test verification is missing and no reason is given.

### PM

- Converts the user's request into acceptance criteria.
- Identifies the affected user journey: monitor page, KPL live page, DB viewer,
  sync workflow, history sync, backend API, scheduler, or deployment/runtime.
- Calls out ambiguous product decisions only when a conservative assumption would
  be risky. Otherwise, chooses the behavior most consistent with the current app.

### Dev

- Implements the smallest coherent change using current project patterns.
- Keeps backend, frontend, and model changes in sync.
- Preserves existing REST/WebSocket contracts unless the requirement explicitly
  changes them.
- Avoids unrelated refactors, generated churn, and changes to runtime data/logs.

### QA

- Designs verification around the acceptance criteria and changed modules.
- Runs the relevant Gradle commands, plus runtime smoke checks when server/API
  behavior changes.
- Reports exact commands and outcomes in the final delivery.

## Engineering Rules

### Kotlin and Structure

- Use Kotlin 2.0.21 conventions already present in the project.
- Prefer straightforward functions and data classes over broad abstractions.
- Keep backend code under `server/src/main/kotlin/com/liaobusi/stockman/monitor`.
- Keep frontend code under `web/src/jsMain/kotlin/com/liaobusi/stockman/monitor/web`.
- Keep REST DTOs and WebSocket DTOs serializable and explicit.
- Do not introduce a new framework, package manager, or formatting tool unless
  the user specifically asks.

### Backend Rules

- Ktor routes live in `Server.kt`; keep route handling thin and push domain work
  into `MarketEngine`, sync classes, database helpers, or network strategies.
- SQLite access lives in `StockDatabase.kt`; use prepared statements for dynamic
  values and validate any dynamic table/column input.
- Preserve `FULL_MARKET_STOCK_MIN_COUNT = 5000` behavior for full-market sync
  unless the requirement is explicitly about changing sync completeness rules.
- Realtime stock sync entrypoint is `RealtimeStockSync`; source-specific behavior
  belongs in `sync/strategy/RealtimeStockStrategies.kt`.
- History K-line sync behavior belongs in `HistoryStockSync` and
  `sync/strategy/HistoryStockStrategies.kt`.
- Use the current OkHttp + Retrofit pattern for market-source requests. Do not
  hand-build long URLs in business logic when a strategy config can express them.
- Be careful with external data sources. They may be slow, incomplete, throttled,
  or unavailable; handle failures without destroying existing DB state.
- Do not commit or rely on `server/data/`, `run-logs/`, or local runtime output.

### Frontend Rules

- Compose for Web UI starts in `App.kt`; keep state local unless it clearly needs
  a reusable model/helper.
- Keep API models in `web/Models.kt` aligned with backend response shapes.
- WebSocket URL currently targets backend port `8080` on the same host.
- Keep monitor refresh intervals bounded by the existing min/max rules unless the
  requirement changes the product behavior.
- Preserve browser notification gating: notifications should be shown only when
  monitoring is enabled and trading-time logic allows it.
- Operational UI should stay dense, scannable, and work-focused. Avoid marketing
  sections, decorative cards, or purely visual changes that reduce table clarity.

### Market Domain Rules

- Trading-time logic is China A-share oriented. Check `ChinaMarketCalendar` and
  existing frontend `isTradingTime` behavior before changing schedules.
- Stock codes are strings. Do not coerce codes to numbers in a way that drops
  leading zeros.
- Sina is the default high-frequency realtime source. EastMoney is lower
  frequency and should be treated more cautiously.
- A sync returning too few stocks should fail gracefully and retain old data.
- Historical records are keyed by `(code, date)` and should be upserted rather
  than duplicated.

### Git and Files

- Work from the `stockman-monitor` directory for all commands in this file.
- Do not edit parent Android app files unless the user asks for parent-project
  changes.
- Do not modify keystores, local DB files, logs, `.gradle`, `.kotlin`, build
  directories, or IDE files.
- If the worktree has unrelated changes, leave them untouched.

## Build and Verification Commands

Run commands from:

```bash
/Users/haoshuaihui/AndroidProject/MyLearn/StockMan/stockman-monitor
```

### Baseline

```bash
./gradlew check
```

### Backend Changes

```bash
./gradlew :server:build
```

When API behavior changes, also run a local smoke test:

```bash
./gradlew :server:installDist
server/build/install/server/bin/server
curl http://localhost:8080/
curl http://localhost:8080/api/snapshot
curl http://localhost:8080/api/sync/status
```

### Frontend Changes

```bash
./gradlew :web:jsBrowserDevelopmentWebpack
```

For release-sensitive frontend changes:

```bash
./gradlew :web:jsBrowserProductionWebpack
```

### Full Local Run

```bash
./scripts/restart-local.sh
```

Then inspect:

```text
http://localhost:8080
http://localhost:8080/db
http://localhost:8081
```

### Useful API Checks

```bash
curl http://localhost:8080/api/snapshot
curl http://localhost:8080/api/sync/status
curl http://localhost:8080/api/sync/history/status
curl http://localhost:8080/api/db/tables
curl "http://localhost:8080/api/db/table/stock?limit=20"
curl -X POST http://localhost:8080/api/tick \
  -H 'Content-Type: application/json' \
  -d '{"code":"300059","price":30.0,"chg":8.0}'
```

Use live market-source sync calls only when the requirement needs them, because
they depend on network availability and third-party behavior:

```bash
curl -X POST "http://localhost:8080/api/sync/stocks?source=sina"
curl -X POST "http://localhost:8080/api/sync/stocks?source=eastmoney"
curl -X POST "http://localhost:8080/api/sync/history/start"
```

## Delivery Format

Final responses must use this shape:

```text
完成：
- ...

验证：
- `command` 通过
- `command` 未运行：原因

交付说明：
- 关键行为变化、兼容性、需要用户手动操作的事项
```

If there are no code changes, say that explicitly and summarize the analysis or
decision instead. If verification could not be run, state the blocker and the
most relevant command the user should run next.
