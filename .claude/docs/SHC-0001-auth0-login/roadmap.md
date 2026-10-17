# SHC-0001 — Auth0 login + roles — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md` (acceptance criteria superseded by solution-design §6)
- [x] Phase 2 — Solution Design → `solution-design.md` (two-tier sign-in: Auth0 builders + shared-password guests)
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 2 — Security deps, `auth.*` properties, dev profile, `AuthProperties`
    - [x] Task 3 — `CurrentUser`, `UserAuthentication`, `IdpUserConverter`, `AuthenticationUtils`
    - [x] Task 4 — `GuestTokenService`, `GuestUserConverter`, `LoginAttemptLimiter`
    - [x] Task 5 — `SecurityConfig` (issuer resolver, CORS), `MethodSecurityConfig`; delete `CorsConfig`
    - [x] Task 6 — `GuestLoginController` + request/response records
    - [x] Task 7 — Existing controllers: `@PreAuthorize`, `/api/me` + owner from token
    - [x] Task 8 — Frontend: Auth0 provider, env, `guestSession.ts`, `client.ts` tokens
    - [x] Task 9 — `SignInPage`, gate, builders-only guard, sign-out, `AppFrame` comment, AGENTS.md
    - [x] Task 1 — Auth0 setup (shared my-intelligence tenant, roles `ShitCloud Builder` / `ShitCloud Admin`) + Railway variables
    - [~] Task 10 — Deployed `a4681da` to `test`; guest flow verified on test. Builder sign-in through Auth0 still to be checked by the user
- [ ] Phase 4 — Tests
- [ ] Phase 5 — Wiki docs (optional — ask)
- [ ] Phase 6 — Frontend implementation instruction (optional — ask; frontend is in this repo, likely skipped)
