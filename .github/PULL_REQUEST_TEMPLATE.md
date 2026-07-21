## What / 变更内容

<!-- Describe the user or operational problem and the bounded solution. -->

## Why / 背景与价值

<!-- Explain why this belongs in mine-platform and who benefits. -->

## Evidence / 验证证据

<!-- Include red/green tests, commands and relevant output. Never include secrets or customer data. -->

## Compatibility and operations / 兼容与运维

<!-- API/message/database compatibility, migration, rollback, metrics, alerts and deployment impact. -->

## Checklist

- [ ] The change is focused and contains no unrelated formatting or generated output.
- [ ] New behavior or a bug fix was covered by a failing test/contract before implementation.
- [ ] Relevant focused tests and `bash scripts/verify.sh` pass.
- [ ] API, message, database, deployment and runbook documentation are updated where required.
- [ ] New database tables and columns have Chinese comments verified by integration tests.
- [ ] User-visible frontend text exists in both Chinese and English locale files.
- [ ] No credentials, customer data, personal data or sensitive mine coordinates are included.
- [ ] Automated evidence is not presented as real-site production acceptance.
