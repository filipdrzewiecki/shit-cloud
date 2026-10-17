# Solution Design — SHC-0001: Auth0 login + roles

Input: `analysis.md`. Reference implementation: my-intelligence `security/*`, `frontend/src/main.tsx`,
`frontend/src/App.tsx`, `frontend/src/api/client.ts`.

**This design changes the analysis in one important way:** sign-in has **two tiers** (user decision
below). §6 restates the acceptance criteria and replaces analysis §4.

---

# 1. User decisions (Phase 2)

| # | Question | Decision |
|---|---|---|
| U1 | Auth0 tenant | **The existing my-intelligence tenant** (`my-intelligence.eu.auth0.com`): a new tenant was not possible. Both products share users, connections and the post-login flow, so the ShitCloud Action must act only on ShitCloud logins (§3 step 6) and shared settings stay untouched (step 4) |
| U2 | Who may log in | **Two tiers.** *Builders/admins* sign in through Auth0 (full identity) and can create views, use the chat and upload HTML. *Everyone else* signs in with a **shared password** (an env variable) and can only use the apps builders made |
| U3 | Author id | Auth0 users: **email**. Password users: **the name they type** at sign-in (self-declared) |
| U4 | Roles among Auth0 users | **Keep builder + admin** (admin ⇒ builder) |
| U5 | Password session length | **1 day** |
| U6 | Lifetime of the password tier | **Long-term**, so it is built properly: attempt limiting, password rotation, configurable |
| U7 | Local development | **Real Auth0 on localhost**; the password tier works locally from a dev default |

# 2. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | The API accepts **two token issuers**: Auth0 (RS256, checked against the JWKS) and the API itself for guests (HS256, `iss=shitcloud-guest`). Spring's `JwtIssuerAuthenticationManagerResolver` picks the validator from the token's `iss`; an unknown issuer → 401 | One request path, one `Authorization: Bearer` header, the same `@PreAuthorize` rules for both tiers |
| D2 | Guest sign-in = `POST /api/auth/guest {password, name}` → `{token, expiresAt}`. The only `/api/**` endpoint open without a token | The SPA holds no secret; the password is checked server-side only |
| D3 | Password compared in constant time (`MessageDigest.isEqual` on SHA-256 digests). Wrong password → 401; **10 failures per client IP in 15 min → 429** (in-memory limiter, client IP from `X-Forwarded-For` via `server.forward-headers-strategy=framework`) | A shared password is guessable by brute force; long-term use (U6) makes the limiter necessary |
| D4 | The guest token carries `pwv` = first 16 hex chars of HMAC-SHA256(signing secret, password). On every request the API recomputes it from the current password; a mismatch → 401. **Changing `GUEST_PASSWORD` signs out every guest at once** | Rotation without a token blocklist or a database |
| D5 | Signing secret `GUEST_TOKEN_SECRET` (≥ 32 bytes), separate from the password | Deriving the key from a human password would make the password crackable offline from any token |
| D6 | No `GUEST_PASSWORD` set → the guest endpoint returns 404 and guest tokens are rejected | Fail closed: a forgotten variable must never mean "empty password works" |
| D7 | Guest identity: `id = "guest:" + name`, `displayName = name`, no groups, `guest = true`. Name trimmed, 1–40 characters | The `guest:` prefix keeps a typed name from impersonating an email id, and history shows who was a guest |
| D8 | Auth0 admission: the post-login Action **denies anyone without `ShitCloud Builder` or `ShitCloud Admin`** for tokens requested for the ShitCloud API; you assign the roles. Sign-up on the shared connection is left as my-intelligence needs it | "Admin requires full authentication" (U2): an Auth0 account with no role has no business here, so it gets no token |
| D9 | Authorities: `ROLE_BUILDER`, `ROLE_ADMIN` (Auth0 tier), none for guests. Every controller declares `@PreAuthorize` | my-intelligence convention, enforced later by `ControllerAuthorizationIT` |
| D10 | `CorsConfig` deleted; CORS moves into `SecurityConfig` as a `CorsConfigurationSource` | The CORS preflight must be answered by the security filter chain, which runs before authentication |
| D11 | Both tokens kept in `localStorage`: Auth0 refresh token (rotation + reuse detection on, iframe fallback off), guest token with its expiry | Sessions survive reloads. This is safe only because user apps never run on the platform origin, so the reason is written next to `AppFrame` and in AGENTS non-negotiables |
| D12 | The GUI front door is **`/sign-in`**: a password + name form, plus "Builder sign-in" → Auth0. Every other route redirects there when signed out, carrying `returnTo` | Most people are guests; sending them to Auth0 first would be wrong |
| D13 | Seed apps keep their mock owners | They disappear with the in-memory store in SHC-0002 |

