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

Working name **ShitCloud**, shown as **S.H.I.T. Cloud** = *Simple HTML Internal Tools* (GUI brand, sign-in page,
app list; the spoken name in messages stays "ShitCloud"): an internal company platform for throwaway mini-apps ("views") built in
minutes through an AI chat or uploaded as HTML. Apps are disposable, data is durable.

## Stack

- Monorepo, modelled on `my-intelligence`
- Backend (repo root): Java 21, Spring Boot 4.1 (Framework 7, Security 7), Maven. JSON is Jackson 3: import
  `tools.jackson.*` (only annotations stay `com.fasterxml.jackson.annotation`); one test starter per technology
- Frontend (`frontend/`): React 18 + Vite + TypeScript, react-router, TanStack Query; served by nginx
- Hosting: Railway project `shitcloud`, environment `test`, two services built from `main`
  (`api` from `/`, `frontend` from `/frontend`) plus a `Postgres` database service. Settings live in the Railway service config —
  config-as-code `railway.json` is deprecated there.
- Database: PostgreSQL (Railway: 18), Spring `JdbcClient` with hand-written SQL only — no JPA, no repositories.
  Schema by Flyway migrations in `resources/db/migration` (never change an applied one; add `V2__…`). App data is
  JSONB. `api` gets `DATABASE_JDBC_URL`, `DATABASE_USER`, `DATABASE_PASSWORD` (references to the Postgres
  service); no defaults, a missing one fails the start. Old versions/history are removed nightly by `db/ExpiryJob`
  (no TTL indexes in Postgres). Locally and in tests an embedded PostgreSQL (zonky, **test scope only**) replaces it.
  Setup, Railway, querying, traps: `.claude/docs/postgres.md`
- Auth: two tiers. Builders/admins sign in through Auth0 (SPA + PKCE, API is a JWT resource server,
  roles from a token claim). Everyone else signs in with a shared password (`GUEST_PASSWORD`) and gets
  an API-issued guest token (1 day). Decisions, Auth0 setup, gotchas: `.claude/docs/auth.md`
- Apps origin: the API listens on a **second port** (`apps.port`, `APPS_PORT`, default 8081) that serves only
  app documents and the library mirror `/_lib/` (Alpine, Tailwind browser build, Chart.js — pinned in
  `apps-lib/LICENSES.md`); `PortFence` 404s everything else on each port. It has its own Railway domain.
  `APPS_URL_PATTERN` (no default) places `{slug}` in the host (one origin per app, needs a wildcard domain)
  or the path (one shared apps origin — `test`). App names whose slug is `api`, `actuator` or `error` are refused.
  Details, CSP, Railway domains, gaps: `.claude/docs/app-origin.md`
- App data: one `docs` table (`space` = app slug, `collection`, `doc_id`, app fields in the JSONB `data`),
  earlier states in `doc_history` (same transaction, removed after 90 days). Apps reach it only through the SDK
  (`window.shitcloud.db` / `me()`) → `postMessage` → host page bridge (`shell/appBridge.ts`) → Data API.
  SDK, rules, limits, retries: `.claude/docs/data-api.md`
- Build chat: Spring AI 2.0 + Claude (`spring-ai-starter-model-anthropic`), streamed to the client as SSE in the
  AI SDK UI message stream v1; protocol, design and gotchas: `.claude/docs/chat-protocol.md`

## Tasks
- All tasks are in `.claude/docs`, one folder per ticket: `.claude/docs/SHC-{number}-{short-name}/`
- Finished tickets are **closed** (ask first): knowledge compacted into project `.md` files, the ticket
  folder removed, a short entry added to `.claude/project-log.md` — see "Closing the ticket" in the SDLC flow
- Topic references (durable knowledge from closed tickets): `.claude/docs/<topic>.md` — `auth.md`, `postgres.md`, `app-origin.md`, `data-api.md`, `app-management.md`, `chat-protocol.md`
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
npm run dev                                        # backend :8080 + apps :8081 (dev profile) + frontend :3000
```

Locally apps run at `http://{slug}.apps.localhost:8081/` (browsers resolve `*.localhost` to this machine).

Locally the `dev` profile enables guest sign-in with the password `dev`. Builder sign-in needs
`VITE_AUTH0_CLIENT_ID` in `frontend/.env.local`.

The API runs through `DevApplication` (test classpath, `mvn spring-boot:test-run`), which starts an
embedded PostgreSQL — nothing to install, no Docker; the binaries come from Maven (`embedded-postgres-binaries`)
and unpack into `%TEMP%/embedded-pg`. Its data is gone after every restart and the `dev` profile re-seeds the
sample apps. If the API is killed hard, orphaned `postgres.exe` processes may stay behind on a random port; they
only cost memory (stop them in Task Manager).

