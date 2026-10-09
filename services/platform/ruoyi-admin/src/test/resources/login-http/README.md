# Real login regression

`LoginHttpPostgresTest` calls an independently started platform application over HTTP
and verifies the login listener's writes in PostgreSQL. Use a dedicated nonproduction
application, PostgreSQL, Redis and two synthetic users in different tenants. Both
users need a password-grant client and the same test password. Each positive case
clears only its own fixture's `login_date` and `login_ip`, then requires a successful
integer response code, a token and a new database write. Never point this test at
production or personal accounts.

Inject the following environment variables into the Maven process; do not put
credentials in command arguments, source control or reports:

| Variable | Meaning |
| --- | --- |
| `RAGENT_LOGIN_HTTP_TEST` | Set to `true` to enable the instance tests |
| `RAGENT_LOGIN_BASE` | URL of the running real application, without a trailing slash |
| `RAGENT_LOGIN_CLIENT_ID` | Seeded password-grant client ID |
| `RAGENT_LOGIN_TENANT_A`, `RAGENT_LOGIN_TENANT_B` | Two different synthetic tenant IDs |
| `RAGENT_LOGIN_USER_A`, `RAGENT_LOGIN_USER_B` | Dedicated fixture user names |
| `RAGENT_LOGIN_PASSWORD` | Shared fixture password |
| `RAGENT_LOGIN_JDBC_URL` | Test PostgreSQL JDBC URL with the platform schema in `currentSchema` |
| `RAGENT_LOGIN_DB_USER`, `RAGENT_LOGIN_DB_PASSWORD` | Test database credentials |

From `services/platform/ruoyi-admin`, run:

```shell
mvn -B -ntp -Pdev -Dtest=LoginHttpPostgresTest test
```

Require three executed test cases, zero failures/errors and zero skips in the
JUnit XML. With the enable flag absent the class is deliberately skipped;
a normal CI build without external fixtures does not prove HTTP login works.
With the flag enabled, missing settings, unreachable services or missing fixture
rows fail the tests. HTTP status alone is never used as the success criterion.
