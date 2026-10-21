# Implementation — SHC-0009: ShitCloud tools + system prompt

Owner: "go, no gates for phase 3" (2026-10-21) — Tasks 1–7 run without per-task gates; Task 8 needs the owner's
deploy order.

## Task 1 — `chat/`: latest data part for tools
- New `chat/DataParts` (`latest(name, id)`; `of(saved, answerParts)`: this answer first, then saved messages, newest
  first; `NONE`). `TurnContext` gains `data` + a two-argument constructor and `with(DataParts)`.
- `ChatEngine.answer` loads the saved messages once (prompt + lookup) and runs tools with
  `turn.context().with(DataParts.of(saved, answer.parts))` — the live parts list, so a draft from an earlier step of
  the same answer is found. The lookup searches all saved messages, not only the 20 resent to the model.

## Task 2 — `edit_draft`
- `buildchat/Drafts`: latest draft from `TurnContext.data()`, the whole-document + size checks (moved out of
  `SaveDraftTool`), the `data-draft` part.
- `buildchat/EditDraftTool`: base = latest draft, else `registry.html(targetSlug)`, else "no draft yet". Edits in
  order; each `old_text` non-empty, ≠ `new_text`, exactly once (overlapping occurrences count) in the working copy;
  any miss → `ToolException` naming the edit, nothing applied. Output `{edited, bytes}`; model reads one line.
  Max 50 edits.
- `SaveDraftTool` uses `Drafts`; description now points changes to `edit_draft`.

## Task 3 — `DataService`
- `spaces()` (left join apps → docs, non-archived, grouped by collection) → `data/SpaceSummary`.
- `sample(space, collection, n)`: newest first (`updated_at desc, id desc`), `requireCollection` as elsewhere.

## Task 4 — `list_spaces`, `sample_docs`
- `ListSpacesTool`: the summaries as JSON for the model; the user sees `{spaces: N}`.
- `SampleDocsTool`: n default 3, clamped 1–5; documents flat like the SDK (`id`, fields, `_version`, `_updatedBy`,
  `_updatedAt`); strings > 200 chars shortened, a document > 2 KB cut with a note; `DataException` (unknown app,
  bad collection name) → `ToolException` "…; see list_spaces". The user sees `{space, collection, docs}`.
- All five tools registered in `BuildChatController`.

## Task 5 — Frontend
- `chat/toolLabels.ts`: lines for `edit_draft` ("Edited the app (2 changes, 48 KB)"), `list_spaces`, `sample_docs`.
  Errors keep the existing "<line>: <errorText>" form. Typecheck clean.

## Task 6 — Prompt
- `chat-rules.md` rewritten in sections: delivering (first version `save_draft`, every later change `edit_draft`,
  retry on a failed edit), data (look before touching, keep shapes, say when a shape changes, own space only),
  replying (chat in the user's language, **app always English**).
- `BuildChatPrompt` rework paragraph: `get_app` first (again in a later message without a draft), then `edit_draft`;
  `sample_docs` before touching data code.
- `builder-guide.md`: "All labels and texts of the app in English" (also shown at `/guide`, so it binds uploads).

## Task 7 — Docs
- `chat-protocol.md`: new "ShitCloud's tools (SHC-0009)" section, 60 s timeout gotcha, next-ticket notes.
- `data-api.md`: the chat's reads (`spaces`, `sample`). `AGENTS.md`: layout of `chat/` and `buildchat/`.

## Checks
- `mvn -q compile test-compile` clean (`SaveDraftToolTest` adjusted to the `Drafts` constructor);
  `npm run typecheck --prefix frontend` clean. Tests not run (owner runs them).
