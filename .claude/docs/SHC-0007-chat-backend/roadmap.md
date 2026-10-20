# SHC-0007 — Chat backend (Spring AI 2.0 + Claude, SSE) — Roadmap

Flow: **full** (one file per phase, phase gates mandatory).

- [x] Phase 1 — Analysis → `analysis.md` (U1 own package, easy to copy; U2 backend only; U3 SSE; U4 `save_draft` + `get_app`; U5 Sonnet 5.5)
- [x] Phase 2 — Solution design → `solution-design.md` (own step loop over `ChatModel.stream()`; D1–D4 accepted)
- [ ] Phase 3 — Implementation → `implementation.md`
    - [x] Task 1 — `pom.xml` Spring AI 2.0.1 + Anthropic starter; chat settings; starts without a key
    - [x] Task 2 — `V3__chat.sql` + `ConversationStore`
    - [x] Task 3 — `UiPart`, `UiMessage`, `UiStreamWriter`
    - [x] Task 4 — `ChatTool`, `ToolResult`, `ChatSettings`, history → Spring AI messages
    - [x] Task 5 — `ChatEngine` (step loop, tools, usage, cancel, errors, save)
    - [x] Task 6 — `SaveDraftTool`, `GetAppTool`, system prompt
    - [x] Task 7 — `BuildChatController` (SSE + conversations); mock → `/api/chat/mock`
    - [x] Task 8 — `ChatPackageBoundaryTest`
    - [x] Task 9 — Docs (`chat-protocol.md`, `AGENTS.md`)
    - [ ] Task 10 — Deploy (on the owner's order) + DevTools check on `test` — snippet in `implementation.md`
- [x] Phase 4 — Tests
    - added: `UiStreamWriterTest`, `HistoryMapperTest`, `ChatPackageBoundaryTest`, `SaveDraftToolTest`, `ChatEngineIT`,
      `BuildChatControllerIT`, `BuildChatUnavailableIT` (+ `FakeChatModel`, `ChatIntegrationTest`)
    - modified: `ChatControllerIT` (mock path)
- [ ] Close ticket (ask)
