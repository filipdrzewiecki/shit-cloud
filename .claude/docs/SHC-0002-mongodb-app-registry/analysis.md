# Analysis — SHC-0002: MongoDB + persisted app registry

## Metadata

| Field               | Value                                                                                 |
|---------------------|---------------------------------------------------------------------------------------|
| **Description**     | Add MongoDB (single-node replica set) and move the app registry from memory into `_apps` + `_app_versions`. |
| **Ticket**          | SHC-0002                                                                              |
| **Endpoints**       | `GET /api/apps` <br> `GET /api/apps/{slug}` <br> `POST /api/apps` (contracts unchanged) |
| **Key classes**     | `AppStore` (replaced), `AppController`                                                |
| **Related classes** | `AppSummary`, `AppDetail`, `DeployRequest`, `IntegrationTest`, `AppControllerIT`, `sample-apps/*` |
| **Handoff**         | §3 Tech stack (Mongo, `MongoTemplate`, replica set), §5 Data model, §8 Railway deployment |
| **Railway**         | project `shitcloud`, env `test`: only `api` + `frontend` today; no database service   |

---

# 1. Problem

## 1.1. What is the problem?
- Apps live in a `ConcurrentHashMap`. Every redeploy or restart of `api` on Railway loses every app a
  builder deployed, and the four mock apps come back.
- There is no database at all. Every later ticket depends on one: the Data API with transactional
  history (SHC-0004), `_deps`, migrations, the history panel.
- Earlier versions of an app are thrown away on redeploy. The handoff wants them kept in
  `_app_versions` for about 90 days.

## 1.2. Why is it a problem? What may be problematic to implement?
- **Replica set on Railway.** A single `mongod` cannot run multi-document transactions, which SHC-0004
  needs (history in the same transaction as the write). It must run as `--replSet rs0` with a one-time
  `rs.initiate()`. Railway's standard Mongo template is a plain standalone server.
- **The replica set member's hostname is a trap.** A driver that connects to a replica set reads the
  member host from the replica set config and reconnects to *that* host. If `rs.initiate()` recorded
  `localhost:27017` or a container id, the API cannot reach it over Railway's private network. Fix:
  initiate with the private DNS name (`<service>.railway.internal:27017`), or connect with
  `directConnection=true` (fine for one node).
- **Auth + replica set needs a key file.** With access control on, `mongod --replSet` refuses to
  start without `--keyFile`, even on a single node. The start command or the image has to create one.
- **Local development: no Docker on this machine** (`docker`, `mongod` not found). This affects
  `npm run dev` and integration tests, which today start the whole Spring context and would now need a
  Mongo. See §3.1.
- **Transactions need explicit wiring.** Spring Boot does not register a `MongoTransactionManager`
  on its own. Collections and indexes must exist before the first transaction: creating a collection
  inside a transaction is allowed from Mongo 4.4 on, but index creation is not.
- **Healthcheck coupling.** With the Mongo starter, `/actuator/health` includes a `mongo` indicator.
  If Mongo is down, the API reports DOWN and Railway keeps the new deployment from going live. That is
  probably what we want, but it means a Mongo outage blocks API deploys.
- **Concurrent deploys of the same name** must not produce two "version 3"s: the version bump has
  to be atomic (`findAndModify` with `$inc`, unique index `{appId, version}`).

## 1.3. How the user would like to solve it
From the product roadmap and handoff:
- Mongo on Railway as a single-node replica set `rs0`, private network only.
- `MongoTemplate`, not Spring Data repositories (handoff §3: later collections are dynamic).
- `_apps` + `_app_versions` (TTL) replace `AppStore`. Indexes and TTLs are created at application
  startup through `indexOps`, not by manual scripts.
- A local dev setup: a container or embedded Mongo.
- Baby steps: the REST contract and the GUI stay as they are. Data model only, no new screens.

# 2. Context

## 2.1. Key context
- **`AppStore` today:** `list()` sorts by `lastUsedAt` desc; `open(slug)` sets `lastUsedAt = now` and
  `archived = false`. `deploy(request, owner)` slugifies the name; the same slug replaces the app,
  bumps `version` and sets `owner` to the latest deployer. Validation: name and HTML required, the
  name must contain letters or digits.
