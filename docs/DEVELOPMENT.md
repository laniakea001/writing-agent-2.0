# Development

## Build

Build the frontend before packaging the Java application:

```bash
cd frontend
npm ci
npm run build:server
npm test
cd ..
mvn package
```

The generated `src/main/resources/static/workbench` directory is intentionally ignored by Git. The root route redirects to the single workbench entry. No legacy HTML or JavaScript page is shipped.

## Database integration tests

Ordinary `mvn test` does not require a database or call a paid model. PostgreSQL tests are opt-in and run only against a separate database named `writing_agent_test`. Docker Compose creates this database on the first initialization of its data volume.

PowerShell:

```powershell
$env:WA2_D2_IT = 'true'
$env:WRITING_TEST_DB_PASSWORD = '<你的测试库密码>'
# Optional: WRITING_TEST_DB_USER (default writing_app) and WRITING_TEST_DB_URL.
# The URL must name writing_agent_test, never the application database.
mvn test
```

Bash:

```bash
WA2_D2_IT=true WRITING_TEST_DB_PASSWORD='<你的测试库密码>' mvn test
```

Tests create isolated fixtures; the two historical truncate-based tests are not included. Flyway clean remains disabled. Do not reuse a production database or your article database for tests.

## Configuration

Docker Compose reads `.env`; Java reads process environment variables. For a custom database address set `WRITING_V2_DB_URL` explicitly. Changing Compose's database name or host port does not automatically change Java configuration.

The local batch dispatcher runs only explicitly activated batches, one item at a time. `WRITING_BATCH_DISPATCH_ENABLED=false` can disable background batch processing; such a configuration cannot complete a batch rewrite demonstration. `WRITING_BATCH_DISPATCH_PROJECT_ID` can restrict dispatch to one project.

Keep all `WA2_INJECT_*` fault-injection environment variables unset during normal operation. Generated agent state, conversation traces and article data are private runtime data, not repository fixtures.