---

# 3. Auth0 setup (manual, done by the user in the dashboard)

Values marked ⟶ are needed in Tasks 2 and 7.

1. **Tenant:** the existing my-intelligence tenant ⟶ domain `my-intelligence.eu.auth0.com`. Both products'
   tokens have the same issuer; the API tells them apart by audience (`https://shit-cloud-api`).
2. **API** (Applications → APIs → Create): name `ShitCloud API`, Identifier ⟶ **`https://shit-cloud-api`**,
   RS256. Settings: *Allow Offline Access* **on**; Token Expiration **3600** s (a revoked role stops
   working within an hour).
3. **Application** (Applications → Create → *Single Page Application*), name `ShitCloud GUI`:
   - Allowed Callback URLs: `http://localhost:3000, https://frontend-test-7caa.up.railway.app`
   - Allowed Logout URLs: `http://localhost:3000/sign-in, https://frontend-test-7caa.up.railway.app/sign-in`
   - Allowed Web Origins: `http://localhost:3000, https://frontend-test-7caa.up.railway.app`
   - Refresh Token Rotation **on**, reuse interval 0; absolute expiration 30 days, inactivity 7 days.
   - ⟶ the **Client ID** is set as `VITE_AUTH0_CLIENT_ID` on the Railway `frontend` service (passed into the
     Docker build as a build argument). The client secret is not used by the SPA; it is kept only as
     `AUTH0_CLIENT_SECRET` on the Railway `api` service.
4. **Connections:** the database connection is shared with my-intelligence, so its *Disable Sign Ups*
   setting is **not** changed for ShitCloud. On the `ShitCloud GUI` application's *Connections* tab, enable
   only the connections builders should use. Admission does not depend on sign-up being off: anyone
   who signs up without a ShitCloud role is denied by the Action.
5. **Roles** (User Management → Roles): `ShitCloud Builder`, `ShitCloud Admin`. Assign `ShitCloud Admin` to your user.
6. **Action** (Actions → Library → Build custom → *Login / Post Login*), name `shitcloud-claims`,
   deploy, then add it to the Post Login flow next to the existing my-intelligence Action. Actions run for
   **every** login in the tenant, so the first line makes it a no-op for anything but ShitCloud:

```js
/**
 * Admission and identity for ShitCloud builders and admins. Everyone else uses the shared
 * password and never reaches Auth0.
 *
 * The tenant is shared with my-intelligence and post-login Actions run for every login in it.
 * Only a token requested for the ShitCloud API is touched; my-intelligence logins pass through
 * unchanged, so its users are never denied here for lacking a ShitCloud role.
 *
 * Claims go on the ACCESS token — the API never sees the ID token. Custom claim names must be
 * namespaced URIs; nothing is fetched from them.
 */
const API = 'https://shit-cloud-api';
const NS = API + '/';
// Role names exactly as in User Management → Roles. The API maps them via BUILDERS_GROUP / ADMINS_GROUP.
const ROLES = ['ShitCloud Builder', 'ShitCloud Admin'];

exports.onExecutePostLogin = async (event, api) => {
  if (event.resource_server?.identifier !== API) return;

  const roles = (event.authorization?.roles ?? []).filter((r) => ROLES.includes(r));

  if (roles.length === 0) {
    api.access.deny('This sign-in is for ShitCloud builders. Use the shared password instead.');
    return;
  }

  api.accessToken.setCustomClaim(NS + 'email', event.user.email);
  api.accessToken.setCustomClaim(NS + 'name', event.user.name ?? event.user.email);
  api.accessToken.setCustomClaim(NS + 'groups', roles);
};
```

