# Implementation — SHC-0001: Auth0 login + roles

The user asked for the code first, with the Auth0 setup and the Railway variables done together with the
deploy at the end of the run. Tasks 2–9 were therefore done in one pass. Task 1 (Auth0 + Railway) moved
to the end, next to Task 10.

## Task 2 — Dependencies, properties, AuthProperties
- `pom.xml`: `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, test `spring-security-test`.
- `application.properties`: `auth.*` block (identity provider + guest). New `application-dev.properties`
  (guest password `dev`, throwaway secret). Root `package.json` `dev` script runs Maven with the `dev` profile.
- `security/AuthProperties` (+ nested `Guest`, `enabled()`); `@ConfigurationPropertiesScan` on `Application`.
- **Deviation:** `server.forward-headers-strategy=framework` was **not** added (see Task 6).

## Task 3 — Caller model + identity-provider converter
- `security/CurrentUser`, `UserAuthentication`, `IdpUserConverter`, `Roles`, `AuthenticationUtils`.
- **Deviation:** a token without the id claim throws `InvalidBearerTokenException`, not `BadJwtException`.
  The converter runs outside the decoder's exception mapping, so only an `AuthenticationException` becomes a 401.

## Task 4 — Guest tokens + limiter
- `security/GuestTokenService` (HS256 via `NimbusJwtEncoder`, decoder with issuer/audience/`pwv` validators,
  constant-time password check, startup failure if the secret is < 32 bytes while a password is set).
- `security/GuestUserConverter`, `security/LoginAttemptLimiter` (sliding window per IP, empty entries removed).
- `model/GuestToken`, `model/GuestLoginRequest`.

## Task 5 — SecurityConfig
- `security/SecurityConfig`: stateless, CORS bean, `JwtIssuerAuthenticationManagerResolver` over
  {Auth0 issuer, `shitcloud-guest`}; the guest entry is added only when enabled. `security/MethodSecurityConfig`.
- `/error` is permitted. Without that, Spring Security rewrites a 429/404 from the public endpoint into a 401
  on the error dispatch.
- `config/CorsConfig.java` deleted.

## Task 6 — Guest sign-in endpoint
- `service/GuestLoginService` (404 disabled → 429 blocked → 400 name → 401 password → token),
  `controller/GuestLoginController` (`@PreAuthorize("permitAll()")`).
- **Deviation:** the client IP is the **last** `X-Forwarded-For` entry (the one Railway's proxy appends) instead of
  Spring's forwarded-header handling, which takes the first, client-controlled entry. That would let an
  attacker reset the limiter by sending a fake header. Verified: `X-Forwarded-For: 1.1.1.1, 9.9.9.9` is still blocked.

## Task 7 — Controller rules
- `MeController` (`isAuthenticated()`, built from the token, `MOCK_USER` removed), `AppController` (class
  `isAuthenticated()`, `deploy` `hasRole('BUILDER')`, owner = caller id), `ChatController` (`hasRole('BUILDER')`).
- `model/Me` gains `guest`.

## Task 8 — Frontend plumbing
- `@auth0/auth0-react` 2.x; env files gain `VITE_AUTH0_DOMAIN/CLIENT_ID/AUDIENCE` (client id **empty** until
  Task 1); `vite-env.d.ts`.
- `main.tsx`: `Auth0ProviderWithNavigate` (refresh tokens, localStorage, no iframe fallback).
- `auth/guestSession.ts` (localStorage + `useSyncExternalStore`), `api/client.ts` (Auth0 token else guest token,
  `UnauthenticatedError`, handler, `anonymous` option), `api/queries.ts` `useGuestSignIn`, `api/types.ts`.
- **Deviation:** the token provider is set during render, not in an effect. Child query effects run before the
  parent's effect, so the first request would otherwise go out without a token.

## Task 9 — Sign-in page, gate, docs
- `pages/SignInPage` (name + team password, per-status error messages; "Builder sign-in" is disabled while no
  client id is configured), `auth/returnTo.ts` (same-app paths only, so `returnTo` can't be used as an open redirect).
- `App.tsx`: `/sign-in` public, `RequireSignIn` gate, `BuildersOnly` on `/build` and `/upload`, unauthenticated
  handler (clears both sessions, ignores repeats while already on `/sign-in`).
- `Layout`: guest/builder/admin badge, Sign out (guest: full load to `/sign-in`; Auth0: `logout` → `/sign-in`).
- `AppFrame` comment: credentials on the platform origin. `styles/app.css`: sign-in styles.
- `.claude/AGENTS.md`: auth stack, dev profile note, layout, API table with access column, two non-negotiables.

### Verification done
- `mvn -q compile` and `npm run typecheck` are clean.
- API on the dev profile, checked with curl: health 200; no token 401; wrong password 401; blank name 400;
  guest `/api/me` → `guest:Anna`; apps 200; guest deploy/chat 403; tampered token 401; CORS preflight 200;
  11th attempt 429, including with a spoofed `X-Forwarded-For` prefix.
- Browser (Vite against the local API):
  - deep link redirects to `/sign-in?returnTo=…`; wrong password shows its error message
  - the correct password returns to the deep link with the guest badge and no builder nav
  - `/build` shows the builders-only message
  - the session survives a full reload; sign-out clears it and lands on `/sign-in`
  - an invalid stored token sends the reader back to sign-in once
- **Not verified:** anything that needs Auth0 (builder sign-in, roles claim, refresh, Auth0 logout). That is part of Task 1 + Task 10.

## Post-deploy fix — builder sign-in rejected without explanation
Deployed `a4681da`. Guest sign-in worked on test. A builder sign-in through Google came back from Auth0 with a
token, but the API answered 401 on `/api/me` and `/api/apps` (Railway HTTP logs, 21:23–21:24), and the GUI
retried several times before settling on the sign-in page with no message.
- `security/LoggingBearerTokenEntryPoint`: wraps Spring's bearer entry point and logs the rejection reason at
  WARN when a token was sent (never the token). This tells a missing email claim apart from a wrong audience,
  an expiry or a wrong issuer in the Railway logs.
- `App.tsx` unauthenticated handler: a guard so it runs once, and it waits for the local Auth0 `logout` before
  navigating. Before, the sign-in page still saw `isAuthenticated` and bounced the reader back into the 401.
- `SignInPage` shows Auth0 callback errors (e.g. the Action's deny message) and `reason=builder-rejected`
  (token refused by the API). `auth/returnTo.ts` `signInPath` takes the optional reason.
- Root cause on the Auth0 side: `shitcloud-claims` was deployed but not added to the post-login trigger, so only
  my-intelligence's "Add agent claim" ran and the token had no ShitCloud claims. The new log line named it at once
  (`Token carries no https://shit-cloud-api/email claim`). After the Action was added and applied, builder
  sign-in on test works, with the admin role.