- **Seed data:** 4 sample apps (`presence-calendar`, `lunch-poll`, `parking-spots`,
  `secret-santa-2025`) with mock owners (`Filip`, `Anna`, `Tomek`). `draft.html` is the mock chat's draft.
  `test-apps/losowarka-standupu.html` is an untracked candidate for another sample.
- **GUI** reads `slug, name, description, owner, lastUsedAt, archived, version`. The list shows
  `v{version}` and "used {timeAgo}"; the host page shows `v{version} · by {owner}`.
- **Handoff data model:**
  - `_apps`: slug, name, owner, current HTML (or asset pointer), `lastUsedAt`, `archived`,
    token usage counter.
  - `_app_versions`: appId, version, HTML/assets, author, date; TTL ~90 days.
- **Tests:** `IntegrationTest` boots the full context with `@SpringBootTest`. `AppControllerIT` relies
  on the seeded `lunch-poll`. Tests are run by the user (`mvn verify`), never by the agent.
- **my-intelligence** uses no Mongo, so there is no reference implementation to copy this time.

## 2.2. Main flows

```
Deploy (builder)                                   Open (anyone signed in)
POST /api/apps {name, description, html}           GET /api/apps/{slug}
  │ slug = slugify(name)                             │ findAndModify _apps {_id: slug}
  │ ── transaction ──────────────────────┐           │   $set lastUsedAt=now, archived=false
  │ findAndModify _apps {_id: slug}      │           │   return new → 404 if missing
  │   upsert, $inc version,              │           └─ { app, html }
  │   $set name/description/html/…       │
  │ insert _app_versions                 │         List
  │   {appId, version, html, author, at} │         GET /api/apps
  │ ─────────────────────────────────────┘           └─ find _apps (no html), sort lastUsedAt desc
  └─ 201 AppSummary

Railway: api ──private network──▶ mongo (mongod --replSet rs0, volume, no public port)
TTL monitor (inside mongod) deletes _app_versions older than ~90 days
```

## 2.3. Current state of things
- No `spring-boot-starter-data-mongodb`, no `MONGODB_URI`, no Mongo service on Railway.
- `AppStore` is a `@Service` holding the map. Its in-memory semantics leak into tests (seeded
  `lunch-poll` in `AppControllerIT`; data deployed in one test is visible to the next).
- No version history exists. `AppSummary.version` is just a counter.

# 3. Options and recommendations for the open questions

## 3.1. Local Mongo (dev + integration tests)

| Option | + | − |
|---|---|---|
| **A. Docker Desktop**: `docker compose` with `mongo:8` `--replSet rs0` for dev, **Testcontainers** for ITs (recommended) | Same image as Railway. Testcontainers' `MongoDBContainer` is already a single-node replica set, so transactions work in tests. The usual Spring approach (`@ServiceConnection`) | You install Docker Desktop (WSL2) once. It uses memory while running |
| B. **Embedded (flapdoodle)** for dev and ITs | No install: downloads a `mongod` binary on first run. Replica set via a property | Unofficial and lags behind Spring Boot versions. The first run downloads about 100 MB. Not the same process as production. Dev data is thrown away on every restart |
| C. Native MongoDB Community on Windows (service), with `replSet` in `mongod.cfg` | No containers. Dev data persists | Manual setup with `rs.initiate()` on each machine. ITs would share your dev database unless they get their own database name |

Recommendation **A**. If installing Docker is unwanted, **C for dev + B for ITs** works too.

## 3.2. Mongo on Railway

| Option | + | − |
|---|---|---|
| **Template "MongoDB Single Replica"** (`dxhww9`, by Brody of Railway, about 30 deploys) (recommended) | Handles the key file, `rs.initiate()` and the member hostname. Volume included | Third-party template: it must be read before deploying, and pinning the image version is our job |
| Official `mongo` image + own start command (`mongod --replSet rs0 --keyFile …` + an init step) | Full control, nothing third-party | We write the key-file + `rs.initiate()` bootstrap and handle the hostname trap ourselves |