7. **Railway, `api` service variables:** `GUEST_PASSWORD` (the shared password) and
   `GUEST_TOKEN_SECRET` (random, e.g. `openssl rand -base64 48`). You set these; the agent does not.

---

# 4. Backend

## 4.1 Dependencies (`pom.xml`)
`spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` (includes Nimbus JOSE
for both validating and signing); test: `spring-security-test`.

## 4.2 Properties (`application.properties`)

```properties
# Railway sits behind a proxy; the client IP for attempt limiting comes from X-Forwarded-For.
server.forward-headers-strategy=framework

#====== AUTH — builders/admins through the identity provider (Auth0 now, ADFS/Entra on-prem)
auth.issuer-uri=${AUTH_ISSUER_URI:https://my-intelligence.eu.auth0.com/}
auth.audience=${AUTH_AUDIENCE:https://shit-cloud-api}
auth.jwks-uri=${AUTH_JWKS_URI:https://my-intelligence.eu.auth0.com/.well-known/jwks.json}
auth.id-claim=${AUTH_ID_CLAIM:https://shit-cloud-api/email}
auth.name-claim=${AUTH_NAME_CLAIM:https://shit-cloud-api/name}
auth.groups-claim=${AUTH_GROUPS_CLAIM:https://shit-cloud-api/groups}
auth.builders-group=${BUILDERS_GROUP:ShitCloud Builder}
auth.admins-group=${ADMINS_GROUP:ShitCloud Admin}

#====== AUTH — everyone else through the shared password. Unset password = guest sign-in disabled.
auth.guest.password=${GUEST_PASSWORD:}
auth.guest.token-secret=${GUEST_TOKEN_SECRET:}
auth.guest.token-ttl=1d
auth.guest.max-failures=10
auth.guest.failure-window=15m
```

`application-dev.properties` (local only) sets a dev password and a dev secret, so guest sign-in works
out of the box locally. `npm run dev` activates the `dev` profile.

## 4.3 Package `com.drzewiecki.shitcloud.security`

| Class | Responsibility |
|---|---|
| `AuthProperties` | `@ConfigurationProperties("auth")` record with a nested `Guest` record; `@ConfigurationPropertiesScan` on `Application` |
| `CurrentUser` | record `(String id, String displayName, List<String> groups, boolean builder, boolean admin, boolean guest)`, the platform's view of the caller, independent of the IdP |
| `UserAuthentication` | `AbstractAuthenticationToken` carrying `Jwt` + `CurrentUser`; principal = `id` |
| `IdpUserConverter` | Auth0 `Jwt` → `UserAuthentication`. Claim names from properties; admin ⇒ builder; missing name ⇒ id. **Missing id claim → `BadJwtException` (401)**, because a broken Action must not produce anonymous writes |
| `GuestTokenService` | `issue(name)` → HS256 JWT (`iss=shitcloud-guest`, `aud=shit-cloud-api`, `sub=guest:<name>`, `name`, `pwv`, `exp=now+ttl`) via `NimbusJwtEncoder`; `decoder()` → `NimbusJwtDecoder` with issuer, audience and `pwv` validators; `passwordMatches(candidate)` constant-time |
| `GuestUserConverter` | guest `Jwt` → `UserAuthentication` (no authorities, `guest=true`) |
| `LoginAttemptLimiter` | Failures per IP within the window (`ConcurrentHashMap<String, Deque<Instant>>`, pruned on access); `blocked(ip)`, `recordFailure(ip)`, `reset(ip)` |
| `SecurityConfig` | Stateless, CSRF off, `cors(withDefaults())`; permitAll: `/actuator/health/**`, `POST /api/auth/guest`; rest authenticated. `oauth2ResourceServer(o -> o.authenticationManagerResolver(resolver))` where the resolver is `JwtIssuerAuthenticationManagerResolver` over `{auth0 issuer → JwtAuthenticationProvider(auth0 decoder, IdpUserConverter), shitcloud-guest → JwtAuthenticationProvider(guest decoder, GuestUserConverter)}` (guest entry only when the password is set). Beans: Auth0 `JwtDecoder` (JWKS, issuer + audience), `CorsConfigurationSource` (`/api/**`, origins from `cors.allowed-origins`, GET/POST/PUT/DELETE, `Authorization` + `Content-Type`, maxAge 3600) |
| `MethodSecurityConfig` | `@EnableMethodSecurity` |
| `AuthenticationUtils` | `static CurrentUser currentUser()` |

