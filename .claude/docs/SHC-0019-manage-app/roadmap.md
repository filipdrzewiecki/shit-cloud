# SHC-0019 — Manage a deployed app: remove, download, replace HTML — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md` (U1 any builder, U2 two download buttons, U3 no import, U4 archived opens with Restore, U5 delete removes history too)
- [x] Phase 2 — Solution Design → `solution-design.md` (D1–D13, A1 `<slug>-data.json`)
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — `V2__archive.sql`; archive fields; `open()` stops un-archiving; deploy clears the archive
    - [x] Task 2 — `AppRegistry`: archive, restore, replaceHtml, delete, usage
    - [x] Task 3 — `DataService.export` + `ExportView` + cap
    - [x] Task 4 — `AppController` endpoints + permissions
    - [x] Task 5 — Frontend API: types, `client.download`, mutations, `lib/download.ts`
    - [x] Task 6 — Host page: Manage menu, archived bar, Replace and Delete dialogs
    - [x] Task 7 — App list: Restore on archived cards
    - [x] Task 8 — `AGENTS.md`, roadmap note (SHC-0014), builder guide
    - [x] Task 9 — Local end-to-end check
    - [ ] Task 10 — Deploy (on the user's order) + acceptance
- [x] Phase 4 — Tests: 94 unit + 145 integration + 38 frontend green (new: AppManagementIT, AppManagementControllerIT, ExportCapIT, download.test.ts)
- [ ] Close ticket (ask)
