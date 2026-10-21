# Analysis — SHC-0009: ShitCloud tools + system prompt

## Metadata

| Field               | Value                                                                                              |
|---------------------|----------------------------------------------------------------------------------------------------|
| **Description**     | Give the build chat what it needs to build working apps on the first try: see existing data (`list_spaces`, `sample_docs`), edit an app in place instead of rewriting it, and a system prompt tuned against the Phase 2 criterion. |
| **Ticket**          | SHC-0009                                                                                           |
| **Endpoints**       | none new — tools run inside `POST /api/chat`                                                       |
| **Key classes**     | `buildchat/BuildChatController`, `BuildChatPrompt`, `SaveDraftTool`, `GetAppTool`, new tools       |
| **Related classes** | `chat/ChatEngine`, `chat/TurnContext`, `chat/HistoryMapper`, `data/DataService`, `service/AppRegistry`, `frontend/src/chat/toolLabels.ts` |
| **Phase 2 goal**    | 4 of 5 different apps generated from a prompt work without manual fixes                            |
| **References**      | `shitcloud-handoff.md` §7.1, `.claude/docs/chat-protocol.md`, `.claude/docs/data-api.md`           |

---

# 1. Problem

## 1.1. What is the problem?
- The model cannot see any data. Building an app on an existing space (e.g. a report over team-calendar's
  `marks`) means guessing the collections and document shapes; a wrong guess gives an app that shows nothing.
- Reworking an app means streaming the whole file back through `save_draft`. Team-calendar took longer than 60 s
  (the "Stream failed" on `test`, fixed with the timeout in `c0f4c2f`): slow, costly in output tokens, and every
  rewrite risks the model silently dropping or changing parts it was not asked to touch.
- The system prompt has never been tested against the Phase 2 criterion: there is no set of test prompts and no
  record of how they fared.

## 1.2. Why is it a problem? What may be problematic to implement?
- **Edits need a base.** A patch applies to "the current draft" — produced by an earlier `save_draft`/patch in
  this answer, in an earlier answer, or (first edit) the deployed app. Tools today only get `TurnContext(caller,
  conversation)`; they cannot see drafts. The chat package is generic and copyable (`ChatPackageBoundaryTest`), so
  the way a tool reads the latest draft must not leak ShitCloud into `chat/`.
- **Edit matching.** Old text must occur exactly once; whitespace and repeated snippets (`</div>`) cause misses. The
  model must get a precise error ("not found" / "found 3 times") so it can retry in the next step, within
  `chat.max-steps=5`.
- **History cost.** Today only the latest draft goes back to the model in full. Edits must keep it that way: the
  result of a patch is a new full draft (preview + history), the edit list itself is small.
- **Company data to the vendor.** `sample_docs` sends real documents to Anthropic (handoff §7.1, §10: needs company
  approval before a pilot). On `test` it is the owner's key and the owner's data. Must stay small (few documents,
  size cap) and be visible to the user (tool line in the chat).
- **Acceptance is manual and costs tokens.** "4 of 5 work without manual fixes" can only be judged by a person
  clicking through the preview.

## 1.3. How the owner would like to solve it
- Roadmap scope: `list_spaces`, `sample_docs`, SDK docs in the system prompt (already there: the builder guide is
  appended), iterate on the prompt against the 4/5 criterion.