## Phase 4 — Tests
`pom.xml`: `maven-failsafe-plugin`, so `*IT` classes run in `mvn verify` and `mvn test` stays unit-only.
Tests were compiled (`mvn -q test-compile`) but **not run** by the agent; the user runs them.

Added (unit, `mvn test`):
- `security/GuestTokenServiceTest`
  - `issuedToken_decodesToTheGuest`, `issuedToken_expiresAfterTheConfiguredTtl`, `passwordMatches_onlyTheConfiguredPassword`
  - `changingThePassword_invalidatesEarlierTokens`, `expiredToken_isRejected`, `tokenSignedWithAnotherSecret_isRejected`
  - `blankPassword_disablesGuestSignIn`, `passwordWithShortSecret_failsAtStartup`
- `security/LoginAttemptLimiterTest`: `blocks_onceTheLimitIsReached`, `countsEachIpSeparately`,
  `reset_clearsTheIp`, `unknownIp_isNotBlocked`
- `security/IdpUserConverterTest`
  - `builder_getsTheBuilderRoleOnly`, `admin_isAlsoABuilder`, `noKnownGroup_meansNoRole`, `missingGroupsClaim_meansNoRole`
  - `missingName_fallsBackToTheId`, `missingIdClaim_isRejected`
- helper `security/TestAuthProperties`

Added (integration, `mvn verify`):
- base `IntegrationTest`: guest callers use real guest tokens; identity-provider callers are built by the
  production converter (Auth0 signature checking is the one step not covered)
- `security/SecurityIT`
  - `withoutToken_everyApiEndpointAnswers401` (5 endpoints), `health_isOpenForTheRailwayHealthcheck`
  - `garbageToken_answers401`, `tokenFromAnUnknownIssuer_answers401`, `guestIssuerWithAForgedSignature_answers401`
  - `preflightFromTheGui_isAllowed`, `preflightFromAnotherOrigin_isRefused`
- `controller/GuestLoginIT`
  - `correctPassword_returnsATokenThatSignsTheGuestIn`, `nameIsTrimmed`, `wrongPassword_answers401`
  - `blankName_answers400`, `tooLongName_answers400`
  - `afterTooManyFailures_evenTheCorrectPasswordAnswers429`, `aForgedForwardedPrefix_doesNotEscapeTheLimit`
  - `successfulSignIn_resetsTheFailureCount`, `tokenIssuedUnderAnEarlierPassword_isRejected`
- `controller/GuestLoginDisabledIT`: `guestSignIn_answers404`
- `controller/MeControllerIT`: `builder_isDescribedFromTheToken`, `admin_isAlsoABuilder`, `guest_hasNoRole`
- `controller/AppControllerIT`
  - `guest_canListAndOpenApps`, `guest_cannotDeploy`, `identityProviderUserWithoutRole_cannotDeploy`
  - `builder_deploysAndIsRecordedAsOwner`, `admin_canDeploy`
- `controller/ChatControllerIT`: `guest_cannotUseTheChat`, `builder_getsAReply`
- `controller/ControllerAuthorizationIT`: `everyController_declaresAnAuthorizationRule`, `theSuiteSeesEveryController`

Not covered: the limiter's window expiry (would need an injectable clock) and Auth0 signature validation
(needs Auth0's keys; verified live on test instead).
- Fix after the user's run: `expiredToken_isRejected` failed because `NimbusJwtEncoder` refuses to encode a token
  whose `exp` is before `iat` (the test used a negative TTL). `GuestTokenService` gained a package-private
  constructor taking a `Clock` (production uses `Clock.systemUTC()` through the `@Autowired` constructor); the test
  issues a normal 1-day token "a day and two minutes ago" and expects the decoder to reject it.
