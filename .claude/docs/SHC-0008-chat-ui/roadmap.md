# SHC-0008 — React chat package: the Build screen on the real chat — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md` (U1 preview without data; U2 copyable `src/chat/` folder; U3 "Change with AI" in Manage menu + conversation list; U4 remove the mock)
- [x] Phase 2 — Solution design → `solution-design.md` (D1–D4 accepted)
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — Backend: `GET /api/platform` (library base URL); remove the mock
    - [x] Task 2 — `authorizedFetch`; conversation + platform queries; mock query removed
    - [x] Task 3 — Dependencies `ai`, `@ai-sdk/react`, `react-markdown`
    - [x] Task 4 — `src/chat/` package (transport, drafts, markdown, parts, panel, css)
    - [x] Task 5 — `DraftFrame` base href (+ Upload); preview note
    - [x] Task 6 — `BuildPage`: conversations, keyed panel, URL state, Deploy vs. Replace HTML
    - [x] Task 7 — "Change with AI" in the Manage menu
    - [x] Task 8 — Docs
    - [ ] Task 9 — Local browser check (extension failed — owner); deploy (owner's order) + check on `test`
- [x] Phase 4 — Tests
    - added: `boundary.test.ts`, `drafts.test.ts`, `serverChat.test.ts`, `toolLabels.test.ts`, `draftHtml.test.ts`,
      `PlatformControllerIT`, `AppOriginTest#libraryBaseUrl…`; removed: `ChatControllerIT`
- [ ] Close ticket (ask)
