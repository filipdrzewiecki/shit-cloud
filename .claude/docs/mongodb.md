# MongoDB — reference

How the database is set up and why. The code is the source of truth for details. This file keeps what
the code cannot tell you: the decisions, the Railway setup, how to look inside, and the traps already hit.

## Model

- MongoDB **7**, single-node **replica set `rs0`**. The replica set exists only for multi-document
  transactions and change streams, at the cost of one instance.
- `MongoTemplate` only, no Spring Data repositories (later collections like `docs` are dynamic).
  `mongo/MongoConfig` registers the `MongoTransactionManager`; Spring Boot does not register one.
- Database `shitcloud` (the path of `MONGODB_URI`). Platform collections are prefixed with `_`:

| Collection | Document | Notes |
|---|---|---|
| `_apps` | `_id` = slug, `name`, `description`, `html`, `owner`, `version`, `lastUsedAt`, `archived`, `createdAt`, `updatedAt`, `updatedBy` | `owner` = creator, never changes; `updatedBy` = last deployer. Index `lastUsedAt_desc` |
| `_app_versions` | `appId`, `version`, `name`, `description`, `html`, `author`, `createdAt` | One per deploy. Unique `appId_version_unique`; TTL `createdAt_ttl` = `apps.version-ttl` (90 d). The live HTML stays in `_apps`, so the TTL never removes a running app |

- **Deploy** (`service/AppRegistry`) runs in one `TransactionTemplate`:
  - an upsert with `$inc version`, `$setOnInsert owner/createdAt`
  - then an insert into `_app_versions`

  A duplicate key, write conflict (112) or `TransientTransactionError` → **409** (concurrent deploy of the same
  app; the builder retries). HTML over `apps.max-html-size` (5 MB) → **413**. A failed version insert rolls back
  the app write (covered by `AppRegistryIT#failedVersionWrite_rollsBackTheApp`).
- **Indexes** are created at startup by `mongo/MongoIndexes` (`@PostConstruct`, before Tomcat takes traffic),
  never by scripts. Indexes cannot be created inside a transaction, so they must exist first. Creating a TTL
  index with a different expiry fails (`IndexOptionsConflict`), so a changed `apps.version-ttl` is applied
  with `collMod`.
- `/actuator/health` includes Mongo. If Mongo is down the API reports DOWN, and Railway won't promote a new
  `api` deploy. Accepted: the API is useless without Mongo.

## Configuration

- `spring.data.mongodb.uri=${MONGODB_URI}` with **no default**: a missing variable fails the start instead of
  quietly trying `localhost`.
- `apps.version-ttl=90d`, `apps.max-html-size=5MB`, `apps.seed-samples=false` (the `dev` profile turns seeding on).

## Railway (`shitcloud` / `test`)

- Service **`Mongo Single Replica`** from template `dxhww9` (`railwayapp-templates/mongo-replica-set`, `nodes/`):
  - image `mongo:7`, started as `mongod --replSet rs0 --auth --keyFile /data/keyfile --ipv6 --bind_ip ::,0.0.0.0`
  - key file generated from the `KEYFILE` variable
  - volume `mongo-single replica-volume` on `/data/db`
  - root user `mongo`, password `MONGO_INITDB_ROOT_PASSWORD`
- The template's one-shot `Init Single Replica` ran `rs.initiate()` with member
  `mongo-single-replica.railway.internal:27017`, then was deleted. The replica set config lives in Mongo's own
  data on the volume.
- **Never rename the service's private endpoint.** The replica set stores that hostname. The driver reads the
  member host from the config and reconnects to it, so a renamed endpoint means "no primary found". The display
  name stayed `Mongo Single Replica` for the same reason, and references use it.
- `api` variable:
  `MONGODB_URI=mongodb://${{Mongo Single Replica.MONGO_INITDB_ROOT_USERNAME}}:${{Mongo Single Replica.MONGO_INITDB_ROOT_PASSWORD}}@${{Mongo Single Replica.RAILWAY_PRIVATE_DOMAIN}}:27017/shitcloud?replicaSet=rs0&authSource=admin`
  - `authSource=admin`: the root user lives in `admin`.
  - Don't use the template's `MONGO_URL` (it goes through the TCP proxy) or `REPLICA_SET_PRIVATE_URI` (it lists
    `mongo2`/`mongo3`, which don't exist).
- **TCP proxy:** the user keeps one on purpose for now (`altaria.proxy.rlwy.net:18030` → 27017), to look at
  `test` data. The root account is reachable from the internet, protected by the password. Remove it before any
  real company data lands on `test`.
- No backups yet (SHC-0017). Deleting the service or its volume loses every app.

## Looking inside

- **MongoDB Compass** through the TCP proxy:
  `mongodb://mongo:<password>@<proxy-host>:<proxy-port>/shitcloud?authSource=admin&directConnection=true`.
  `directConnection=true` is required: without it the client follows the replica set config to
  `*.railway.internal` and fails. If the password has `@ : / ? #`, use the Authentication tab instead of the URI.
- **Without a public port:** `railway ssh --service "Mongo Single Replica"`, then
  `mongosh -u mongo -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin shitcloud`.
- In `mongosh`, use `db.getCollection('_apps')`, not `db._apps` (names starting with `_` clash with shell properties).

## Local development and tests

- An embedded `mongod` (flapdoodle `de.flapdoodle.embed.mongo.spring3x`, pinned in `pom.xml`) replaces Mongo.
  It is a **test-scope dependency only**: on the production classpath its auto-configuration would replace the
  real database.
- `src/test/java/.../DevApplication` (`SpringApplication.from(Application::main)`) is the dev entry point;
  `npm run dev` → `mvn spring-boot:test-run -Dspring-boot.run.profiles=dev`.
- `src/test/resources/embedded-mongo.properties` is loaded by `EmbeddedMongoConfig` (test sources, picked up by
  component scanning only on the test classpath). It sets:
  - `MONGODB_URI=mongodb://localhost/shitcloud`, which satisfies the placeholder; flapdoodle rewrites host and port
  - version `7.0.14`, the same major as Railway
  - `storage.repl-set-name=rs0`; flapdoodle runs `rs.initiate()`
  - These settings must not go in `src/test/resources/application.properties`, which would hide the main
    `application.properties` on the test classpath.
- First run downloads `mongod` (~100 MB, cached in `~/.embedmongo`). Data is gone after every restart; the dev
  profile re-seeds 4 sample apps (`SampleAppSeeder`, only when `_apps` is empty).
- A hard kill of the API can leave `mongod.exe` running on a random port. It only costs memory; kill it.
- ITs: `IntegrationTest` runs on the embedded Mongo, one `mongod` per cached Spring context, and empties
  `_apps` / `_app_versions` before each test (indexes kept). Don't rely on seeded data in tests.

## Accepted trade-offs

- Embedded Mongo is a different process from Railway (same major version) and an unofficial library that
  must keep up with Spring Boot.
- Root user for the API on `test`; on-prem gets a `readWrite` user on `shitcloud`.
- No automatic retry of a conflicting deploy (409, the builder retries).
- `_app_versions` is stored but not exposed by any endpoint yet.
- `test` starts with no sample apps; samples are dev-only.
