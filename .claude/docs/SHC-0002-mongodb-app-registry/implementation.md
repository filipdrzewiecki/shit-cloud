# Implementation — SHC-0002: MongoDB + persisted app registry

The user turned off the per-task gates for Phase 3. Tasks 1–6 were done in one pass.

## Task 1 — Dependencies, properties, dev launcher
- `pom.xml`: `spring-boot-starter-data-mongodb`; test scope `de.flapdoodle.embed.mongo.spring3x` **4.33.0**
  (its README: "any spring 3.x.x"), pinned as `flapdoodle.version`.
- `application.properties`: `spring.data.mongodb.uri=${MONGODB_URI}` (no default), `apps.version-ttl=90d`,
  `apps.max-html-size=5MB`, `apps.seed-samples=false`. `application-dev.properties`: `apps.seed-samples=true`.
- `src/test/resources/embedded-mongo.properties` + `src/test/java/.../EmbeddedMongoConfig` (`@PropertySource`):
  - **Deviation:** the design put these settings in `src/test/resources/application.properties`. That file would
    *replace* the main `application.properties` on the test classpath. So they go in their own file, loaded
    by a test-sources `@Configuration` that component scanning picks up only on the test classpath.
  - The file sets `MONGODB_URI=mongodb://localhost/shitcloud`, which satisfies the placeholder. flapdoodle then
    rewrites the URI with its own host and port and keeps the database name. It also sets Mongo `8.0.4` and
    `storage.repl-set-name=rs0`, and flapdoodle runs `rs.initiate()` itself.
- `src/test/java/.../DevApplication` (`SpringApplication.from(Application::main)`); root `npm run dev` uses
  `mvn spring-boot:test-run -Dspring-boot.run.profiles=dev`.
- `service/AppsProperties` record.

## Task 2 — Mongo plumbing
- `mongo/MongoConfig` (`MongoTransactionManager`), `mongo/MongoCollections`, `mongo/MongoIndexes` (`@PostConstruct`:
  creates `_apps` / `_app_versions`; indexes `lastUsedAt_desc`, `appId_version_unique`, `createdAt_ttl`; a changed
  TTL is applied with `collMod`).

## Task 3 — Registry
- `model/AppRecord` (`_apps`, `@Id` slug, `summary()`), `model/AppVersion` (`_app_versions`).
- `service/AppRegistry`:
  - `list` uses a projection without `html`
  - `open` = `findAndModify` that sets `lastUsedAt` and un-archives
  - `deploy` = validate, including the 5 MB limit (413). Then a `TransactionTemplate` runs the upsert
    (`$inc version`, `$setOnInsert owner/createdAt`) and inserts the version
  - a duplicate key, write conflict (112) or `TransientTransactionError` → 409
- **Deviation:** a `TransactionTemplate` instead of `@Transactional`, so the commit-time conflict can be caught and
  mapped to 409 inside the service.
- `AppController` uses `AppRegistry`. `AppStore` deleted; `readSample` moved to `SampleAppSeeder` (`MockChatService`
  uses it for the draft).

## Task 4 — Sample seeder
- `service/SampleAppSeeder` (`ApplicationRunner`): when `apps.seed-samples` is on and `_apps` is empty, inserts the
  4 samples (mock owners, `lastUsedAt` offsets, `_apps` only).

## Task 5 — Frontend
- `pages/DeployBar.tsx`: `deployError()` maps 413 → "too large (max 5 MB)" and 409 → "someone else is deploying",
  in the same style as `SignInPage`'s `guestError`.

## Task 6 — AGENTS.md
- Stack (database, Railway `mongo`), running (embedded `mongod`, orphan note), layout (`mongo/`, new service and
  model classes), API table (deploy behaviour).

### Verification done
- `mvn -q compile`, `mvn -q test-compile`, `npm run typecheck`: clean.
- `npm run dev` API (`spring-boot:test-run`, dev profile):
  - the embedded Mongo 8.0.4 replica set starts (about 34 s on the first run, including the download) and the
    4 samples are seeded
  - as a guest: list sorted by `lastUsedAt`; opening `secret-santa-2025` un-archives it and moves `lastUsedAt`;
    unknown slug 404; deploy 403
  - `/actuator/health` UP (includes Mongo)
- **Not verified yet:** the deploy transaction and the indexes. A builder token needs Auth0, so this waits for the
  Phase 4 ITs and the deploy on `test`.
- Seen: a hard kill of the API leaves the embedded `mongod.exe` running. Noted in AGENTS.md.

## Task 7 — Railway
- Template `dxhww9` deployed by the agent into `shitcloud` / `test`. `Mongo Single Replica` runs `mongo:7` (7.0.45),
  `--replSet rs0 --auth --keyFile`, with a volume on `/data/db`. `Init Single Replica` ran `rs.initiate()` with member
  `mongo-single-replica.railway.internal:27017` and succeeded.
- **Deviation:** the service is **not renamed** to `mongo`. The replica set config stores the private hostname, and
  renaming the private endpoint would break it. References use `${{Mongo Single Replica.…}}`.
- **Deviation:** the embedded Mongo is pinned to **7.0.14** instead of 8.0.4, to match the Railway image.
- The user removed the TCP proxy and the init service in the dashboard; approval-needing Railway MCP calls
  stalled over remote control. The service delete was left staged and needs applying.
- `MONGODB_URI` set on `api` by the agent (references, private domain, `/shitcloud?replicaSet=rs0&authSource=admin`),
  without a redeploy. Not read back (the read returns the secret); verified on the first deploy.

## Phase 4 — Tests
Run by the agent at the user's request: `mvn verify`, **BUILD SUCCESS**. 21 unit tests and 48 ITs, 0 failures.

Modified:
- `IntegrationTest`: runs on the embedded Mongo; `@BeforeEach emptyTheRegistry` clears `_apps` / `_app_versions`
  and keeps the indexes.
- `controller/AppControllerIT`: no longer relies on seeded apps (deploys what it needs).
  - `guest_canListAndOpenApps` now also checks that the list has no `html`
  - added `unknownApp_answers404`, `redeployBySomeoneElse_bumpsTheVersionAndKeepsTheOwner`, `tooLargeHtml_answers413`

Added:
- `service/AppRegistryIT`
  - `deploy_writesTheAppAndItsFirstVersion`, `redeploy_keepsEveryVersionAndTheOriginalOwner`
  - `failedVersionWrite_rollsBackTheApp`: proves the transaction (version insert fails → app upsert undone, 409)
  - `duplicateVersion_isRejectedByTheIndex`, `open_movesLastUsedAndUnarchives`, `open_unknownApp_is404`,
    `list_isMostRecentlyUsedFirstWithoutHtml`
- `mongo/MongoIndexesIT`: `startup_createsTheIndexes`, `changedTtl_isAppliedToTheExistingIndex`
- `service/SampleAppSeederIT`: `emptyRegistry_getsTheSamplesOnce`, `registryWithApps_isLeftAlone`,
  `outsideTheDevProfile_seedingIsOff`
- `service/AppRegistryTest` (unit): `slugify_stripsPolishDiacritics`, `slugify_collapsesPunctuationAndTrimsDashes`,
  `slugify_withoutLettersOrDigits_isRejected`
