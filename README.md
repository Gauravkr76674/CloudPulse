# CloudOps AI Agent — how to run it

Prerequisites: JDK 17+, Maven 3.9+, Node 18+, Docker (or a local MySQL 8).

## 1. Database

```bash
docker compose up -d                 # MySQL 8 on :3306, db/user/pass = cloudops
```

Hibernate creates the tables on first start (`ddl-auto: update`).
To load the demo rows manually instead: `mysql -u cloudops -p cloudops < db/schema.sql && mysql -u cloudops -p cloudops < db/seed.sql`

## 2. Backend

```bash
cp .env.example .env                 # optional: every value already has a default
set -a && source .env && set +a      # PowerShell: see the header of .env.example
cd backend && mvn spring-boot:run    # http://localhost:8080
```

Tests: `cd backend && mvn test` (H2 in MySQL mode, no external services needed).
API docs: http://localhost:8080/swagger-ui.html · Health: http://localhost:8080/actuator/health

## 3. Frontend

```bash
cd frontend && npm install && npm run dev     # http://localhost:5173, proxies /api to :8080
```

## 4. Log in

| username | role |
| --- | --- |
| admin | ADMIN — can approve and execute actions |
| operator | OPERATOR — can run analysis, cannot approve |
| viewer | VIEWER — read only |

All three are created on first start with the **same** password, `BOOTSTRAP_ADMIN_PASSWORD`
(default `Admin@12345`); `SEED_DEMO_USERS=false` creates only `admin`. Change it from the Users
screen after logging in. If you instead loaded `db/seed.sql` into an empty database *before*
the first start, the passwords are `Admin@12345`, `Operator@12345` and `Viewer@12345`.

## 5. Drive the workflow

With `CLOUD_PROVIDER=mock` (default) no AWS account is needed: the mock instance `demo-web-01`
reports 92–98% CPU against a 90% threshold, and settles for 30 minutes once it is rebooted, so
a remediation has a visible effect.

1. Dashboard → **Sync resources** — imports instances
2. Dashboard → **Collect metrics now** — polls, detects, opens an incident
3. Incident → **Run AI analysis** — diagnosis + recommendation from the action catalog
4. Approvals → **Approve and execute** as `admin` — runs the predefined AWS SDK call
5. Incident → **Verify recovery**

## 6. Switches

| variable | values | effect |
| --- | --- | --- |
| `CLOUD_PROVIDER` | `mock` \| `aws` | `aws` uses the default credential chain (`aws configure`, instance role, env vars) |
| `AI_PROVIDER` | `stub` \| `http` | `http` needs `AI_API_KEY`, `AI_BASE_URL`, `AI_MODEL`; `AI_MODEL_<STAGE>` overrides per stage |
| `REMEDIATION_ENABLED` | `true` \| `false` | `false` records approvals without touching AWS |
| `REMEDIATION_FORCE_DRY_RUN` | `true` \| `false` | `true` validates and audits every action without calling AWS |
| `MONITORING_ENABLED` | `true` \| `false` | disables the background poller (manual collection still works) |

No secret is read from source: `JWT_SECRET`, `AI_API_KEY` and AWS credentials come from the
environment only. The model can never issue an AWS call of its own — it may only select an
action from the server-side catalog, and an ADMIN must approve it before it runs.
