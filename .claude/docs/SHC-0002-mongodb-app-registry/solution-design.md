# Solution Design — SHC-0002: MongoDB + persisted app registry

Input: `analysis.md`. No reference implementation (my-intelligence has no Mongo).

---

# 1. User decisions (Phase 1 gate)

Answered by the user at the start of Phase 2.

| # | Question | Decision |
|---|---|---|
| U1 | Local Mongo | **Embedded only** (flapdoodle) for both `npm run dev` and ITs. No Docker. Dev data is gone after every restart (accepted) |
| U2 | Railway Mongo | **Template "MongoDB Single Replica"** (`dxhww9`, built from `railwayapp-templates/mongo-replica-set`, the official Railway templates org) |
| U3 | Seed data | Seed the sample apps **in the `dev` profile only** (when `_apps` is empty). `test` starts empty |
| U4 | Owner on redeploy | `owner` = the creator, never changes. Each version keeps its own `author`; `_apps` gets `updatedBy`/`updatedAt` |
| U5 | Railway setup | Done by the agent with the Railway tools (Task 7), confirmed right before |
| U6 | HTML size limit | **5 MB** |

# 2. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | `spring-boot-starter-data-mongodb`, **`MongoTemplate` only**, no repositories | Handoff §3. Later collections (`docs`) are dynamic |
| D2 | `MongoTransactionManager` bean. `AppRegistry.deploy` is `@Transactional`: it upserts `_apps` and inserts `_app_versions` in one transaction | The first real use of the replica set. If the transaction fails on Railway, it fails here, not in SHC-0004 |
| D3 | Version bump is an atomic `findAndModify` (upsert, `$inc: {version: 1}`, `$setOnInsert: {owner, createdAt}`), plus a **unique index `{appId, version}`** on `_app_versions` | Two concurrent deploys can never both write "v3". The loser gets a write conflict and answers 409 "Someone else is deploying this app, try again" |
| D4 | `_apps._id` = slug | Slug is already the identity in URLs; no second unique index |
| D5 | Collections and indexes are created at startup in `MongoIndexes` (`@PostConstruct`, so before Tomcat takes traffic): `_apps` `{lastUsedAt: -1}`; `_app_versions` unique `{appId, version}` + TTL on `createdAt` | Handoff §5: no manual scripts. Indexes cannot be created inside a transaction, so they must exist before the first deploy |
| D6 | TTL from `apps.version-ttl` (default `90d`). If the configured TTL differs from the existing index, startup runs `collMod` to change it | `createIndex` with different `expireAfterSeconds` throws `IndexOptionsConflict`; changing a property should not need a manual fix |
| D7 | List uses a projection without `html` | The list never needs it, and HTML is the bulk of each document |
| D8 | Deploy HTML limit `apps.max-html-size` (default `5MB`) → 413 | Cheap now. The 16 MB document limit would otherwise surface as a 500 |
| D9 | `MONGODB_URI` has **no default** in `application.properties`. Locally the embedded Mongo's auto-configuration supplies host and port | Fail fast: a Railway deploy without the variable must not silently try `localhost` |
| D9a | flapdoodle (`de.flapdoodle.embed.mongo.spring3x`) is a **test-scope** dependency only. Dev runs through a test-classpath launcher `DevApplication` (`SpringApplication.from(Application::main)`) with `mvn spring-boot:test-run` | The embedded Mongo can never end up in the production JAR, where its auto-configuration would replace the real database. This is Spring Boot's documented pattern for dev-time services |
| D9b | Embedded Mongo 8.0, started as replica set `rs0` (flapdoodle's replica-set storage property) | Transactions work locally and in ITs the same way as on Railway |
| D10 | Database name comes from the URI path (`/shitcloud`) | One place to change on-prem |
| D11 | `SampleAppSeeder` inserts the 4 sample apps (mock owners and `lastUsedAt` offsets as today, `_apps` only, no version records) when `apps.seed-samples=true` and `_apps` is empty. Default `false`; the `dev` profile turns it on | The embedded dev database starts empty on every run; `test` holds only real apps (U3) |
| D12 | Mongo health indicator stays on | Without Mongo the API is useless; Railway should not promote a deployment that cannot reach it |
| D13 | New package `mongo/` for Mongo plumbing (config, collection names, indexes). Documents are records in `model/`, logic in `service/AppRegistry`, and `AppStore` is deleted | Same layering as today; the Mongo specifics stay in one place |

# 3. Data model

```
_apps                                   _app_versions                     (TTL: createdAt + 90d)
{ _id: "lunch-poll",                    { _id: ObjectId,
  name, description, html,                appId: "lunch-poll", version: 3,
  owner,            ← creator             name, description, html,
  version: 3,       ← current             author,            ← this deploy's caller
  lastUsedAt, archived,                   createdAt }
  createdAt, updatedAt, updatedBy }     unique {appId: 1, version: 1}
index {lastUsedAt: -1}
```

Records in `model/`:
- `AppRecord(@Id String slug, name, description, html, owner, int version, Instant lastUsedAt,
  boolean archived, Instant createdAt, Instant updatedAt, String updatedBy)` mapped to `_apps`
- `AppVersion(@Id String id, appId, int version, name, description, html, author, Instant createdAt)`
  mapped to `_app_versions`

API records (`AppSummary`, `AppDetail`, `DeployRequest`) are unchanged. `AppRecord` maps to them, so
the REST contract and the GUI stay the same.

# 4. Backend

## 4.1 Dependencies (`pom.xml`)
- `spring-boot-starter-data-mongodb`
- test: `de.flapdoodle.embed:de.flapdoodle.embed.mongo.spring3x`. Not managed by the Spring Boot parent:
  pick the newest 4.x release whose compatibility notes cover Boot 3.4, and pin it as a property

## 4.2 Properties

`application.properties`:
```properties
#====== MONGODB — single-node replica set rs0. No default on purpose: a missing variable must fail the start.
spring.data.mongodb.uri=${MONGODB_URI}

#====== APPS
apps.version-ttl=90d
apps.max-html-size=5MB
apps.seed-samples=false
```

`application-dev.properties`: `apps.seed-samples=true`.

Embedded Mongo settings (version `8.0.x`, replica set `rs0`, database `shitcloud`) go in
`src/test/resources/application.properties`, so they are on the test classpath only and apply to both
`DevApplication` and the ITs. The exact flapdoodle property names are checked against the chosen release in Task 1.

## 4.3 Classes

| Class | Responsibility |
|---|---|
| `mongo/MongoConfig` | `MongoTransactionManager` bean |
| `mongo/MongoCollections` | Collection name constants `APPS = "_apps"`, `APP_VERSIONS = "_app_versions"` |
| `mongo/MongoIndexes` | `@PostConstruct`: create the collections if missing, then indexes (D5, D6) |
| `service/AppsProperties` | `@ConfigurationProperties("apps")` record: `versionTtl` (`Duration`), `maxHtmlSize` (`DataSize`), `seedSamples` |
| `service/AppRegistry` | `list()`, `open(slug)`, `deploy(request, caller)` — same validation and `slugify` as `AppStore`, plus the size limit and the 409 mapping |
| `service/SampleAppSeeder` | `ApplicationRunner`; seeds when enabled and `_apps` is empty |
| `controller/AppController` | Uses `AppRegistry`; passes `currentUser().id()` as the caller |

`open(slug)` = `findAndModify({_id: slug}, {$set: {lastUsedAt: now, archived: false}}, returnNew)`;
`null` → 404.

## 4.4 Local development
- `src/test/java/com/drzewiecki/shitcloud/DevApplication.java`: `main` ->
  `SpringApplication.from(Application::main).run(args)`. On the test classpath, so flapdoodle starts
  an embedded `mongod` before the context uses it.
- Root `package.json` `dev`: `mvn spring-boot:test-run -Dspring-boot.run.profiles=dev` instead of `spring-boot:run`.
- The first run downloads the `mongod` binary for Windows (about 100 MB, cached in `~/.embedmongo`).
  After that it starts offline.
- Data lives in a temporary directory and is gone after every restart (U1); the `dev` profile re-seeds the samples.

# 5. Railway

Done by the agent with the Railway tools after you confirm (it creates services in `shitcloud`/`test`),
or by you in the dashboard:

1. Deploy template `dxhww9` into `shitcloud` / `test`. It creates `Mongo Single Replica` (volume
   `/data/db`, key file, root user) and `Init Single Replica`, a one-shot job that runs `rs.initiate()`
   with the **private** domain as the member host.
2. Rename `Mongo Single Replica` → `mongo`. Once the init job has succeeded, delete `Init Single Replica`.
3. **Delete the TCP proxy** if the template created one. Mongo must stay private-only (the
   template's `MONGO_URL` points at the proxy and is not used).
4. Check the Mongo version the template's Dockerfile builds (must be ≥ 7).
5. `api` variable:
   `MONGODB_URI=mongodb://${{mongo.MONGO_INITDB_ROOT_USERNAME}}:${{mongo.MONGO_INITDB_ROOT_PASSWORD}}@${{mongo.RAILWAY_PRIVATE_DOMAIN}}:27017/shitcloud?replicaSet=rs0&authSource=admin`
   (the template's `REPLICA_SET_PRIVATE_URI` lists `mongo2`/`mongo3` hosts that do not exist, so it is not used).
6. Only then push to `main`.

# 6. Acceptance criteria

1. An app deployed on `test` survives a redeploy and a restart of `api`.
2. Redeploying the same name → version N+1; `_app_versions` holds both N and N+1; `owner` is unchanged
   and `updatedBy` is the latest deployer.
3. Opening an app moves `lastUsedAt` and un-archives it, and this persists.
4. `dev` profile on an empty database → the 4 sample apps appear once. `test` starts with no apps.
5. After startup `_app_versions` has the unique `{appId, version}` index and the TTL index, and `_apps`
   has `{lastUsedAt: -1}`. No manual steps.
6. Railway Mongo: replica set `rs0`, no public TCP proxy, and a deploy (a transaction) succeeds.
7. HTML over 5 MB → 413. Missing `MONGODB_URI` → the API does not start.
8. `npm run dev` starts the embedded Mongo and the app works locally with no install; ITs run against the embedded Mongo.
9. REST contracts unchanged; the GUI only gains the two deploy error messages.

# 7. Implementation tasks (Phase 3)

Tasks 1–6 go to `main` together after Task 7. Nothing is pushed per task.

- Task 1 — `pom.xml` deps (data-mongodb; test-scope flapdoodle); `spring.data.mongodb.uri` + `apps.*`
  properties; dev + test properties; `DevApplication`; `npm run dev` via `spring-boot:test-run`; `AppsProperties`
- Task 2 — `mongo/MongoConfig`, `mongo/MongoCollections`, `mongo/MongoIndexes`
- Task 3 — `model/AppRecord`, `model/AppVersion`; `service/AppRegistry`; `AppController` switched; `AppStore` deleted
- Task 4 — `service/SampleAppSeeder` (dev profile)
- Task 5 — Frontend: `DeployBar` (used by build and upload) shows its own message for 413 ("App is too large, max 5 MB") and 409 ("Someone else is deploying this app, try again")
- Task 6 — `.claude/AGENTS.md`: stack, running, layout, Railway services
- Task 7 — Railway: Mongo template, rename, no TCP proxy, `MONGODB_URI` on `api` (§5; agent after confirmation)
- Task 8 — Deploy to `test` (you push) + walk acceptance criteria 1–9

Phase 4 tests: the `IntegrationTest` base runs on the embedded Mongo (one `mongod` per test JVM through the
cached Spring context) and empties the collections before each test, keeping the indexes. `AppControllerIT`
stops relying on the seeded `lunch-poll`. New: `AppRegistryIT` (deploy writes both collections; redeploy
bumps the version and keeps the owner; open un-archives; 404; 413; duplicate `{appId, version}` rejected),
`MongoIndexesIT` (indexes exist; a changed TTL is applied), `SampleAppSeederIT` (seeds an empty database
once; leaves a non-empty one alone), `AppRegistryTest#slugify_*` (unit).

# 8. Trade-offs

| Choice | Cost accepted |
|---|---|
| Embedded Mongo instead of a container | Unofficial library that has to keep up with Spring Boot; a different process than on Railway (same Mongo version). Dev data is lost on restart |
| Root Mongo user on `test` | The API has full rights on the server. Fine for one private-network database on test; on-prem gets a `readWrite` user on `shitcloud` |
| Third-party template code | Read before deploying; data lives on the volume, so the template can be replaced |
| No automatic retry of a conflicting deploy | A rare concurrent deploy of the same app answers 409; the builder clicks deploy again |
| No samples on `test` | `test` starts with an empty list until someone deploys or uploads |
| Mongo in `/actuator/health` | A Mongo outage blocks new API deployments on Railway |
| `_app_versions` stored but not exposed | No endpoint until a later ticket needs one; data accumulates for 90 days anyway |