## Testing

```
mvn test            # unit tests only
mvn verify          # unit + integration tests
npm test --prefix frontend   # Vitest: app bridge + the injected SDK (no DOM, hand-made windows)
```

**Never run `mvn test` or `mvn verify` autonomously.** The user runs tests themselves. Review test
code and report findings. Compiling (`mvn -q compile`) and typechecking the frontend is fine.

## Project layout

```
src/main/java/com/drzewiecki/shitcloud/
  Application.java
  controller/      AppController, MeController, PlatformController, GuestLoginController, DataController,
                   DepsController (/api/deps/{space})
  chat/            generic, copyable build-chat core — imports nothing else from ShitCloud (ChatPackageBoundaryTest):
                   ChatEngine (own step loop over Spring AI ChatModel.stream: text → tool calls → run → next step),
                   UiStreamWriter + UiParts (AI SDK UI message stream v1), ChatTool/ToolResult (plug-in tools),
                   HistoryMapper (saved UIMessages → prompt; only the latest data part in full), ConversationStore,
                   DataParts (a tool reads the latest data part, e.g. the draft, via TurnContext.data())
  buildchat/       ShitCloud's chat: BuildChatController (/api/chat), BuildChatPrompt, tools SaveDraftTool,
                   EditDraftTool (exact-text edits), GetAppTool, ListSpacesTool, SampleDocsTool, GetDepsTool;
                   Drafts (checks, base)
  data/            DataService (docs + doc_history, row locks), FilterTranslator (Mongo-style filter → SQL on JSONB),
                   DataProperties, records, DataException + DataExceptionHandler ({code, message} bodies),
                   DependencyLog (app_deps: which app used which data, from the X-ShitCloud-App header)
  apps/            the apps port: AppsConnectorConfig (2nd Tomcat connector), PortFence, AppsSecurityConfig
                   (own filter chain, framing allowed), AppOrigin (url pattern), AppDocumentController (CSP),
                   SdkInjector, LibraryMirrorConfig
  model/           request/response records; rows AppRecord (apps), AppVersion (app_versions)
  security/        SecurityConfig (two token issuers, CORS), AuthProperties, converters, GuestTokenService,
                   LoginAttemptLimiter, AuthenticationUtils.currentUser()
  db/              ExpiryJob (nightly removal past retention), Timestamps (timestamptz ↔ Instant)
  service/         AppRegistry (apps + app_versions), SampleAppSeeder (dev only), AppsProperties,
                   MockChatService, GuestLoginService
src/main/resources/
  application.properties, application-dev.properties
  db/migration/    Flyway SQL migrations (V1 apps, app_versions, docs, doc_history; V2 archive; V3 chat_conversations,
                   chat_messages; V4 app_deps)
  build-chat/      chat-rules.md (system prompt preamble); builder-guide.md is copied in from frontend/src/guide by
                   the build (pom resources + Dockerfile) — one guide for /guide and the chat
  sample-apps/     seed HTML apps for the mockup
  apps/sdk.js      inlined as the first script of every app document: window.shitcloud.me() / db.*
  apps-lib/        the library mirror served at /_lib/ + LICENSES.md (versions)
frontend/src/
  api/             client.ts (only place an absolute URL is built and a token attached), queries.ts, types.ts
  auth/            guestSession.ts (guest token in localStorage), returnTo.ts
  chat/            the build chat's UI, copyable (boundary.test.ts forbids GUI imports): ChatPanel (useChat +
                   own parts, Stop, "Working… N s"), serverChat.ts (transport: own fetch, sends only the new message),
                   drafts.ts, toolLabels.ts, Markdown (react-markdown), chat.css (own vars, GUI tokens as fallback)
  pages/           SignInPage, AppListPage, AppHostPage (+ AppManageMenu: "Change with AI"), BuildPage
                   (conversations · ChatPanel · preview; ?c=<id> / ?app=<slug>), ReplaceBar, UploadPage, DeployBar,
                   BuilderGuidePage
  guide/           builder-guide.md: the app rules written for an AI assistant (shown at /guide, copied by builders)
  shell/           Layout, AppFrame (deployed app: apps-origin src + allow-same-origin), appBridge.ts +
                   useAppBridge (answers the app's SDK calls; accepts only its own frame + the apps origin),
                   DraftFrame (Build/Upload/Replace preview: srcdoc, opaque origin, <base href> = apps origin from
                   /api/platform so /_lib/ loads; draftHtml.ts)
  styles/          tokens.css (colours, light + dark), app.css
```

## API

