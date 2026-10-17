# shit-cloud

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
  (`api` from `/`, `frontend` from `/frontend`). Settings live in the Railway service config —
  config-as-code `railway.json` is deprecated there.
- Auth: two tiers. Builders/admins sign in through Auth0 (SPA + PKCE, API is a JWT resource server,
  roles from a token claim). Everyone else signs in with a shared password (`GUEST_PASSWORD`) and gets
  an API-issued guest token (1 day). Setup steps: `.claude/docs/SHC-0001-auth0-login/solution-design.md` §3
- Planned (see roadmap): MongoDB single-node replica set, Spring AI + Claude

## Tasks
- All tasks are in `.claude/docs`, one folder per ticket: `.claude/docs/SHC-{number}-{short-name}/`
- Finished tickets get a `-DONE` suffix and move to `.claude/docs/archived/`
- Ticket naming pattern: `SHC-{number}` (four digits, e.g. `SHC-0001`)

## MANDATORY — Never change git state unless asked

**Do not touch the repository's git state unless the user has explicitly asked for that specific
action.** This covers, at minimum:

- creating, switching, renaming or deleting a **branch**
- **committing**, amending, or staging with the intent to commit
- **pushing**, opening a pull request, merging, rebasing
- `reset`, `revert`, `stash`, `checkout -- <file>`, or anything else that discards work

"The change is finished" is not permission to commit it. Approval for one action is not approval for
the next one — each is asked for separately. Pushing to `main` deploys to Railway.

What to do instead: finish the work, leave it in the working tree, and **say what you would run**.

---

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
  model/           request/response records
  security/        SecurityConfig (two token issuers, CORS), AuthProperties, converters, GuestTokenService,
                   LoginAttemptLimiter, AuthenticationUtils.currentUser()
  service/         AppStore (in-memory, until MongoDB), MockChatService, GuestLoginService
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

## API (current — in-memory data)

Every endpoint requires `Authorization: Bearer <token>` (Auth0 or guest) unless marked public.

| Method | Path | Access | Description |
|--------|------|--------|-------------|
| POST | `/api/auth/guest` | public | `{password, name}` → `{token, expiresAt}`; 401 wrong password, 429 too many failures, 404 disabled |
| GET  | `/api/me` | signed in | Caller: `{id, displayName, groups, builder, admin, guest}` |
| GET  | `/api/apps` | signed in | List apps, most recently used first |
| GET  | `/api/apps/{slug}` | signed in | App + HTML; marks it used, un-archives |
| POST | `/api/apps` | builder | Deploy `{name, description?, html}`; same name → new version; owner = caller id |
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
