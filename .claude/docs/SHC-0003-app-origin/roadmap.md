# SHC-0003 — App serving on its own origin — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md` (U1 shared domain, U2 api second domain, U3 public HTML, U4 srcdoc previews)
- [x] Phase 2 — Solution Design → `solution-design.md` (apps on a second port of the API, own security chain)
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — `AppsProperties` (`port`, `urlPattern`), properties, `AppsConnectorConfig`
    - [x] Task 2 — `AppOrigin`, `PortFence`, `AppsSecurityConfig`
    - [x] Task 3 — `SdkInjector` + `sdk.js`; `AppRegistry.html`; `AppDocumentController` (headers, CSP)
    - [x] Task 4 — Library mirror (Alpine, Tailwind browser, Chart.js) + `LibraryMirrorConfig`
    - [x] Task 5 — `AppDetail.frameUrl`; frontend `AppFrame` / `DraftFrame`, `AppHostPage`, Build/Upload
    - [x] Task 6 — Local end-to-end check; `AGENTS.md`
    - [x] Task 7 — Railway (user, dashboard): second domain → port 8081, `APPS_URL_PATTERN`
    - [ ] Task 8 — Deploy (on the user's order) + acceptance criteria
- [ ] Phase 4 — Tests
- [ ] Close ticket (ask)
