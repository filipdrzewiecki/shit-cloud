# Analysis — SHC-0001: Auth0 login + roles

## Metadata

| Field               | Value                                                                                   |
|---------------------|-----------------------------------------------------------------------------------------|
| **Description**     | Put the platform behind Auth0 login; user / builder / admin roles from a token claim.   |
| **Ticket**          | SHC-0001                                                                                |
| **Endpoints**       | `GET /api/me` <br> `GET /api/apps` <br> `GET /api/apps/{slug}` <br> `POST /api/apps` <br> `POST /api/chat` |
| **Key classes**     | `MeController`, `AppController`, `ChatController`, new `SecurityConfig`                 |
| **Related classes** | `CorsConfig` (folds into security config), `AppStore` (owner), `frontend/src/main.tsx`, `api/client.ts`, `shell/Layout.tsx` |
| **Reference impl**  | my-intelligence `security/*`, `frontend/src/main.tsx`, `.claude/docs/auth0-oauth2-guide.md` |
| **Handoff**         | §2 Users and roles, §3 Tech stack, §11 On-prem move                                     |

---

# 1. Problem

## 1.1. What is the problem?
- Every endpoint is open to anyone with the Railway URL — including deploy, which stores arbitrary
  HTML and serves it to other users.
- The user is a hard-coded constant (`MeController.MOCK_USER`); `owner` on deployed apps is always
  "Filip". The handoff requires **every write to record the user id** (`updatedBy`, `owner`,
  `changedBy`), and every later ticket (Data API, history, deps) builds on that.
- The three roles from the handoff do not exist. Builder-only actions (deploy, chat — later: migrations,
  restore) cannot be restricted.

## 1.2. Why is it a problem? What may be problematic to implement?
- **No database yet.** my-intelligence admits users through an `app_user` allowlist table and attaches
  privileges from it on every request. ShitCloud has nowhere to keep that until SHC-0002, so admission
  and roles must come from Auth0 / the token for now.
- **Roles must be configuration, not code.** Target is on-prem AD via ADFS or Entra ID (handoff §11),
  where builders are an AD group in a `groups` claim. Auth0 has no `groups` claim out of the box; it
  needs a post-login Action that writes a namespaced custom claim. The claim *name* and the group
  *values* must be properties, so the AD switch is a config change.
- **Identity in access tokens.** Auth0 access tokens carry only `sub` by default; email / name live in
  the ID token. my-intelligence hit this exactly (the email claim comes from a post-login Action that
  must write `api.accessToken`, not only `api.idToken`).
- **Session persistence in the browser.** The SPA (frontend domain) and Auth0 (tenant domain) are
  different sites, so iframe silent auth depends on third-party cookies, which browsers increasingly
  block. my-intelligence switched to refresh tokens in `localStorage` with rotation + reuse
  detection. Storing a credential in the platform origin's storage is only acceptable because user
  apps never run on the platform origin — today they run as `srcdoc` without `allow-same-origin`, and
  from SHC-0003 on their own origin. This dependency must be written down so nobody "simplifies" the
  iframe sandbox later.
- **Manual Auth0 dashboard work.** Tenant / API / SPA application / Action / roles are created in the
  Auth0 dashboard by the user; the agent cannot do it. Steps must be exact.
- **Callback / logout / web-origin URLs** must list both `http://localhost:3000` and the Railway GUI
  domain; CORS must allow the `Authorization` header.

## 1.3. How user would like to solve it
- Copy the my-intelligence approach: Auth0, SPA client with PKCE, API as JWT resource server
  (handoff §0: "authentication will be also auth0").
- Baby steps: only login + roles + real `/api/me` + owner from token. No user database, no admin UI.
- Rules:
  - every `/api/**` endpoint requires authentication; `/actuator/health` stays public (Railway
    healthcheck)
  - deploy and chat require the builder role; admin is reserved (no admin-only endpoint yet)
  - the SPA holds no secret; nothing secret is committed
  - every controller declares its authorization rule explicitly (my-intelligence convention)