Every endpoint requires `Authorization: Bearer <token>` (Auth0 or guest) unless marked public.

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| POST | `/api/auth/guest` | public | `{password, name}` → `{token, expiresAt}`; 401 wrong password, 429 too many failures, 404 disabled |
| GET  | `/api/me` | signed in | Caller: `{id, displayName, groups, builder, admin, guest}` |
| GET  | `/api/apps` | signed in | List apps, most recently used first |
| GET  | `/api/apps/{slug}` | signed in | App + HTML + `frameUrl` (on the apps origin); marks it used (an archived app stays archived) |
| POST | `/api/apps` | builder | Deploy `{name, description?, html}`; same name → new version (kept 90 days in `app_versions`); owner = creator, `updatedBy` = caller; 413 over 5 MB, 409 concurrent deploy; un-archives an archived app |
| POST | `/api/apps/{slug}/archive` · `/restore` | builder | Hide from the list (data, HTML, versions kept; `archivedAt/By` recorded) · bring back |
| PUT  | `/api/apps/{slug}/html` | builder | `{html}` → next version of that app; name, description, archive state and data unchanged |
| DELETE | `/api/apps/{slug}?confirm={slug}` | builder | Irreversible: app, versions, documents and history → `{documents, versions, history}`; wrong `confirm` → 400 |
| GET  | `/api/apps/{slug}/usage` | signed in | `{documents, collections, versions}` (shown before deleting) |
| GET  | `/api/apps/{slug}/export` | signed in | All the app's documents by collection → download `<slug>-data.json` (cap `data.max-export-documents`, 413 above) |
| POST | `/api/chat` | builder | Build chat: `{conversationId?, message, targetSlug?}` → SSE, AI SDK UI message stream v1 (`chat-protocol.md`); 503 `CHAT_UNAVAILABLE` without `ANTHROPIC_API_KEY`, 400/404 `{code,message}` before the stream |
| GET  | `/api/chat/conversations` · `/{id}` | builder | Caller's own conversations, newest first · one as `UIMessage[]` (someone else's → 404) |
| GET  | `/api/platform` | signed in | `{libraryBaseUrl}`: root of the apps origin, for draft previews' `<base href>` |
| POST | `/api/data/{space}/{collection}/query` | signed in | `{filter?, sort?, limit?}` → `[DocView]`; Mongo-style filter, whitelisted operators only (translated to SQL on JSONB) |
| PUT  | `/api/data/{space}/{collection}/{id}` | signed in | `{data, expectedVersion?}` → `DocView`; previous state → `doc_history`; last write wins unless `expectedVersion` (409 `VERSION_CONFLICT`) |
| POST | `/api/data/{space}/{collection}` | signed in | `{data}` → 201 `DocView` with a generated id |
| DELETE | `/api/data/{space}/{collection}/{id}` | signed in | `?expectedVersion=` → `{deleted}`; last state → `doc_history` |

Data API errors are `{code, message}` (`BAD_REQUEST`, `NO_SUCH_APP`, `VERSION_CONFLICT`, `CONFLICT`, `TOO_LARGE`);
`space` must be an existing app. Guests read and write too.

Apps port (public, no token): `GET /{slug}/` (path mode; `/{slug}` redirects) or `GET /` (host mode) → the app's
HTML with the SDK injected, CSP (`connect-src 'none'`, `frame-ancestors` = `cors.allowed-origins`), `no-store`.
`GET /_lib/{file}` → mirrored library, cached 1 day.

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
  `allow-same-origin` to a `srcdoc` frame — only `AppFrame`, whose `src` is the apps origin, has it.
- **The ports stay fenced:** the API is never answered on the apps port and app documents never on the API
  port (`PortFence`, tested both ways). App documents never get `X-Frame-Options`; `frame-ancestors` decides.
- **Every controller declares `@PreAuthorize`** (class or every mapping) — `isAuthenticated()`,
  `hasRole('BUILDER')`, or an explicit `permitAll()`.

## Conventions

- Controllers delegate to services; services own logic.
- Request/response types are Java records in `model/` (until a ticket decides otherwise).
- **Never put ticket numbers (e.g. `SHC-0001`) in code, comments, Javadoc or test names.** The ticket
  lives in `.claude/docs`. Comments explain the *why* and stand on their own.
- Platform UI is English. Generated/sample apps use Polish labels (the build chat: the user's language).
- Chat config: `ANTHROPIC_API_KEY` (Railway `api`; never read or print it), `CHAT_MODEL` (default
  `claude-sonnet-5-5`), `CHAT_MAX_TOKENS` (32000); tests and dev run on no key (`FakeChatModel` in chat ITs).
- **`frontend/src/guide/builder-guide.md` is a contract with outside AI assistants.** Whenever the SDK, the app
  CSP, the library mirror or Data API limits/operators change, update it in the same change.
