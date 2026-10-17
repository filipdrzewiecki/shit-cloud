---
jira: SHC-0001
title: "Auth0 login + roles"
endpoint: "/api/me, /api/apps, /api/chat"
controller: MeController.class
key-classes: [SecurityConfig.class, MeController.class, AppController.class, ChatController.class]
---

# Description
Put the platform behind a login. Same approach as my-intelligence: Auth0, the GUI as a Single Page
Application client (Authorization Code + PKCE), the API as a JWT resource server. Replace the mock
user with the authenticated one and enforce the three roles from the handoff (user / builder / admin).

# Open questions
- Reuse the my-intelligence Auth0 tenant or create a new one?
- Who is allowed to log in on `test` at all?
- Where do roles come from in Auth0, and which claim carries them?
- Which claim is the user id stored in `updatedBy` / `owner`?
- Local development: real Auth0 on localhost, or a mock-user profile?

# Key rules
- Every `/api/**` call requires a valid bearer token; `/actuator/health` stays open
- Builder-only: deploy (`POST /api/apps`) and chat (`POST /api/chat`)
- Roles come from a token claim (handoff §2: builder role from the `groups` claim) so the on-prem
  switch to AD is configuration, not code
- No client secret in the SPA; nothing secret committed

# Key context
- my-intelligence: `SecurityConfig`, `Auth0JwtAuthenticationConverter`, `frontend/src/main.tsx`
  (`Auth0Provider`), `.claude/docs/auth0-oauth2-guide.md`
- No database yet (MongoDB is SHC-0002), so no allowlist table like my-intelligence's `app_user`
- Railway domains: api `api-test-27f4.up.railway.app`, GUI `frontend-test-7caa.up.railway.app`

# Key flows
1. Signed-out visitor opens the GUI → redirected to Auth0 → back with a session
2. GUI calls the API with `Authorization: Bearer <access token>`
3. API validates signature / issuer / audience, maps the roles claim to authorities
4. `/api/me` returns who the caller is; the GUI hides builder screens from non-builders
5. A non-builder calling `POST /api/apps` gets 403

# Optional items
- [ ] wikipedia
- [x] Acceptance criteria
- [x] Tasks/Subtasks
- [x] Test scenarios
- [~] Extend/Modify MCP (no MCP in this project)
- [x] Implement frontend
