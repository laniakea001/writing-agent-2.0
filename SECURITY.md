# Security

This repository is a local writing workbench. The default server and database ports bind to loopback. Workspace IDs provide business scoping, not a complete account authentication or tenant authorization system. Do not expose the service directly to the public Internet without implementing authentication, authorization, TLS and deployment-specific safeguards.

- Store model keys and database passwords in environment variables; never commit them.
- Keep `.agentscope`, `runtime`, database dumps and conversation exports private.
- Treat model output and external text as untrusted. Business writes require server validation and, where applicable, an explicit confirmation decision.
- A network timeout is not permission to repeat a write. Preserve the original action key and query the existing result.
- Do not post credentials, private article contents or raw model traces in public issues.
- If a credential was published previously, remove it from the public artifact and rotate it at its issuer; deleting a current file alone does not remove repository history.

For a suspected vulnerability, report the affected endpoint and a minimal redacted reproduction. Do not publish a working exploit against another person's deployment.
