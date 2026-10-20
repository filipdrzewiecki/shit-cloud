# Implementation — SHC-0007: Chat backend

Phase 3 approved with D1–D4 as proposed ("go, no gates for phase 3").

## Task 1 — Spring AI + settings
- `pom.xml`: `spring-ai.version` 2.0.1, `spring-ai-bom` import, `spring-ai-starter-model-anthropic` (brings the
  Anthropic Java SDK 2.52 with its own Jackson 2 — separate package, no clash with Jackson 3).
- `application.properties`: `spring.ai.anthropic.api-key=${ANTHROPIC_API_KEY:}`, model `${CHAT_MODEL:claude-sonnet-5-5}`,
  `chat.*` (max-tokens 32000, max-steps 5, history-messages 20, ping-interval 15s), `spring.mvc.async.request-timeout=10m`.
- Checked: the app starts with an empty key (the Anthropic client is only built, not called).

## Task 2 — Storage
- `V3__chat.sql`: `chat_conversations`, `chat_messages` (parts JSONB, model, tokens).
- `chat/ConversationStore`, `Conversation`, `UiMessage`. `append` locks the conversation row for a gap-free `seq`.

## Task 3 — Stream
- `chat/UiParts` (one factory per part type), `chat/UiStreamWriter` (`data:` frames, `<` → `<`, `: ping`,
  `[DONE]`, synchronized, `ClientGoneException` on a failed write).
- Deviation: `UiParts` (static factories of maps) instead of a `UiPart` record — parts are open-ended maps anyway.

## Task 4 — Plug-ins and history
- `chat/ChatTool` (+ `ToolException`, `inputForHistory`), `ToolResult` (+ `forModel`, `DataPart`), `TurnContext`,
  `ChatSettings`, `HistoryMapper`.
- Deviation: `ToolResult.forModel` added for `get_app` (model reads the source, user sees the size).

## Task 5 — Engine
- `chat/ChatEngine`: step loop over `ChatModel.stream()` read with `toStream()` (closing = cancel), text deltas, tool
  runs (unknown tool / `ToolException` / crash → `tool-output-error`), data parts, usage summed per answer, pings
  (first after one interval), `error` part on failure, the answer saved in `finally` (also partial).
- Found on the way: `AnthropicChatModel` ignores generic options → options from `model.getOptions().mutate()`;
  `Prompt` keeps the list it gets → each step gets `List.copyOf(messages)` (caught by `BuildChatControllerIT`).

## Task 6 — ShitCloud tools and prompt
- `buildchat/SaveDraftTool` (whole document, ≤ `apps.max-html-size`, `data-draft` with `targetSlug`, placeholder in
  history), `buildchat/GetAppTool` (≤ 300 KB, source to the model only), `buildchat/BuildChatPrompt`.
- `resources/build-chat/chat-rules.md`; the builder guide copied onto the classpath (`pom.xml` resources,
  `Dockerfile` copies `frontend/src/guide/builder-guide.md`).

## Task 7 — Endpoints
- `buildchat/BuildChatController`: `POST /api/chat` (`StreamingResponseBody`, protocol headers), `GET
  /api/chat/conversations[/{id}]`, checks before the stream (`CHAT_UNAVAILABLE`, `BAD_REQUEST`, `NOT_FOUND`,
  `NO_SUCH_APP`). Records nested in the controller (copyable package) rather than `model/`.
- Mock moved: `ChatController` → `/api/chat/mock`; `frontend/src/api/queries.ts` path; `ChatControllerIT` paths.

## Task 8 — Boundary
- `ChatPackageBoundaryTest`: no `com.drzewiecki.shitcloud.*` imports in `chat/` except `chat` itself.

## Task 9 — Docs
- `chat-protocol.md`: "Server implementation" section (Spring AI facts, silent minute, options trap, saved shape,
  history rules, refusals, prompt, `ai` parse check); `AGENTS.md`: stack line, layout, API table, chat config.

## Verification
- `mvn verify`: 107 unit + 161 IT green; frontend `npm test` 38 green; frontend typecheck clean.
- A stream from `/api/chat` (fake model, tool round with `<script>` and Polish text in the HTML) parsed with
  `ai@7.0.146` `DefaultChatTransport` + `readUIMessageStream` → step-start · text · tool-save_draft
  (output-available) · data-draft (HTML intact) · step-start · text.

## Task 10 — Deploy + check on `test` (pending: owner's commit/push order, Railway outage)
Signed in on the GUI on `test` as a builder, DevTools console:
```js
const k = Object.keys(localStorage).find(k => k.startsWith('@@auth0spajs@@') && k.includes('shit-cloud-api'));
const token = JSON.parse(localStorage[k]).body.access_token;
const res = await fetch('https://api-test-27f4.up.railway.app/api/chat', { method: 'POST',
  headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + token },
  body: JSON.stringify({ message: { id: 'u1', role: 'user', parts: [{ type: 'text', text: 'Zrób prostą ankietę lunchową: każdy głosuje na jedno miejsce, widać wyniki' }] } }) });
console.log(res.status);
const reader = res.body.pipeThrough(new TextDecoderStream()).getReader();
let buf = '', text = '';
for (;;) {
  const { value, done } = await reader.read(); if (done) break; buf += value;
  for (let i; (i = buf.indexOf('\n\n')) >= 0;) {
    const f = buf.slice(0, i); buf = buf.slice(i + 2);
    if (!f.startsWith('data: ')) continue;
    const d = f.slice(6); if (d === '[DONE]') { console.log('DONE'); continue; }
    const p = JSON.parse(d);
    if (p.type === 'text-delta') text += p.delta;
    else if (p.type === 'data-draft') { window.draftHtml = p.data.html; console.log('DRAFT', p.data.html.length, 'chars'); }
    else console.log(p.type, p.toolName || p.errorText || p.data?.id || '');
  }
}
console.log(text);
// Look at the app (runs in local mode, without ShitCloud data): open(URL.createObjectURL(new Blob([draftHtml], {type: 'text/html'})))
```
Expected: 200, start · data-conversation · steps · `tool-input-available save_draft` · `tool-output-available` ·
DRAFT · finish · DONE, and a working app.

# Phase 4 — Tests

- added: `chat/UiStreamWriterTest` (frames, `<` escaping, client gone), `chat/HistoryMapperTest` (text, tool step,
  only the latest draft in full, stopped tool call left out, tool error), `chat/ChatPackageBoundaryTest`,
  `buildchat/SaveDraftToolTest` (draft + targetSlug, refusals, no second copy in history)
- added: `chat/ChatEngineIT` (text answer saved with usage, continued conversation resends history, tool round with
  data part and second step, tool failures, step limit, prompt options = model's own + tools + max tokens, model
  failure keeps partial text, client gone cancels the model call and keeps partial text)
- added: `buildchat/BuildChatControllerIT` (guest/user 403, protocol headers + continue + get as `UIMessage[]`,
  long title, draft kept once, rework via `get_app` with `targetSlug`, conversations per builder, refusals before
  the stream), `buildchat/BuildChatUnavailableIT` (503 without key)
- support: `chat/FakeChatModel`, `chat/ChatIntegrationTest`
- modified: `controller/ChatControllerIT` (mock path `/api/chat/mock`)
