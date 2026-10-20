# SHC-0020 — Upgrade to Spring Boot 4

Flow: **simplified** (this one file for all phases; gates kept).

# Phase 1 — Analysis

## Metadata
| Field | Value |
|---|---|
| **Description** | Move the API from Spring Boot 3.4.4 to **4.1.1** (Framework 7.0.9, Security 7.1.1, Jackson 3.1.5, Tomcat 11, Flyway 12.4, Hikari 7, JUnit 6), so the chat (SHC-0007) starts on Spring AI 2.0 |
| **Ticket** | SHC-0020 (owner's decision in SHC-0006; before SHC-0007) |
| **Scope** | backend only (`pom.xml`, `src/`); the frontend is untouched |
| **References** | Spring Boot 4.0 migration guide (GitHub wiki), `.claude/docs/postgres.md`, `.claude/docs/auth.md`, `.claude/docs/app-origin.md` |

## 1. Problem
- Spring AI 2.0 (GA June 2026) requires Boot 4 / Framework 7 / Jackson 3; our Boot 3.4.4 can only use Spring AI 1.1.x,
  whose move to 2.0 later means breaking changes in chat code. Upgrading first keeps the chat on the current line.
- Boot 3.4 is also behind on support; 4.1.1 is current (released 2026-08-20).

## 2. What changes for this codebase (from the migration guide, mapped to our files)
| Area | Change | Where |
|---|---|---|
| Starters | `spring-boot-starter-web` → `-webmvc`; `-oauth2-resource-server` → `-security-oauth2-resource-server`; Flyway via `spring-boot-starter-flyway`; tests: `spring-boot-starter-webmvc-test`, `-security-test`, `-jdbc-test`, `-flyway-test` instead of one `spring-boot-starter-test` (+ `spring-security-test`) | `pom.xml` |
| Jackson 3 | `com.fasterxml.jackson.*` → `tools.jackson.*` (`ObjectMapper`, `TypeReference`); `JsonProcessingException` → `JacksonException`; Boot provides a `JsonMapper`; annotations keep their package | `DataService`, `FilterTranslator`, tests (`DataServiceIT`, `FilterTranslatorTest`, `FilterSqlIT`) |
| Tests | `@SpringBootTest` no longer configures MockMvc by itself (we already add `@AutoConfigureMockMvc`); test auto-config annotations moved packages | `IntegrationTest`, `GuestLoginDisabledIT` |
| Tomcat | Boot's Tomcat classes moved into a Tomcat module/package | `AppsConnectorConfig` (`TomcatServletWebServerFactory`, `WebServerFactoryCustomizer`) |
| Security 7 | DSL is lambda-only already; resource server, `JwtIssuerAuthenticationManagerResolver`, method security to re-check | `SecurityConfig`, `AppsSecurityConfig`, `MethodSecurityConfig` |
| Actuator | liveness/readiness probes on by default (fine; `/actuator/health/**` already public) | — |
| Properties | renames surfaced by `spring-boot-properties-migrator` (temporary) | `application*.properties` |
| JSON output | dates, null handling, unknown properties under Jackson 3 defaults must stay as the GUI and apps expect (ISO-8601 strings) | API responses, Data API |

## 3. Risks
| Risk | Mitigation |
|---|---|
| Jackson 3 changes the JSON the GUI / apps / SDK see (date format, field order, nulls) | Compare responses before/after with curl on the same data; ITs assert shapes; `spring.jackson.use-jackson2-defaults=true` only as a fallback |
| Security 7 behaviour change (issuer routing, 401 vs 403, CORS) | `SecurityIT`, `GuestLoginIT`, `ControllerAuthorizationIT`, `PortFenceIT` must pass unchanged |
| Embedded Postgres (zonky) / Flyway 12 / Hikari 7 interplay | Full IT suite; dev run |
| `spring-boot:test-run` / `DevApplication` changes | Local dev run is part of acceptance |
| Third-party libs without Boot 4 support | Only zonky (test scope, framework-independent) |

# Phase 2 — Solution design

Approach: one step to 4.1.1 (no 3.5 stop-over — we have few dependencies and full test coverage), compile-driven,
then behaviour-driven: the existing 94 unit + 145 integration + 38 frontend tests are the contract; nothing in them
changes except imports/annotations.

## Tasks
- Task 1 — `pom.xml`: parent 4.1.1, renamed starters, test starters, Flyway starter; temporary `spring-boot-properties-migrator`
- Task 2 — Main code compiles: Jackson 3 imports, Tomcat packages, anything Security 7 / Framework 7 removed
- Task 3 — Test code compiles: test auto-config packages, Jackson 3 in tests
- Task 4 — Full suite green; fix behaviour differences (JSON shape, security answers) without changing test expectations
- Task 5 — JSON before/after comparison (`/api/me`, `/api/apps`, `/api/apps/{slug}`, Data API query, export) on the same data
- Task 6 — Local dev run (`npm run dev` path): both ports, Flyway, seeding, guest sign-in, Data API
- Task 7 — Remove the properties migrator; docs (`AGENTS.md` stack, `postgres.md` Flyway note)
- Task 8 — Deploy (only on the owner's order) + check `test` (health, apps, data, sign-in)

## Acceptance
1. Boot 4.1.1 on the classpath; no deprecated starter names; build without warnings from the properties migrator.
2. All tests green with unchanged expectations.
3. API JSON identical before/after for the compared endpoints (or every difference explained and harmless).
4. Local dev and `test` work (sign-in both tiers, apps, apps origin, Data API, export).

# Phase 3 — Implementation
(filled in while working)

# Phase 4 — Tests
(filled in while working)
