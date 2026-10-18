# Project log

What each closed ticket delivered, newest first. Durable details live in the files each entry points to.

## SHC-0001 — Auth0 login + roles (closed 2026-10-18)

- Two-tier sign-in: builders/admins through Auth0 (shared my-intelligence tenant, roles `ShitCloud Builder` /
  `ShitCloud Admin`), everyone else with a shared password and a 1-day API-issued guest token.
- API is a JWT resource server accepting both issuers; every controller declares `@PreAuthorize`; deploy and
  chat are builder-only; `/api/me` and app `owner` come from the token.
- Guest tier built for long-term use: constant-time check, per-IP attempt limiter (429), password rotation
  signs all guests out, fails closed when unset.
- GUI: `/sign-in` front door, builders-only screens, sign-out, readable error for rejected builder sign-ins.
- Deployed to `test` and verified for both tiers; unit + integration tests green (`mvn verify`).
- Reference: `.claude/docs/auth.md`.