## 4.4 Controllers and model

| Controller | Rule | Change |
|---|---|---|
| `GuestLoginController` (new, `POST /api/auth/guest`) | `@PreAuthorize("permitAll()")` | 404 if disabled; 429 if the IP is blocked; 400 on an invalid name; 401 + `recordFailure` on a wrong password; success → `reset`, 200 `GuestToken(token, expiresAt)` |
| `MeController` | class `isAuthenticated()` | `Me` from `currentUser()`; `MOCK_USER` removed |
| `AppController` | class `isAuthenticated()`; `deploy` `hasRole('BUILDER')` | owner = `currentUser().id()` |
| `ChatController` | class `hasRole('BUILDER')` | none |

Model: `Me` gains `boolean guest`; new records `GuestLoginRequest(password, name)`,
`GuestToken(token, expiresAt)`. `config/CorsConfig.java` deleted.

## 4.5 Error responses
Spring Security defaults: 401 + `WWW-Authenticate: Bearer` for a missing or invalid token, 403 for a
failed `@PreAuthorize`. CORS headers are present on both, so the GUI can read the status.

---

# 5. Frontend

## 5.1 Configuration
- Dependency `@auth0/auth0-react`.
- `.env.example` / `.env.local` / `.env.production` (public values, committed for production):
  `VITE_AUTH0_DOMAIN=my-intelligence.eu.auth0.com`, `VITE_AUTH0_CLIENT_ID=<step 3>`,
  `VITE_AUTH0_AUDIENCE=https://shit-cloud-api`. Typed in `src/vite-env.d.ts`.

## 5.2 `main.tsx`
`BrowserRouter` → `Auth0ProviderWithNavigate` → `QueryClientProvider` → `App`, the my-intelligence
shape: `useRefreshTokens`, `useRefreshTokensFallback={false}`, `cacheLocation="localstorage"`, scope
`openid profile email offline_access`, `onRedirectCallback` → `navigate(returnTo)`. The comment
explains D11.

## 5.3 `auth/guestSession.ts` (new)
Reads, writes and clears `{token, expiresAt}` in `localStorage`; `current()` returns null once expired.
`signInAsGuest(password, name)` calls `POST /api/auth/guest` through `api/client.ts`.

## 5.4 `api/client.ts`
- `setTokenProvider(provider)`. `App` supplies one that returns the Auth0 token when signed in through
  Auth0, otherwise the guest token, otherwise throws.
- The provider throwing, or the API answering 401, → `UnauthenticatedError` (an `ApiError` with
  status 401) → registered handler clears the guest session and navigates to
  `/sign-in?returnTo=…`.
- Requests can opt out of the token (`{ anonymous: true }`) for the guest sign-in call.

## 5.5 `App.tsx` and `pages/SignInPage.tsx`
- `/sign-in` (public): a form for the shared password and "Your name", which submits →
  `signInAsGuest` → `returnTo`. Wrong password, rate limit and disabled guest sign-in each get
  their own message. Below the form: "Builder? Sign in with Auth0" → `loginWithRedirect({ appState: { returnTo } })`.
- Other routes: wait for Auth0 `isLoading`; not signed in either way → `<Navigate to="/sign-in?returnTo=…">`.
- `/build`, `/upload` show "Builders only" to non-builders (nav already hides them; the API enforces it).

## 5.6 `shell/Layout.tsx`
User chip shows the name and a `guest` or `builder` badge, plus *Sign out*. A guest's session is
cleared and they go to `/sign-in`; an Auth0 user goes through
`logout({ logoutParams: { returnTo: origin + '/sign-in' } })`. The query cache is cleared either way.

