# Security Policy / 安全策略

## Supported versions

mine-platform is currently pre-1.0. Security fixes are applied to the latest `main` revision and the latest published release when one exists. Older snapshots and unmaintained deployment forks are not supported unless a separate maintenance agreement says otherwise.

| Version | Supported |
|---|---|
| Latest `main` / latest release | Yes |
| Older snapshots | No |

## Report a vulnerability privately

Do **not** open a public Issue, Discussion or Pull Request for a vulnerability, exposed credential or customer data leak.

Use [GitHub Private Vulnerability Reporting](https://github.com/Boscodoggggg/mine-platform/security/advisories/new). Include:

- affected revision, component and deployment mode;
- prerequisites and a minimal reproducible proof;
- realistic impact and affected trust boundary;
- sanitized logs or packets with every secret and customer identifier removed;
- any mitigation already tested.

Maintainers aim to acknowledge a complete report within three business days and provide an initial classification within seven business days. Complex field or dependency issues may take longer; progress will be shared in the private advisory.

## Scope

Examples that are in scope:

- authentication, authorization or mine-scope isolation bypass;
- device identity spoofing, protocol parser memory/cpu abuse or command injection;
- secret disclosure, unsafe sensitive-value echo or insecure default credentials;
- archive traversal, backup overwrite or destructive recovery behavior;
- stored/reflected XSS, SQL injection, SSRF or arbitrary object-storage access;
- supply-chain or offline-bundle integrity failures.

Operational misconfiguration without a platform defect, unsupported end-of-life dependencies and findings that only affect deliberately exposed development credentials may be closed as hardening guidance. Do not test against a mine, customer system or infrastructure you do not own or have written authorization to assess.

## Coordinated disclosure

Please allow maintainers time to reproduce, fix and prepare an advisory before public disclosure. We will credit reporters who want attribution and acted in good faith. Never retain, alter or publish customer data obtained during research.

## 中文说明

漏洞、泄露凭据或客户数据不得提交公开 Issue、Discussion 或 PR，请使用上方 GitHub 私密漏洞报告入口。报告中应说明受影响版本、组件、复现步骤、真实影响和已验证缓解措施，并移除所有真实凭据、客户标识、人员信息与敏感坐标。未经书面授权，不得测试任何真实矿区或第三方系统。
