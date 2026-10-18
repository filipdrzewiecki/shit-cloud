# Authentication & authorization — reference

How sign-in works and why. The code is the source of truth for details; this file keeps what the code
cannot tell you: the decisions, the manual Auth0 setup, and the traps already hit.

## Model — two tiers

| Tier | Who | How | Identity | Authorities |
|---|---|---|---|---|
| Builder / admin | people who create apps | Auth0 (SPA + PKCE), API is a JWT resource server | `id` = email, `displayName` = name (fallback: email) | `ROLE_BUILDER`; admin ⇒ also `ROLE_ADMIN` + `ROLE_BUILDER` |
| Guest | everyone else | shared password `GUEST_PASSWORD` → `POST /api/auth/guest {password, name}` → API-issued token, 1 day | `id` = `guest:<name>` (self-declared, 1–40 chars, trimmed), `guest = true` | none |

- One request path: `JwtIssuerAuthenticationManagerResolver` picks the validator by `iss` — Auth0
  (RS256, JWKS, issuer + audience) or `shitcloud-guest` (HS256, own secret). Unknown issuer → 401.
- Every controller declares `@PreAuthorize`; `ControllerAuthorizationIT` fails the build otherwise.
- `/actuator/health/**`, `POST /api/auth/guest` and `/error` are the only public paths. `/error` must stay
  permitted, or Spring Security rewrites a 404/429 from the guest endpoint into a 401.
- Caller in code: `AuthenticationUtils.currentUser()` → `CurrentUser(id, displayName, groups, builder, admin, guest)`.

## Guest token rules

- `pwv` claim = first 16 hex of HMAC-SHA256(`GUEST_TOKEN_SECRET`, password), re-checked on every request:
  **changing `GUEST_PASSWORD` signs every guest out at once**. No blocklist, no database.
- `GUEST_TOKEN_SECRET` ≥ 32 bytes, separate from the password (startup fails otherwise while a password is set).
- No `GUEST_PASSWORD` → guest endpoint 404 and guest tokens rejected (fail closed).
- Password compared in constant time. 10 failures per IP in 15 min → 429 (in-memory, per instance —
  move to Mongo if the API is ever scaled out).
- Client IP = the **last** `X-Forwarded-For` entry (the one Railway's proxy appends). Spring's
  forwarded-header handling takes the first, client-controlled entry — don't switch to
  `server.forward-headers-strategy=framework`, it would let an attacker reset the limiter.

## Auth0 setup (manual, in the dashboard — already done for `test`)

- **Tenant:** shared with my-intelligence, `my-intelligence.eu.auth0.com`. Products are told apart by audience.
- **API** `ShitCloud API`, identifier `https://shit-cloud-api`, RS256, offline access on, token expiry 3600 s
  (a revoked role stops working within an hour).
- **SPA application** `ShitCloud GUI`:
  - callback + web origins: `http://localhost:3000`, `https://frontend-test-7caa.up.railway.app`
  - logout URLs: the same with `/sign-in`
  - refresh token rotation on, reuse interval 0, absolute 30 d, inactivity 7 d
  - client id → `VITE_AUTH0_CLIENT_ID` (Railway `frontend` build arg; locally `frontend/.env.local`)
- **Connections:** the database connection is shared — never change its *Disable Sign Ups*. Admission is
  done by the Action, not by sign-up settings.
- **Roles:** `ShitCloud Builder`, `ShitCloud Admin`, assigned per user.
- **Action** `shitcloud-claims` (Login / Post Login). It must be **deployed AND added to the Post Login
  trigger**. Deployed-but-not-in-the-flow was the cause of the first "builder rejected" bug.

```js
const API = 'https://shit-cloud-api';
const NS = API + '/';
const ROLES = ['ShitCloud Builder', 'ShitCloud Admin'];

exports.onExecutePostLogin = async (event, api) => {
  // Tenant is shared with my-intelligence: touch only tokens requested for the ShitCloud API.
  if (event.resource_server?.identifier !== API) return;
  const roles = (event.authorization?.roles ?? []).filter((r) => ROLES.includes(r));
  if (roles.length === 0) {
    api.access.deny('This sign-in is for ShitCloud builders. Use the shared password instead.');
    return;
  }
  // Claims go on the ACCESS token — the API never sees the ID token.
  api.accessToken.setCustomClaim(NS + 'email', event.user.email);
  api.accessToken.setCustomClaim(NS + 'name', event.user.name ?? event.user.email);
  api.accessToken.setCustomClaim(NS + 'groups', roles);
};
```

- **Railway `api` variables:** `GUEST_PASSWORD`, `GUEST_TOKEN_SECRET` (e.g. `openssl rand -base64 48`).
  Claim names, issuer, audience and group names have defaults in `application.properties` and env overrides
  (`AUTH_*`, `BUILDERS_GROUP`, `ADMINS_GROUP`) — the on-prem switch to ADFS/Entra is configuration, not code.

## Debugging a rejected builder

- `LoggingBearerTokenEntryPoint` logs the rejection reason at WARN (never the token) — check the Railway
  `api` logs first. `Token carries no https://shit-cloud-api/email claim` = the Action did not run.
- A token without the id claim → `InvalidBearerTokenException` (401), never an anonymous caller.
- GUI shows Auth0 callback errors (e.g. the Action's deny message) and `?reason=builder-rejected`.

## Frontend

- `Auth0Provider` with refresh tokens in `localStorage`, no iframe fallback (third-party cookies are blocked).
- Guest token + expiry in `localStorage` (`auth/guestSession.ts`).
- `api/client.ts` is the only place a token is attached; the token provider is set **during render**, not in
  an effect (child query effects run first and would go out without a token).
- 401 → clear both sessions once, wait for the local Auth0 `logout`, then `/sign-in?returnTo=…`.
  `returnTo` accepts same-app paths only (no open redirect).
- Credentials in `localStorage` are safe only because user apps never run on the platform origin — see
  the non-negotiables in `AGENTS.md`.

## Accepted trade-offs

- Guest authorship is a label, not proof. Builder actions are real identities.
- Roles live in the token, no DB lookup: revocation takes up to 1 h.
- An email change orphans a builder's authorship.

## Testing notes

- `maven-failsafe-plugin`: `*IT` run in `mvn verify` only; `mvn test` is unit-only.
- `IntegrationTest` base: guests use real guest tokens; identity-provider callers go through the production
  converter. Auth0 signature checking is the one step only verified live.
- `NimbusJwtEncoder` refuses `exp` before `iat`: expiry tests inject a `Clock` into `GuestTokenService`
  (package-private constructor) instead of a negative TTL.
