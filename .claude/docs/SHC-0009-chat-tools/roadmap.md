# SHC-0009 — ShitCloud tools + system prompt — Roadmap

Flow: **full** (one file per phase, phase gates mandatory). Scope extended by the owner (2026-10-21): "Change with
AI" edits an app in place (patch tool) instead of rewriting the whole file.

- [x] Phase 1 — Analysis → `analysis.md` (U1 exact-text edits; U2 base = latest draft else app; U3 edits whenever a
  draft exists; U4 ≤5 samples × 2 KB; U5 `list_spaces` tool only; U6 five prompts, owner judges; U7 English labels)
- [x] Phase 2 — Solution design → `solution-design.md`
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — `chat/`: `DataParts` + `TurnContext.data`; engine hands tools the latest-data lookup
    - [x] Task 2 — `EditDraftTool` + shared draft checks; register
    - [x] Task 3 — `DataService.spaces()` + `sample(…)`
    - [x] Task 4 — `ListSpacesTool` + `SampleDocsTool`; register
    - [x] Task 5 — Frontend tool lines (`toolLabels.ts`)
    - [x] Task 6 — Prompt: `chat-rules.md`, rework section, tool descriptions, English rule in the guide
    - [x] Task 7 — Docs: `chat-protocol.md`, `data-api.md`, `AGENTS.md`
    - [ ] Task 8 — Deploy (owner's order) + acceptance run of five prompts; tune until 4/5
- [ ] Phase 4 — Tests
- [ ] Close ticket (ask)
