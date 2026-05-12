# Checklists

## Requirement Intake

- [ ] What user workflow changes?
- [ ] Which module changes: server, web, both, scripts, docs?
- [ ] Are API contracts affected?
- [ ] Is database shape or stored data affected?
- [ ] Is third-party network behavior involved?
- [ ] Is trading time or market calendar logic involved?

## Implementation

- [ ] Read existing code before editing.
- [ ] Keep changes scoped.
- [ ] Keep DTO/model names clear and serializable.
- [ ] Preserve existing API compatibility where possible.
- [ ] Preserve local DB/log/build artifacts.
- [ ] Avoid unrelated style churn.

## QA

- [ ] Backend build if server changed.
- [ ] Frontend webpack if web changed.
- [ ] Full `check` if shared/build logic changed.
- [ ] Curl smoke check if REST behavior changed.
- [ ] Browser/manual smoke check if UI behavior changed.
- [ ] Final answer lists commands and outcomes.
