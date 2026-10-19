# Implementation — SHC-0003: App serving on its own origin

Input: `solution-design.md` (D1–D13).

## Done (Tasks 1–6)

| Task | Result |
|---|---|
| 1 | `AppsProperties.port` / `urlPattern`; `apps.port=${APPS_PORT:8081}`, `apps.url-pattern=${APPS_URL_PATTERN}` (no default); dev `http://{slug}.apps.localhost:8081/`; `apps/AppsConnectorConfig` adds the Tomcat connector (refuses `apps.port == server.port`) |
| 2 | `apps/AppOrigin` (validates the pattern at startup with an explanatory message), `apps/PortFence` (HIGHEST_PRECEDENCE, checks servlet path + path info, i.e. the container-normalized path), `apps/AppsSecurityConfig` (`@Order(1)`, `securityMatcher` = local port, permitAll, frame options + cache-control headers off) |
| 3 | `resources/apps/sdk.js`, `apps/SdkInjector`, `AppRegistry.html(slug)` (raw `Document` projection), `apps/AppDocumentController` (`/`, `/{slug}/`, `/{slug}` → 302 with a relative `Location`; also checks the port itself) |
| 4 | `resources/apps-lib/`: Alpine 3.17.4, Tailwind browser 4.3.3, Chart.js 4.5.1 (unmodified jsDelivr copies), `LICENSES.md`; `apps/LibraryMirrorConfig` (`max-age=86400, public`) |
| 5 | `AppDetail.frameUrl`; `AppFrame` (src + `allow-same-origin`), new `DraftFrame` (srcdoc) in Build/Upload, host bar shows the apps host + "Open in new tab"; frontend build green |
| 6 | Local run: Tomcat on 8080 + 8081, fence checked both ways with curl, document headers + SDK first verified. `AGENTS.md` updated (stack, running, layout, API, non-negotiables) |

## Deviations from the design

- **Reserved slugs.** The local check found `/actuator` on the apps port answered 200 (it is slug-shaped, so the
  fence let it through to the actuator index). `PortFence.RESERVED_SEGMENTS` (`api`, `actuator`, `error`) are
  now excluded from the apps-port paths, and `AppRegistry.slugify` refuses names with those slugs (400), since
  such an app could never be opened in path mode.
- **`AppRegistry.html`** reads a raw `org.bson.Document`: a projection into the `AppRecord` record fails
  (primitive `version` cannot be null). Also found by the local check.
- `X-Content-Type-Options: nosniff` comes from Spring Security's default headers on the apps chain, not
  from the controller.
- Browser-level checks (origin, localStorage, CSP blocking, libraries running) were not done locally: the
  Chrome extension did not respond. They are in the acceptance criteria on `test`.

## Task 7 — Railway (user, dashboard) — done: apps domain `api-test-e5c7.up.railway.app` → 8081

1. `api` → Settings → Networking → Public Networking → **Generate Domain**, target port **8081**.
2. `api` → Variables → `APPS_URL_PATTERN=https://<that domain>/{slug}/` (exactly this shape, trailing slash).
3. Nothing else: `CORS_ALLOWED_ORIGINS` already names the frontend and doubles as `frame-ancestors`.

Until the variable exists, a deploy of this code fails at startup (by design), so set it **before** pushing.