Either way: private network only (no TCP proxy), a volume, and `MONGODB_URI` on `api` as a reference
variable. Volume backups are SHC-0017.

## 3.3. Seed data
- Recommended: **seed the sample apps only when `_apps` is empty**, on every environment. `test` keeps
  something to click on, and real deploys are never overwritten. Mock owners stay (`Filip`, …).
- Alternatives: seed only with the `dev` profile, or never seed.

## 3.4. Owner on redeploy
- Today, redeploying the same name makes the latest deployer the `owner`. Recommended: **`owner` stays
  the creator; each `_app_versions` record keeps its own `author`**, and `_apps` gets
  `updatedBy`/`updatedAt` (the handoff rule: every write records the user id).
- Any builder may still redeploy any app by name. Restricting that is not asked for.

## 3.5. Document shape (proposal for Phase 2)
- `_apps`: `_id` = slug, `name`, `description`, `owner`, `html`, `version`, `lastUsedAt`, `archived`,
  `createdAt`, `updatedAt`, `updatedBy`. The token usage counter waits for SHC-0015.
  Index `{lastUsedAt: -1}`.
- `_app_versions`: `appId`, `version`, `name`, `description`, `html`, `author`, `createdAt`. Unique
  index `{appId, version}`, TTL on `createdAt` = 90 days (a property). The current HTML stays in
  `_apps`, so TTL never removes a live app.
- List queries leave `html` out (projection).
- Size: Mongo's 16 MB document limit is plenty. A deploy size limit (for example 2 MB, 413) is cheap
  to add now. Proper upload limits are SHC-0013/0016.

# 4. Acceptance criteria (draft, finalized in Phase 2)
1. An app deployed on `test` is still there after the `api` service redeploys or restarts.
2. Redeploying the same name gives version N+1, and version N stays readable in `_app_versions`.
3. Opening an app updates `lastUsedAt` and un-archives it, and this persists.
4. On an empty database the sample apps are seeded once; a later restart does not duplicate or
   overwrite them.
5. Indexes (`_app_versions` unique + TTL, `_apps` `lastUsedAt`) exist after startup without manual steps.
6. Mongo on Railway runs as replica set `rs0`, reachable only over the private network. A
   multi-document transaction (the deploy) succeeds against it.
7. `npm run dev` works locally with the chosen local Mongo; ITs run against a real Mongo.
8. REST contracts and GUI unchanged.

# 5. Test scenarios (for Phase 4)
- `AppControllerIT` (adapted): deploy → list → open round trip; redeploy bumps version; owner stays
  the creator; opening an archived app un-archives it; unknown slug → 404.
- `AppRegistryIT` (or similar): deploy writes `_apps` + `_app_versions` together; the unique
  `{appId, version}` index rejects a duplicate; indexes exist after startup; seeding is idempotent.
- Unit: `slugify` stays covered (moved with the code).
- Isolation: each IT starts from an empty or known database, not from what the previous test left.

# 6. Risks
| Risk | Mitigation |
|---|---|
| Replica set host unreachable from `api` | Template that initiates with the private host, or `directConnection=true`; checked with a deploy (transaction) on `test` |
| Third-party template changes or breaks | Read it before deploying, pin the image tag, data lives on the volume |
| ITs become slow or flaky with a real Mongo | One container per test run (Testcontainers reuse in the base class), clean collections between tests |
| Mongo outage makes `/actuator/health` DOWN and blocks API deploys | Accepted: the API is useless without Mongo. Revisit if it hurts |
| Deploying the backend before the Mongo service exists | Railway Mongo and the `MONGODB_URI` variable are set up first (manual task), then push |

# 7. Out of scope
- `docs`, `_history`, `_deps`, `_migrations` (SHC-0004, SHC-0010, SHC-0011)
- `GET /api/apps/{slug}/versions` and `PUT /api/apps/{slug}`. Versions are stored, not exposed yet
- Archiving after 30 days, admin delete (SHC-0014). Token counters (SHC-0015)
- Backups (SHC-0017)
