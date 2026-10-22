# Importing an HTML file (upload → AI adaptation) — reference

A builder uploads any HTML file, a quick check lists what will not work on the platform, and the build chat adapts
the file in place (keeping its look and behaviour), lists its changes, and shows before/after. The code is the source
of truth; this file keeps the decisions and the traps.

## Decisions (owner, SHC-0013)

| | Decision |
|---|---|
| Where | **In the build chat.** "Adapt with AI" starts a conversation whose first draft is the uploaded file; the builder can keep steering it. |
| Change list | **A card** from the tool `report_changes`: one line per change, `replaced` / `moved` / `removed` / `wont_work`. |
| Preview | **One preview with a Before / After toggle.** Before = the original as written. It may load its CDN files in the builder's browser; the server never fetches them. |
| Libraries | **The AI rewrites** code that uses libraries outside the mirror into plain JS/CSS or Alpine/Tailwind/Chart.js. Whatever it can't is reported as `wont_work`. The mirror is unchanged. |
| Old data | **Not carried over.** `localStorage` data in the person's browser is unreachable. The adapted app moves storage to `shitcloud.db`; data hard-coded in the file is seeded once, while its collection is empty. The reply tells people. |
| Size | **300 KB** (`chat.max-upload-size`, the same as `get_app`). Bigger files can only be deployed as is (5 MB). |
| Quick check | **Yes**, client-side and instant. It is shown on the Upload page and becomes the chat's first message. |
| Deploy as is | **Kept** as the secondary action on the Upload page. |

## Flow

```
UploadPage: file → pages/htmlScan.scan (regex, no DOM): external scripts/styles/fonts/images (+ hosts), network
  calls, browser storage, <form action>, <base>   ·   ≤ 300 KB → "✨ Adapt with AI" · always "Deploy as is"
  → navigate('/build', {state: {upload: {fileName, html, findings}}})
BuildPage: Session.upload (router state cleared on mount: a reload must not import twice)
  ChatPanel startBody={upload: {fileName, html}} + autoSend "Adapt <file> to ShitCloud.\n\n<findings>"
POST /api/chat {…, upload}: only a new conversation without targetSlug (400), a whole HTML document (400),
  ≤ 300 KB (413 TOO_LARGE); base name ≤ 100 chars → conversation "Import <file>" + chat_uploads row (V7)
  prompt: BuildChatPrompt.forConversation(targetSlug, uploadName) — the import section (rules of adapting)
  model: get_upload → edit_draft (Drafts.latest: latest draft, else the upload, else the deployed app)
         → report_changes (data-changes, fixed id) → reply
Preview head: Before | After (After = draft, or the original labelled "Not adapted yet") · DeployBar prefilled
Reopened: ConversationSummary.upload → GET /api/chat/conversations/{id}/upload (owner only) for "Before"
```

## Notes

- The original lives in `chat_uploads`, not in a chat data part. The generic chat package only gained `startBody`
  and `autoSend`, and the 300 KB file is never resent through history: the model reads it with `get_upload`.
- `autoSend` goes once on mount, only to an empty panel; a ref guards StrictMode's second effect run.
- The scan is a checklist, not a gate: regexes may miss or over-count. The AI does the real work.
- The uploaded file may contain prompt injection. The prompt says the file is data, and the CSP and sandbox enforce
  the rules regardless of what the model does.

## Testing

`BuildChatControllerIT` (an import end to end with get_upload → edit_draft on the upload → report_changes card,
summary + `GET …/upload`, refusals before the stream), `ReportChangesToolTest` (incl. file-name cleaning),
`SchemaIT`; frontend `pages/htmlScan.test.ts`, `pages/ChangesCard.test.ts`, `chat/serverChat.test.ts`
(`startBody`), `chat/toolLabels.test.ts`.