# 2. Context

## 2.1. Key context
- **my-intelligence reference** (working in production):
  - `SecurityConfig`: stateless, CSRF off, `oauth2ResourceServer().jwt()` with `NimbusJwtDecoder`
    from the JWKS URI, issuer + audience validators, CORS bean listing exact origins, `Authorization`
    + `Content-Type` allowed headers.
  - `Auth0JwtAuthenticationConverter` → `UserAuthentication` (custom token carrying the JWT), logs
    every non-standard claim (`[JWT] custom claim: …`) — the fastest way to see what a token carries.
  - GUI: `@auth0/auth0-react` `Auth0Provider` inside `BrowserRouter` (for `onRedirectCallback` →
    `navigate`), `useRefreshTokens`, `useRefreshTokensFallback={false}`, `cacheLocation="localstorage"`,
    scope `openid profile email offline_access`, audience = API identifier; token injected in
    `api/client.ts` via `setTokenProvider`.
  - Four failure gates (Auth0 error page → 401 signature/issuer/audience → 403 allowlist → 403
    authorization). ShitCloud has no gate 3 yet.
- **ShitCloud today:** `CorsConfig` (`WebMvcConfigurer`, origins from `CORS_ALLOWED_ORIGINS`),
  `MeController.MOCK_USER`, `AppStore.deploy(request, owner)`, the GUI's `useMe()` already drives
  builder-only nav — so the UI side of roles is mostly wiring.
- **Railway:** api `https://api-test-27f4.up.railway.app`, GUI `https://frontend-test-7caa.up.railway.app`.
  `VITE_*` values are inlined at build time from the committed `frontend/.env.production` (public
  values only — an SPA client id is not a secret).

## 2.2. Main flows

```
Browser (GUI, frontend-test-…)          Auth0 tenant                    API (api-test-…)
  │  not signed in                          │                                │
  ├─ loginWithRedirect (PKCE) ─────────────▶│ login page (Google / email)    │
  │                                         │ post-login Action:             │
  │                                         │  + email, name, roles claims   │
  │◀──────────── code ──────────────────────┤                                │
  ├─ code + verifier ──────────────────────▶│                                │
  │◀──── access token (aud = shitcloud API) + refresh token ─────────────────│
  ├─ GET /api/me   Authorization: Bearer … ──────────────────────────────────▶│ JWKS signature,
  │                                                                          │ iss, aud → roles
  │◀──────────────────────────── { id, displayName, builder, admin } ────────┤ claim → authorities
  ├─ POST /api/apps (builder only) ──────────────────────────────────────────▶│ 403 if not builder
```

## 2.3. Current state of things
- No `spring-boot-starter-security` / `oauth2-resource-server` on the classpath.
- `MeController` returns a constant; `AppController.deploy` uses `MOCK_USER.displayName()` as owner
  (a display name, not a stable id).
- GUI has no Auth0 dependency, no login screen, no token handling; `api/client.ts` sends no
  `Authorization` header.
- No Auth0 tenant / API / application exists for ShitCloud.

# 3. Options and recommendations for the open questions

## 3.1. Auth0 tenant
| Option | + | − |
|---|---|---|
| **New tenant `shitcloud`** (recommended) | Separate users, Actions and roles; an Action edit for one project cannot break the other; mirrors the company/personal split of the two products | One more tenant to manage |
| Reuse `my-intelligence.eu.auth0.com` (new API + SPA app) | One dashboard | Post-login Actions are tenant-wide — they'd run for both products and must branch on client id, the exact trap documented in my-intelligence |

