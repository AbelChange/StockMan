# Delivery Template

Use this exact structure in final responses after implementation work.

```text
完成：
- ...

验证：
- `./gradlew ...` 通过
- `curl ...` 通过

交付说明：
- ...
```

Rules:

- Mention changed behavior, not every tiny edit.
- Include exact verification commands.
- Say `未运行` with a reason for any expected validation that was skipped.
- Note local runtime URLs only when a server was started:
  - `http://localhost:8080`
  - `http://localhost:8080/db`
  - `http://localhost:8081`
- Note any dependency on third-party market data or trading hours.