## 5.7 `shell/AppFrame.tsx`
Comment extended: the platform origin holds credentials in `localStorage` (refresh token, guest
token), so `allow-same-origin` on a `srcdoc` frame would hand any app those credentials.

---

# 6. Acceptance criteria (replace analysis §4)

1. Opening the GUI signed out shows `/sign-in` with the password form and the builder sign-in link.
2. The correct password + a name → app list. `/api/me` → `{id: "guest:<name>", guest: true, builder: false}`.
   No Build/Upload in the nav.
3. Wrong password → error message. The 11th failure within 15 min from one IP → 429 "too many attempts".
4. A guest calling `POST /api/apps` or `POST /api/chat` directly → 403.
5. Builder sign-in via Auth0 → builder screens; a deployed app's `owner` is the builder's email.
6. An Auth0 account with no ShitCloud role is refused at Auth0 and gets no token.
7. A guest session ends after 1 day; changing `GUEST_PASSWORD` on Railway signs every guest out.
8. Any `/api/**` call without a valid token (except guest sign-in) → 401. `/actuator/health` → 200.
9. Both sessions survive a reload and a browser restart (within their lifetime). Sign out returns to
   `/sign-in`.

---

# 7. Implementation tasks (Phase 3)

Tasks 2–9 go to `main` together. Pushing the backend alone would make the GUI on test answer 401
everywhere. Nothing is pushed per task.

- Task 1 — **Auth0 setup + Railway variables (user, manual)**: §3 steps 1–7; report domain + client id
- Task 2 — `pom.xml` security deps; `auth.*` properties; `application-dev.properties` + root
  `npm run dev` runs Maven with `-Dspring-boot.run.profiles=dev`; `AuthProperties`
- Task 3 — `CurrentUser`, `UserAuthentication`, `IdpUserConverter`, `AuthenticationUtils`
- Task 4 — `GuestTokenService`, `GuestUserConverter`, `LoginAttemptLimiter`
- Task 5 — `SecurityConfig` (issuer resolver, CORS), `MethodSecurityConfig`; delete `CorsConfig`
- Task 6 — `GuestLoginController` + `GuestLoginRequest` / `GuestToken`
- Task 7 — Existing controllers: `@PreAuthorize`, `/api/me` from token (+ `guest`), owner from token
- Task 8 — Frontend: `@auth0/auth0-react`, env files, `main.tsx` provider, `guestSession.ts`, `client.ts` tokens
- Task 9 — `SignInPage`, `App.tsx` gate, builders-only guard, `Layout` sign-out, `AppFrame` comment, AGENTS.md
- Task 10 — Deploy to `test` (user pushes) + walk acceptance criteria 1–9

Phase 4 tests: `IntegrationTest` base; `SecurityIT` (401s, health, unknown issuer); `GuestLoginIT`
(success, wrong password, 429, disabled → 404, password rotation invalidates token, expiry);
`MeControllerIT`; `AppControllerIT` (guest 403, builder 201 owner = email); `ChatControllerIT`;
`ControllerAuthorizationIT`; `IdpUserConverterTest`; `LoginAttemptLimiterTest`.

---

# 8. Trade-offs

| Choice | Cost accepted |
|---|---|
| Shared password for most users | No real identity for guests: a typed name can be anyone's, so guest authorship is a label, not proof. Builder actions are still real Auth0 identities |
| In-memory attempt limiter | Resets on restart and is per instance. Fine for one Railway instance; move it to Mongo if the API is ever scaled out |
| Roles in the Auth0 token, no DB lookup | A revoked builder keeps access until their access token expires (≤ 1 h with step 2) |
| Email as builder id | An email change orphans authorship |
| Credentials in `localStorage` | Readable by any script on the platform origin. Bounded by refresh-token rotation, the 1-day guest TTL, and never running user apps on that origin |
| Shared tenant with my-intelligence | One Action mistake can affect the other product's logins; the ShitCloud Action returns early for every token not requested for `https://shit-cloud-api`. A user who exists in both products is one Auth0 account |
| Real Auth0 locally | Builder sign-in locally needs network; guest sign-in works offline with the dev password |