## 3.2. Who may log in on `test`
- No user table until SHC-0002. Options: (a) **disable public sign-ups in the Auth0 database
  connection and invite users manually** + Google social login restricted by a deny-by-default
  check in the post-login Action (email in an allowlist stored in the Action's secrets/app metadata) —
  recommended; (b) open sign-up, everybody becomes a plain user — not acceptable even on test,
  because deploy stores HTML served to others and later data is company data.
- When MongoDB arrives this can move into an app-side allowlist like my-intelligence if still wanted;
  on-prem, AD itself is the allowlist.

## 3.3. Roles source and claim
- Recommended: **Auth0 RBAC roles** (`shitcloud-builders`, `shitcloud-admins`) assigned to users in
  the dashboard; the post-login Action copies `event.authorization.roles` into a namespaced claim
  `https://shitcloud-api/groups` on the **access token**.
- API properties: `shitcloud.auth.groups-claim` (default the namespaced claim; on ADFS/Entra it will
  be `groups`), `shitcloud.auth.builders-group` (`BUILDERS_GROUP` in handoff §8), `shitcloud.auth.admins-group`.
- Admin implies builder (handoff table: admin is the platform owner).

## 3.4. User id
| Option | + | − |
|---|---|---|
| **Email** (recommended) | Human-readable in `owner` / `updatedBy` / history without a user table; survives the IdP switch to AD (same corporate email, different `sub`) — the on-prem migration keeps every record's author meaningful | Needs the Action to put email in the access token; an email change orphans authorship (rare in a company) |
| `sub` | Always present, stable within one IdP | `google-oauth2|1093…` is unreadable; changes completely when moving Auth0 → AD, so every `updatedBy` in Mongo would point at nobody |

Display name: `name` claim (also via the Action), falling back to email.

## 3.5. Local development
- Recommended: **real Auth0 locally** (localhost:3000 registered as callback/origin) — same as
  my-intelligence; local behaves like production and CORS + bearer are exercised for real.
- Integration tests use `spring-security-test` (`jwt()` post-processor with claims), no network.
- A mock-login profile is possible later if Auth0 becomes a nuisance; not part of this ticket.

# 4. Acceptance criteria
1. Opening the Railway GUI signed out leads to the Auth0 login; after login the app list loads.
2. Any `/api/**` request without a token → 401; with a token for another audience → 401.
3. `GET /api/me` returns the caller's email as `id`, their name, their groups, `builder`, `admin`.
4. A user without the builder group: no "Build"/"Upload" nav, and `POST /api/apps` / `POST /api/chat`
   → 403 even when called directly.
5. A deployed app's `owner` is the deployer's id.
6. `/actuator/health` stays 200 without a token (Railway healthcheck keeps working).
7. Session survives a page reload and a browser restart without a new login prompt (refresh token).
8. Log out returns to the signed-out screen.

# 5. Test scenarios (for Phase 4)
- `SecurityIT`: no token → 401 on each endpoint; health → 200 without token.
- `MeControllerIT`: claims → `/api/me` body (builder, admin implies builder, missing name → email).
- `AppControllerIT`: plain user deploy → 403; builder deploy → 201 with owner = email.
- `ChatControllerIT`: plain user → 403; builder → 200.
- `ControllerAuthorizationIT` (my-intelligence pattern): every `@RestController` declares a
  `@PreAuthorize` — fails the build when a new controller forgets one.

# 6. Risks
| Risk | Mitigation |
|---|---|
| Action writes claims only to the ID token → API sees no email, every user looks anonymous | Analysis calls it out; design gives exact Action code; converter logs custom claims |
| Refresh token in `localStorage` readable by any script on the platform origin | Rotation + reuse detection on in Auth0 (mandatory setting); user apps never on the platform origin — documented next to `AppFrame` and in AGENTS non-negotiables |
| Callback URL mismatch after a Railway domain change | URLs listed in one place in the design; `.env.production` + Auth0 settings checklist |
| Tenant-wide Action changes break login | Separate tenant (3.1) |

# 7. Out of scope
- User database / allowlist in Mongo (SHC-0002 or later if needed)
- Admin-only endpoints and admin UI (Phase 4)
- Per-app identity inside user apps — `shitcloud.me()` is SHC-0004
- On-prem ADFS / Entra configuration (Phase 5; only the configurability is in scope)
