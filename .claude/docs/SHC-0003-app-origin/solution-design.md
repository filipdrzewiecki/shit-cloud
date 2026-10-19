# Solution Design — SHC-0003: App serving on its own origin

Input: `analysis.md` (decisions U1–U5 in §8).

---

# 1. Decisions carried from Phase 1

| # | Decision |
|---|---|
| U1 | Shared Railway apps domain on `test` (path mode). Subdomain mode supported by config and used locally |
| U2 | The `api` service serves app documents |
| U3 | App HTML is public by slug (accepted risk on `test`) |
| U4 | Build/Upload previews stay on opaque `srcdoc` |
| U5 | CSP allows `'unsafe-eval'` |

# 2. Design decisions

| # | Decision | Why |
|---|---|---|
| D1 | **The apps origin is a second port of the same JAR**: `apps.port` (default `8081`) via an extra Tomcat connector. Railway gives the `api` service a second domain whose **target port** is 8081 | Fencing by port can't be confused by a `Host` header: the API port serves only `/api/**`, `/actuator/**`, `/error`; the apps port serves only app documents and `/_lib/**`. One deployable (U2) |
| D2 | **`PortFence`** servlet filter, first in the chain: on the apps port any path outside the app routes → 404; on the main port `/_lib/**` and app routes → 404 | One small place that enforces the split; tested in both directions |
| D3 | **A separate `SecurityFilterChain` for the apps port** (`securityMatcher` = local port), ordered first: `permitAll`, stateless, no CSRF, no bearer auth, **frame options disabled** | Spring Security's default `X-Frame-Options: DENY` would block every app; the API chain keeps its defaults untouched |
| D4 | **URL pattern** `apps.url-pattern` with a `{slug}` placeholder decides the mode: `https://apps-test-….up.railway.app/{slug}/` (path) or `http://{slug}.apps.localhost:8081/` (subdomain). `AppOrigin` builds an app's URL and reads the slug back from a request | One property switches `test` to per-app subdomains when a domain exists; nothing else changes |
| D5 | `APPS_URL_PATTERN` has **no default** in `application.properties` (dev and tests set their own) | Fail fast, same as `MONGODB_URI`: a deploy without it must not hand out wrong frame URLs |
| D6 | **App document**: `GET /{slug}/` (path mode; `/{slug}` redirects to `/{slug}/` so relative URLs resolve) or `GET /` (subdomain mode). Reads `_apps.html` without marking the app used (the host page's `GET /api/apps/{slug}` already does). Unknown slug → 404 | The document is just a rendering of the stored app; usage tracking stays where the user is known |
| D7 | **Response headers** on app documents: `Content-Security-Policy` (D8), `Content-Type: text/html;charset=UTF-8`, `X-Content-Type-Options: nosniff`, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` | `no-store` so a redeploy shows at once; `no-referrer` keeps app URLs out of anything the app links to |
| D8 | **CSP**: `default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline'; img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'none'; form-action 'none'; base-uri 'none'; object-src 'none'; frame-ancestors <cors.allowed-origins>` | Handoff §4.1. `frame-ancestors` reuses the GUI origins already configured for CORS, so only the platform can frame apps |
| D9 | **SDK**: `resources/apps/sdk.js`, inlined as the **first `<script>`**. `SdkInjector` inserts it right after `<head …>`, else after `<html …>`, else after `<!doctype …>`, else at the very start. The SDK only defines a frozen `window.shitcloud = {version}` (and does nothing if it already exists) | Inline = synchronous and guaranteed first. The bridge, `db` and `me()` arrive in SHC-0004 and only add to this object |
| D10 | **Library mirror** under `/_lib/` on the apps port, from `classpath:/apps-lib/`, pinned versions with their licenses: `alpine.min.js` (Alpine 3), `tailwind.js` (Tailwind 4 browser build), `chart.min.js` (Chart.js 4). `Cache-Control: public, max-age=86400` | Stable unversioned names for app authors and the AI; a day of caching because the names don't change per version |
| D11 | `AppDetail` gains **`frameUrl`**. `html` stays (later chat tools rework an app's HTML) | The GUI never builds app URLs itself |
| D12 | **Frontend**: `AppFrame` loads `src={frameUrl}` with `sandbox="allow-scripts allow-same-origin allow-forms allow-modals allow-popups allow-downloads"`. A new `DraftFrame` keeps today's `srcdoc` without `allow-same-origin`, for Build and Upload. Comments explain why each is safe | The two cases can never be mixed by accident: `allow-same-origin` exists only on a frame whose `src` is the apps origin |
| D13 | Host bar badge shows the app's real origin (`new URL(frameUrl).host`), plus an "Open in new tab" link | Makes the separate origin visible; handy for debugging an app |

# 3. Known gaps (accepted, documented)
- **Public app HTML** (U3): anyone with the apps URL reads an app's HTML.
- **Popups** (`allow-popups`): an app can open a popup with data in its URL; CSP doesn't cover that navigation.
  Revisit in SHC-0004 when apps hold data.
- **Path mode on `test`**: apps share one origin, so they can read each other's `localStorage`.

# 4. Backend

## 4.1 Properties
`application.properties`:
```properties
#====== APPS ORIGIN — app documents and the library mirror are served on their own port, which gets its own
# public domain. {slug} in the pattern: in the host = one origin per app, in the path = one shared apps origin.
apps.port=${APPS_PORT:8081}
apps.url-pattern=${APPS_URL_PATTERN}
```
`application-dev.properties`: `apps.url-pattern=http://{slug}.apps.localhost:8081/`.
Tests: `apps.url-pattern=http://apps.test/{slug}/` (in `IntegrationTest`), plus a subdomain-mode test.

## 4.2 Classes (new package `apps/`)

| Class | Responsibility |
|---|---|
| `service/AppsProperties` | gains `port`, `urlPattern` |
| `apps/AppsConnectorConfig` | `WebServerFactoryCustomizer<TomcatServletWebServerFactory>` adding a connector on `apps.port` |
| `apps/AppOrigin` | Parses the pattern once: `urlFor(slug)`; `slugOf(request)` (from host in subdomain mode, first path segment in path mode); `isAppsPort(request)` |
| `apps/PortFence` | `OncePerRequestFilter`, highest precedence (D2) |
| `apps/AppsSecurityConfig` | The apps-port `SecurityFilterChain` (D3), `@Order(1)` |
| `apps/SdkInjector` | `inject(html)` (D9); reads `apps/sdk.js` once |
| `apps/AppDocumentController` | `@PreAuthorize("permitAll()")`; the document routes (D6) with headers (D7, D8) |
| `apps/LibraryMirrorConfig` | `/_lib/**` resource handler from `classpath:/apps-lib/` with cache control |
| `service/AppRegistry` | gains `html(slug)` (no side effects) |
| `model/AppDetail` | gains `frameUrl` |

## 4.3 Resources
- `src/main/resources/apps/sdk.js`
- `src/main/resources/apps-lib/alpine.min.js`, `tailwind.js`, `chart.min.js`, `LICENSES.md` (versions + MIT notices)

# 5. Frontend
- `api/types.ts`: `AppDetail.frameUrl`.
- `shell/AppFrame.tsx`: `src` + full sandbox (D12). New `shell/DraftFrame.tsx`: the old `srcdoc` frame.
- `pages/AppHostPage.tsx`: uses `frameUrl`; badge with the origin; "Open in new tab".
- `pages/BuildPage.tsx`, `pages/UploadPage.tsx`: switch to `DraftFrame`.
- `main.tsx` comment about credentials: points at both frames' reasons.

# 6. Railway (dashboard steps for the user; approval-needing MCP calls stall over remote control)
1. `api` → Settings → Networking → **Generate Domain** (a second Railway domain) with **target port `8081`**.
2. `api` → Variables: `APPS_URL_PATTERN=https://<that domain>/{slug}/`.
3. Push only when the user orders it.

If Railway doesn't allow a second generated domain on one service: fall back to a custom domain, or ask.

# 7. Acceptance criteria
1. An opened app runs on the apps origin: `location.origin` in the app ≠ the platform origin.
2. `localStorage` works inside a deployed app and does not contain the platform's tokens.
3. `fetch('https://example.com')` and an external `<img>` in an app are blocked by CSP; the app keeps running.
4. `window.shitcloud.version` is defined before the app's first script; apps unaware of it work unchanged.
5. A test app using `/_lib/alpine.min.js`, `/_lib/tailwind.js` and `/_lib/chart.min.js` works.
6. The apps domain answers 404 on `/api/me` and `/actuator/health`; the API domain answers 404 on `/_lib/alpine.min.js`
   and on app documents.
7. Opening an app document in a top-level tab works (U3). Framing it from another site is refused (`frame-ancestors`).
8. Build and Upload previews work as before.
9. `npm run dev`: apps at `http://{slug}.apps.localhost:8081/`.

# 8. Implementation tasks (Phase 3)

- Task 1 — `AppsProperties` (`port`, `urlPattern`), properties (main, dev), `AppsConnectorConfig`
- Task 2 — `AppOrigin`, `PortFence`, `AppsSecurityConfig`
- Task 3 — `SdkInjector` + `apps/sdk.js`; `AppRegistry.html`; `AppDocumentController` with headers + CSP
- Task 4 — Library mirror: pinned Alpine / Tailwind browser / Chart.js + licenses in `apps-lib/`; `LibraryMirrorConfig`
- Task 5 — `AppDetail.frameUrl`; frontend `AppFrame` / `DraftFrame`, `AppHostPage`, Build/Upload, types
- Task 6 — Local end-to-end check with the sample apps and a mirror test page; `AGENTS.md` (layout, running, non-negotiables, API table)
- Task 7 — Railway (user, dashboard): second domain → port 8081, `APPS_URL_PATTERN`
- Task 8 — Deploy (only on the user's order to commit/push) + acceptance criteria 1–9

Phase 4 tests:
- `AppOriginTest` (unit): both modes, `urlFor`/`slugOf`, malformed host or path.
- `SdkInjectorTest` (unit): with/without head, html, doctype, uppercase tags, fragment.
- `AppDocumentIT`: apps port 200 with CSP/nosniff/no-store and the SDK first; unknown slug 404; `/{slug}` redirect.
- `PortFenceIT`: both directions.
- `LibraryMirrorIT`: served with cache headers on the apps port only.
- `AppControllerIT`: `frameUrl` in the detail.
- `ControllerAuthorizationIT` keeps passing.

# 9. Trade-offs
| Choice | Cost accepted |
|---|---|
| Second port instead of `Host` routing | One more connector and Railway domain; in return the fence is structural |
| Public app HTML | See §3 |
| Unversioned mirror file names | Upgrading a library changes it for every app at once (fine for throwaway apps; SHC-0016 can add versioned paths) |
| Opaque previews | An app relying on `localStorage` only works after deploy |
