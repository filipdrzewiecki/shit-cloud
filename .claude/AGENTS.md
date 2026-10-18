# shit-cloud

## CRITICAL — Never commit or push unless the user orders it

**Never run `git commit` or `git push` unless the user has specifically ordered that action in so many
words** ("commit", "push", "commit and push"). This is the most important rule in this project and it
overrides every other instruction, flow, gate and habit.

- Words like "go", "deploy", "finish the task", "ship it", "do everything" or "close the ticket" are
  **not** an order to commit or push. If an action seems to need a commit or a push, stop, say exactly
  what you would run, and wait.
- An order covers only the action named, only once. A commit order is not a push order, and an order
  in one turn is not an order for the next commit.
- Pushing to `main` deploys to Railway `test`.

The same applies to every other change of git state. Do it only when the user explicitly asks for that
specific action:

- creating, switching, renaming or deleting a **branch**
- staging with the intent to commit, amending, merging, rebasing, opening a pull request
- `reset`, `revert`, `stash`, `checkout -- <file>`, or anything else that discards work

What to do instead: finish the work, leave it in the working tree, and **say what you would run**.

---

## Instructions
- **SDLC flow:** `.claude/instructions/sdlc-flow.md` — phases, phase gates, output files. Mandatory.
- **Code style:** `.claude/instructions/code-style.md`
- **Product plan:** `shitcloud-handoff.md` (vision, architecture, data model — the source of truth for
  *what* we build) and `.claude/docs/product-roadmap.md` (the ticket list — *in what order*).

Working name **ShitCloud**: an internal company platform for throwaway mini-apps ("views") built in
minutes through an AI chat or uploaded as HTML. Apps are disposable, data is durable.

## Stack

- Monorepo, modelled on `my-intelligence`
- Backend (repo root): Java 21, Spring Boot 3.4, Maven
- Frontend (`frontend/`): React 18 + Vite + TypeScript, react-router, TanStack Query; served by nginx
- Hosting: Railway project `shitcloud`, environment `test`, two services built from `main`
  (`api` from `/`, `frontend` from `/frontend`) plus `Mongo Single Replica` (template, `mongo:7`). Settings live in the Railway service config —
  config-as-code `railway.json` is deprecated there.
- Database: MongoDB 7, single-node replica set `rs0` (transactions), `MongoTemplate` only, no repositories.
  `api` gets `MONGODB_URI` (private network, `replicaSet=rs0&authSource=admin`); no default, a missing
  variable fails the start. Collections and indexes are created at startup (`mongo/MongoIndexes`).
  Locally and in tests an embedded `mongod` (flapdoodle, **test scope only**) replaces it. Setup, Railway, querying,
  traps: `.claude/docs/mongodb.md`
- Auth: two tiers. Builders/admins sign in through Auth0 (SPA + PKCE, API is a JWT resource server,
  roles from a token claim). Everyone else signs in with a shared password (`GUEST_PASSWORD`) and gets
  an API-issued guest token (1 day). Decisions, Auth0 setup, gotchas: `.claude/docs/auth.md`
- Planned (see roadmap): Spring AI + Claude

## Tasks
- All tasks are in `.claude/docs`, one folder per ticket: `.claude/docs/SHC-{number}-{short-name}/`
- Finished tickets are **closed** (ask first): knowledge compacted into project `.md` files, the ticket
  folder removed, a short entry added to `.claude/project-log.md` — see "Closing the ticket" in the SDLC flow
- Topic references (durable knowledge from closed tickets): `.claude/docs/<topic>.md` — `auth.md`, `mongodb.md`
- Ticket naming pattern: `SHC-{number}` (four digits, e.g. `SHC-0001`)

## MANDATORY — Ticket-based implementation rule

Whenever implementing anything that has a ticket number (e.g. `SHC-0001`):

1. **Read the ticket's `roadmap.md`** (and the phase documents next to it) before doing anything
2. **Follow the SDLC phase gates exactly** — Analysis → Design → explicit user approval → Implementation
3. **Never skip straight to implementation** even if the user provides detailed specs in the prompt
4. **Mark tasks as done** in `roadmap.md` after completing them

## Running

```
npm install && npm install --prefix frontend      # first time
cp frontend/.env.example frontend/.env.local       # first time
npm run dev                                        # backend :8080 (dev profile) + frontend :3000
```