- Added (owner, 2026-10-21): "Change with AI" edits apps in place with a patch tool instead of rewriting the whole
  HTML. This deliberately departs from handoff §7.1 ("the model returns the whole HTML file each time; diffs are
  unnecessary complexity") — real apps (team-calendar) outgrew that assumption.

# 2. Context

## 2.1. Key context
- Tools: `ChatTool` (name, description, JSON schema, `run`, `inputForHistory`), result = small `output` for the UI,
  optional `forModel` text (not saved), `data` parts for the client. Engine runs up to `chat.max-steps` model calls.
- `save_draft` emits `data-draft` with fixed id `draft` → the preview; `HistoryMapper` resends only the latest
  `data-draft` in full. `get_app` returns the HTML as `forModel` only (not saved), capped at 300 KB.
- Data: one `docs` table (`space` = app slug, `collection`, `doc_id`, `data jsonb`). Everyone signed in may read
  every space through the Data API, so builders seeing samples adds no new read right.
- `BuildChatPrompt` = `chat-rules.md` + `builder-guide.md` (the same guide `/guide` shows) + a "this conversation
  reworks `<slug>`" section.
- UI: `frontend/src/chat/toolLabels.ts` turns each tool call into one line ("Read team-calendar"); new tools need
  labels.

## 2.2. Main flows

New app on existing data:
```
user: "monthly report of office days from team-calendar"
  → list_spaces            → [{space: team-calendar, collections: {people: 12, marks: 840}}, …]
  → sample_docs(team-calendar, marks, 3) → 3 real documents (shape)
  → save_draft(whole html) → preview
```

Change an app (target set, "Change with AI"):
```
  → get_app(slug)                   → current HTML (model reads it)
  → edit_draft([{old, new}, …])     → base = latest draft, else the deployed app
                                      → applied in order, all or nothing
                                      → new full data-draft → preview
  (miss → error "old text not found / found N times" → model retries next step)
```

## 2.3. Current state of things
- Done: `get_app`, `save_draft`, streaming, saved conversations, Build screen (SHC-0007/0008).
- Missing: `list_spaces`, `sample_docs`, an edit tool, a way for a tool to read the latest draft, labels for the
  new tools, a test-prompt set and its results.
- Out of scope (later tickets): `get_deps` and cross-space reads (SHC-0010), `propose_migration` (SHC-0011),
  token limits (SHC-0015).

# 3. Open questions for Phase 2

| # | Question | Proposal |
|---|---|---|
| Q1 | Edit format | Exact-text replacements `[{old_text, new_text}]`, each `old_text` unique in the base, applied in order, all or nothing (same model as Claude's own editing tools — the model is trained on it). No line numbers, no unified diff. |
| Q2 | Where the edit's base comes from | Latest draft of the conversation (incl. earlier in the same answer), else the target app's deployed HTML. Needs a small generic hook in `chat/` (e.g. the engine hands tools the latest data part by name) — designed in Phase 2. |
| Q3 | Edits for new apps too? | Yes: once a draft exists, follow-up changes use edits whatever the conversation started as. First version of a new app stays `save_draft`. |
| Q4 | `sample_docs` limits | n ≤ 5 (default 3), newest first, each document cut to a size cap; shown as one tool line. Owner to confirm sending real data is fine on `test`. |
| Q5 | `list_spaces` content | Every non-archived app: slug, name, description, collections with document counts. Tool only, not baked into the system prompt (stays fresh, costs nothing when unused). |
| Q6 | Acceptance set | Five prompts written down in the ticket (e.g. lunch poll, office-days report on team-calendar data, equipment loan list, meeting-room booking, a change to an existing app); the owner runs them on `test` and records pass/fail. Prompt tweaks until 4/5. |
| Q7 | Prompt language rule | Handoff says "UI labels in Polish"; `chat-rules.md` says "the language the user writes in". Keep the current rule unless the owner wants Polish fixed. |

# 4. Owner's decisions (2026-10-21)

- **U1 — Edit format:** exact-text replacements `edit_draft({edits: [{old_text, new_text}]})`; each `old_text` unique
  in the base; applied in order, all or nothing; a miss returns the reason ("not found" / "found N times — add more
  context") for the model to retry.
- **U2 — Edit base:** the latest draft of the conversation (also one from earlier in the same answer), else the
  target app's deployed HTML. A small generic hook in `chat/` lets a tool read it; `chat/` stays ShitCloud-free.
- **U3 — When edits:** whenever a draft exists, for new and existing apps alike. The first version of a new app is a
  full `save_draft`; `save_draft` stays for a rewrite the user asks for.
- **U4 — `sample_docs`:** default 3, at most 5 documents, newest first, each cut at ~2 KB; one tool line in the chat.
  Real data to the vendor is fine on `test` (owner's key and data); company approval still needed before a pilot.
- **U5 — `list_spaces`:** tool only (not in the system prompt); non-archived apps with slug, name, description and
  collections with document counts.
- **U6 — Acceptance:** five fixed prompts, run by the owner on `test`, pass = works without manual fixes; prompt and
  guide tuned until 4/5. No cross-space app in the set (apps read only their own space until SHC-0010):
  1. Lunch poll: today's options, one vote per person, live results
  2. Equipment loans: who has which laptop/monitor, return date, overdue in red
  3. Meeting-room booking: 3 rooms, 30-min slots, no double booking
  4. Retro board: 3 columns, cards, votes, chart of votes per column
  5. Change: team-calendar — add "Invoiced" per month, saved with Save (data survives, `edit_draft` used)
- **U7 — Language:** app labels **always English**, whatever language the request is in (replaces "the language the
  user writes in" for apps; chat replies still follow the user's language). Departs from handoff §7.1 ("Polish").
