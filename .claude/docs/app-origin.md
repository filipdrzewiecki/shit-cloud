# Apps origin — reference

How deployed apps are served apart from the platform, and why. The code is the source of truth for details;
this file keeps the decisions, the Railway setup and the traps already hit.

## Model

- The API process listens on **two ports**: `server.port` (`PORT`, 8080) answers the API, `apps.port`
  (`APPS_PORT`, default 8081, a second Tomcat connector) answers app documents and the library mirror.
  Each port has its own public domain, so apps run on an origin that holds none of the platform's credentials.
- **`PortFence`** (first servlet filter) 404s anything a port must not answer, before security and MVC:
  apps port = `/`, `/{slug}`, `/{slug}/`, `/_lib/{file}`; API port = `/api/**`, `/actuator/**`, `/error`.
  It matches the servlet path + path info (container-normalized), so `/api/../_lib/x` cannot sneak through.
- **Reserved slugs** `api`, `actuator`, `error` (`PortFence.RESERVED_SEGMENTS`): excluded from the apps-port
  paths and refused at deploy (400). Without it `/actuator` on the apps port reached the actuator index —
  found by the local end-to-end run, not by the first tests.
- **`AppsSecurityConfig`**: a separate `SecurityFilterChain`, `@Order(1)`, matched by local port. permitAll,
  stateless, **frame options off** (Spring's default `X-Frame-Options: DENY` would block every app),
  cache-control headers off (handlers set their own). The API chain is untouched.
- **`APPS_URL_PATTERN`** (no default — startup fails with an explanatory message if missing or malformed;
  `AppOrigin`) decides the mode by where `{slug}` is:
  - path: `https://apps.example/{slug}/` — one shared apps origin (apps can read each other's storage);
  - host: `https://{slug}.apps.example/` — one origin per app, needs a wildcard domain + TLS (on-prem).
- `GET /api/apps/{slug}` returns **`frameUrl`**; the GUI never builds app URLs itself.

## App document

- `AppDocumentController`: path mode `GET /{slug}/` (`/{slug}` → 302 with a *relative* `Location` — Tomcat's
  `sendRedirect` would make it absolute with `http` behind Railway's proxy); host mode `GET /`.
  Public by slug, no token. Reading it does not mark the app used (the host page's API call does).
- `AppRegistry.html(slug)` reads a raw `org.bson.Document`: projecting into the `AppRecord` record fails
  (primitive `version` cannot be null).
- Headers: CSP below, `Referrer-Policy: no-referrer`, `Cache-Control: no-store` (a redeploy shows at once),
  `nosniff` (from Spring Security's defaults), **no `X-Frame-Options`**.
- CSP: `default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'none';
  form-action 'none'; base-uri 'none'; object-src 'none'; frame-ancestors <cors.allowed-origins>`.
  `'unsafe-eval'` is for Alpine. `frame-ancestors` reuses the GUI origins from `CORS_ALLOWED_ORIGINS`.
- **SDK**: `resources/apps/sdk.js`, inlined by `SdkInjector` as the first script — after `<head>`, else
  `<html>`, else the doctype (never before it: quirks mode), else at the start. `<header>` is not `<head>`.
  Today it only defines a frozen `window.shitcloud = {version: 1}`; the data bridge extends it.

## Library mirror

- `/_lib/{file}` on the apps port from `classpath:/apps-lib/`, `Cache-Control: max-age=86400, public`.
- `alpine.min.js` (Alpine 3.17.4), `tailwind.js` (Tailwind browser build 4.3.3), `chart.min.js` (Chart.js 4.5.1);
  unmodified jsDelivr copies, versions + MIT notices in `apps-lib/LICENSES.md`. Names carry no version, so an
  upgrade changes every app at once (fine for throwaway apps).
- Usage in an app: `<script src="/_lib/tailwind.js">`, `<script src="/_lib/chart.min.js">`,
  `<script defer src="/_lib/alpine.min.js">`.

## Frontend

- `AppFrame`: `src={frameUrl}`, sandbox `allow-scripts allow-same-origin allow-forms allow-modals allow-popups
  allow-downloads`. `allow-same-origin` is safe only because `src` is the apps origin.
- `DraftFrame` (Build/Upload previews): `srcdoc`, **no** `allow-same-origin` (it would get the platform origin).
- **Known gap:** a draft preview resolves `/_lib/…` against the frontend domain, so apps using the mirror look
  broken until deployed. Planned fix: the GUI learns the apps origin from the API and `DraftFrame` adds
  `<base href="<apps origin>/">` (isolation unchanged). Tracked in the product roadmap.

## Railway (`test`)

- `api` has two public domains: `api-test-27f4.up.railway.app` → 8080 (API) and
  `api-test-e5c7.up.railway.app` → 8081 (apps).
- `api` variable `APPS_URL_PATTERN=https://api-test-e5c7.up.railway.app/{slug}/` (set it **before** deploying
  code that needs it — the start fails without it).
- A second generated domain with its own target port works on one service; no custom domain needed.

## Accepted gaps

- App HTML is public by slug (anyone with the apps URL can read it).
- `allow-popups`: an app can open a popup carrying data in its URL; CSP doesn't cover that navigation.
  Revisit when apps hold data.
- Path mode on `test`: all apps share one origin and its `localStorage`.

## Testing

- MockMvc requests arrive on port 80 = the API port; `IntegrationTest.appsPort()` moves a request to 8081.
- `AppOriginTest`, `SdkInjectorTest` (unit); `AppDocumentIT`, `PortFenceIT`, `LibraryMirrorIT`.
- Verified live on `test`: origin, own `localStorage` without platform tokens, `fetch`/image blocked by CSP,
  framing from another site refused, all three libraries running in a deployed app.