Locally the `dev` profile enables guest sign-in with the password `dev`. Builder sign-in needs
`VITE_AUTH0_CLIENT_ID` in `frontend/.env.local`.

The API runs through `DevApplication` (test classpath, `mvn spring-boot:test-run`), which starts an
embedded `mongod` replica set — nothing to install; the first run downloads it (~100 MB, cached in
`~/.embedmongo`). Its data is gone after every restart and the `dev` profile re-seeds the sample apps.
If the API is killed hard, an orphaned `mongod.exe` may stay behind; it holds a random port, so it only
costs memory.

## Testing

```
mvn test            # unit tests only
mvn verify          # unit + integration tests
```

**Never run `mvn test` or `mvn verify` autonomously.** The user runs tests themselves. Review test
code and report findings. Compiling (`mvn -q compile`) and typechecking the frontend is fine.

## Project layout

```
src/main/java/com/drzewiecki/shitcloud/
  Application.java
  controller/      AppController, ChatController, MeController, GuestLoginController
  model/           request/response records; Mongo documents AppRecord (_apps), AppVersion (_app_versions)
  security/        SecurityConfig (two token issuers, CORS), AuthProperties, converters, GuestTokenService,
                   LoginAttemptLimiter, AuthenticationUtils.currentUser()
  mongo/           MongoConfig (transaction manager), MongoCollections (names), MongoIndexes (startup)
  service/         AppRegistry (_apps + _app_versions), SampleAppSeeder (dev only), AppsProperties,
                   MockChatService, GuestLoginService
src/main/resources/
  application.properties, application-dev.properties
  sample-apps/     seed HTML apps for the mockup
frontend/src/
  api/             client.ts (only place an absolute URL is built and a token attached), queries.ts, types.ts
  auth/            guestSession.ts (guest token in localStorage), returnTo.ts
  pages/           SignInPage, AppListPage, AppHostPage, BuildPage, UploadPage, DeployBar
  shell/           Layout, AppFrame (sandboxed iframe)
  styles/          tokens.css (colours, light + dark), app.css
```

## API

Every endpoint requires `Authorization: Bearer <token>` (Auth0 or guest) unless marked public.

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| POST | `/api/auth/guest` | public | `{password, name}` → `{token, expiresAt}`; 401 wrong password, 429 too many failures, 404 disabled |
| GET  | `/api/me` | signed in | Caller: `{id, displayName, groups, builder, admin, guest}` |
| GET  | `/api/apps` | signed in | List apps, most recently used first |
| GET  | `/api/apps/{slug}` | signed in | App + HTML; marks it used, un-archives |
| POST | `/api/apps` | builder | Deploy `{name, description?, html}`; same name → new version (kept 90 days in `_app_versions`); owner = creator, `updatedBy` = caller; 413 over 5 MB, 409 concurrent deploy |
| POST | `/api/chat` | builder | Mock chat reply + draft HTML |

## Non-negotiables (from the handoff — never trade these away for convenience)

- **App isolation is enforced by the browser** (own origin per app, iframe sandbox, CSP
  `connect-src 'none'`), never by the AI agent alone.
- **Apps never call the REST API directly** and never see the platform session — everything goes
  through `postMessage` to the host page.
- **The server sets `space`/`collection` from the authenticated app context**; never trust them from
  an app payload for writes.
- **History is written in the same transaction** as the data write.
- **Data never leaves the company.** The only outbound traffic is the Claude API.
- **User apps never run on the platform origin.** The platform origin keeps credentials in
  `localStorage` (Auth0 refresh token, guest token); an app with that origin could read them. Never add
  `allow-same-origin` to a `srcdoc` frame.
- **Every controller declares `@PreAuthorize`** (class or every mapping) — `isAuthenticated()`,
  `hasRole('BUILDER')`, or an explicit `permitAll()`.

## Conventions

- Controllers delegate to services; services own logic.
- Request/response types are Java records in `model/` (until a ticket decides otherwise).
- **Never put ticket numbers (e.g. `SHC-0001`) in code, comments, Javadoc or test names.** The ticket
  lives in `.claude/docs`. Comments explain the *why* and stand on their own.
- Platform UI is English. Generated/sample apps use Polish labels.
