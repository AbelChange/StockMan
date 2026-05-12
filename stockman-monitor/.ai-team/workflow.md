# AI Team Workflow

This workflow turns a plain user requirement into a completed change.

## 1. Intake

Orchestrator:

- Restate the requirement in one sentence.
- Identify affected area: `server`, `web`, both, scripts/docs, or runtime.
- Read the smallest relevant files before planning.
- Check for existing behavior and preserve it unless the requirement says
  otherwise.

PM:

- Produce acceptance criteria:
  - User-visible behavior.
  - Data/API behavior.
  - Error and empty-state behavior.
  - Compatibility constraints.
- Resolve obvious ambiguity by following current product patterns.
- Ask the user only when the decision changes scope, data loss risk, or external
  side effects.

## 2. Plan

Orchestrator creates a short task plan:

1. Files/modules to inspect or change.
2. Implementation steps.
3. Verification commands.
4. Delivery risks or assumptions.

Keep the plan small. This project favors direct changes over broad rewrites.

## 3. Implementation

Dev:

- Make focused edits.
- Keep server and web models synchronized.
- Add or adjust helper functions where they reduce real duplication.
- Keep network-source logic inside strategy/network classes.
- Avoid changing runtime data, logs, generated build output, or unrelated files.

When changing APIs:

- Update backend route/DTO.
- Update frontend model and call site.
- Add a curl smoke check to QA's validation list.

When changing database shape:

- Add idempotent SQLite migration/initialization SQL.
- Preserve existing rows.
- Add indexes only when query behavior needs them.

When changing UI:

- Verify table density, text fit, loading/empty/error states, and disabled states.
- Keep controls close to the workflow they affect.

## 4. QA

QA maps changed files to validation:

- Backend-only: `./gradlew :server:build`.
- Frontend-only: `./gradlew :web:jsBrowserDevelopmentWebpack`.
- Shared/API/model changes: both backend and frontend commands.
- Broad or uncertain changes: `./gradlew check`.
- Runtime/API behavior: start server and run targeted `curl` checks.
- Full local UX change: `./scripts/restart-local.sh`, then inspect
  `http://localhost:8081`.

QA records:

- Commands run.
- Pass/fail outcome.
- Any skipped checks and why.
- Manual smoke-test observations.

## 5. Delivery

Orchestrator final answer:

```text
完成：
- What changed, by behavior and file area.

验证：
- `command` 通过
- `command` 未运行：reason

交付说明：
- Any migration, runtime, data-source, or user-action note.
```

Do not claim a command passed unless it was actually run in this workspace.
