# Acceptance checklist — owner's checks on `test`

These are the features built since SHC-0008 that have not been checked by hand yet. For each feature: what it is
for, what you need, how to check it, and what counts as a pass. Tick a box when it passes. When something fails,
note what you saw (a screenshot, the chat's text, the time) next to it, and hand it over as a bug.

- GUI: https://frontend-test-7caa.up.railway.app — sign in as a **builder** (Auth0). Some steps also need a
  **guest** (shared password) in a second browser or a private window.
- API: `api-test-27f4.up.railway.app` · apps: `api-test-e5c7.up.railway.app`
- After a deploy, reload the GUI with **Ctrl+Shift+R**. An old tab keeps the old bundle (this already hid "Change with
  AI" once).
- Expect chat answers to take up to a few minutes when the AI writes a whole app.

---

## 0. Before anything else

- [ ] **Automated tests pass locally:** `mvn verify` and `npm test --prefix frontend`.
  *What for:* SHC-0011, SHC-0012 and SHC-0013 went live without their backend tests ever running. Also, every chat
  integration test was broken from SHC-0011 until the fix in `5125aaa`.
  *Pass:* everything green. If not, paste the failing output into the chat.
- [ ] **The deploy is the latest one:** in Railway, the `api` and `frontend` services of `test` show commit `5125aaa`
  (or newer) as active. The `api` logs show Flyway applied **V5, V6, V7** with no errors.
  *Pass:* both services run the latest commit; no startup errors.

---

## 1. Build chat on the real model (SHC-0008, SHC-0009) — Phase 2's "done" criterion

**What for:** builders describe a tool in plain words, and the AI writes it as one HTML app with a live preview,
ready to deploy. Changes to an existing app go through small exact edits (`edit_draft`), not a full rewrite. The AI
looks at existing data (`list_spaces`, `sample_docs`) so it keeps the same shapes. **Phase 2 is done when at least 4
of these 5 prompts produce a working app without manual fixes.**

**How (each prompt in a NEW conversation: Build → "+ New app"):**
1. Type the prompt, wait for the preview, click **Deploy**, give it a name.
2. Open the app and use it as a builder; open it as a guest in a second browser and use it there too. Data must
   be shared between the two (reload to see the other's changes).

| # | Prompt (write it in your own words) | Pass when |
|---|---|---|
| [ ] 1 | Lunch poll: today's options, one vote per person, live results | Both users vote; each has only one vote; results add up after a reload |
| [ ] 2 | Equipment loans: who has which laptop/monitor, return date, overdue in red | Add, edit and return loans; an overdue date shows red |
| [ ] 3 | Meeting-room booking: 3 rooms, 30-minute slots, no double booking | A second person can't book a taken slot |
| [ ] 4 | Retro board: 3 columns, cards, votes, a chart of votes per column | Cards and votes persist; the chart (Chart.js) draws |
| [ ] 5 | Open **team-calendar** → **⋯ Manage → ✨ Change with AI**: "add an 'Invoiced' checkbox per month" → **Replace HTML** | The chat shows "Edited the app (…)" lines (not a whole rewrite); old calendar entries are still there after Replace |

**Also watch for:**
- [ ] The app's labels are in **English** even when you write in Polish; the chat answers in your language.
- [ ] The chat shows short tool lines ("Saved the app (12 KB)", "Looked at 3 documents of …"), never raw HTML.
- [ ] **Stop** cancels a running answer. Reopening a conversation from the list shows its last draft in the preview.
- [ ] No "Stream failed" on long answers (the client limit was raised from 60 s to 10 min).

**Result:** __ / 5 passed → Phase 2 done at 4/5.

---

## 2. Apps reading other apps' data, and "Used by" (SHC-0010)

**What for:** a new app can **read** (never write) another app's data, e.g. an office report built on
team-calendar's entries. The platform records which app uses which data, so before anyone changes an app's data
format, the AI and the app page can warn: "office-report reads this — it may break".

**How:**
1. Build → new conversation: "a report of who is in the office this week, using the team calendar's data".
   - [ ] The chat looks at the data first (lines like "Listed N apps", "Looked at 3 documents of team-calendar / …").
2. Deploy it as e.g. *Office Report* and **open it once** (the link is recorded when the app reads the data).
   - [ ] It shows real entries from team-calendar.
3. Open **team-calendar**'s page.
   - [ ] Under the bar: "Its data is used by **Office Report**" (as a link). Builders only: a guest does not see it.
4. Manage → Change with AI on team-calendar: "rename the field for the day type".
   - [ ] Before changing anything, the chat says that Office Report reads this data and will likely break (tool line
     "Checked who uses team-calendar's data (1 other app)").

**Pass:** all four ticks.

---

## 3. Converting an app's data to a new format (SHC-0011) — first half of Phase 3's "done" criterion

**What for:** apps are disposable, data is not. When a change needs the stored records in a new shape, the AI writes
a conversion. The platform tries it on every record without saving, shows the result for approval, applies it
together with the new HTML (all or nothing), and keeps the old records 30 days for undo. **Phase 3 needs: the
calendar rewritten to a new data format without losing records.**

**Prepare:** note how many entries team-calendar has (Manage → **⬇ Download data** keeps a copy of the file, a good
baseline to compare with).

**How:**
1. team-calendar → Manage → **Change with AI**: e.g. "split the day type into kind (office/remote/absence) and an
   optional reason, and convert the existing entries".
   - [ ] The chat edits the app, then shows a **Data change** card: N documents, changed / unchanged / removed /
     failed, which fields change, and before → after examples. If other apps read it, a ⚠ line names them.
   - [ ] **Nothing has changed yet:** the deployed calendar still shows the old entries as before.
2. In the preview head: the checkbox **"Convert the data (N documents)"** is on, and the button says **"Replace HTML of
   “Team Calendar” and convert the data"** → click it.
   - [ ] You land on the calendar page; the new version shows **all** old entries in the new form.
   - [ ] The count is the same as before (download the data again and compare), unless the chat said some would be
     removed.
3. A guest adds or changes an entry. It works with the new format.
4. Manage → **↶ Undo data change…**: the dialog says what comes back, until when, and that later entries are replaced.
   - [ ] **⬇ Data before the change** downloads the old records.
   - [ ] **Undo** → the old HTML and the old entries are back. The guest's entry from step 3 is gone from the app but
     visible in Data & history (section 4).
5. Failure path (optional): ask for a conversion and tell the AI to make it fail on some records ("throw an error
   for entries of type off").
   - [ ] The card shows failures; the bar says the data change fails and can't be applied; Replace HTML alone still
     works.

**Pass:** steps 1–4 ticked, no record lost.

---

## 4. Data & history — browse and restore records (SHC-0012)

**What for:** every app record keeps its earlier states for 90 days. When someone overwrites or deletes a colleague's
entry by mistake (guests can write too, last write wins), a builder can see who changed what and put an earlier
state back.

**How:**
1. As a guest, in team-calendar: change one entry, then **delete** another.
2. As a builder: team-calendar → Manage → **🕘 Data & history**.
   - [ ] Collection tabs with counts (e.g. "days 412 · 1 removed").
   - [ ] **Filter by id** narrows the list; **Load more** appears with more than 50 records.
3. Click the changed entry.
   - [ ] "Now: vN · by guest:…" with its fields, then the earlier versions, newest first, each with who wrote it
     and when, and what replaced it.
   - [ ] In an earlier version, fields that differ from now are marked ("differs", "not there now", "added since").
   - [ ] If section 3 ran: versions before the data change carry the tag **"before a data change"**.
4. **↺ Restore this version** → "Make vX the current state?" → **Yes, restore**.
   - [ ] "Restored — it is now vN+1"; the app shows the restored entry after a reload.
5. Tick **Removed records**, click the deleted entry → restore its last version.
   - [ ] It is back in the app, and gone from the removed list.
6. Conflict path: open an entry's history, then (as the guest, in another browser) change that entry, then click
   Restore.
   - [ ] "This record changed since you opened it…"; the panel shows the new state; nothing was overwritten.
7. As a guest, open `…/apps/team-calendar/data` directly.
   - [ ] "This page is for builders."

**Pass:** steps 2–7 ticked.

---

## 5. Importing an HTML file with AI adaptation (SHC-0013) — second half of Phase 3's "done" criterion

**What for:** people already have HTML tools (a planner in `localStorage`, a ChatGPT-made page with CDN links). As
is, they break on the platform (CDNs and network calls are blocked), and their data stays in one browser. The import
turns such a file into a shared team app: the AI swaps CDN libraries for hosted ones or plain JS, moves stored data to
the shared store, removes what can't work, and says what it changed. **Phase 3 needs: an external HTML with CDN libs
and localStorage imported and working.**

**Prepare a test file** (or ask any AI for it): a small to-do or planner page, under 300 KB, that loads **jQuery** and
**Bootstrap** from a CDN, uses a **Google Font**, keeps its items in **localStorage**, and calls a public API with
`fetch` (e.g. a weather box).

**How:**
1. **Upload** → choose the file.
   - [ ] "Will not work as is:" lists the external scripts with their hosts, the font, the stylesheet, the network
     call and the browser storage.
   - [ ] The preview (as is) shows the file; **Deploy as is** is there.
2. Click **✨ Adapt with AI**.
   - [ ] A Build conversation "Import <file>" opens and sends the first message by itself ("Adapt <file> to
     ShitCloud." plus the list).
   - [ ] Tool lines: "Read <file> (N KB)", "Edited the app (…)", "Listed N changes", and a **Changes to the file**
     card: ⇄ replaced (jQuery, Bootstrap, font), → moved (the stored items to a collection), ⚠ won't work (the
     weather/API call).
   - [ ] The reply says old browser data is not carried over.
3. Preview **Before / After**:
   - [ ] Before = the original look (it may load its CDN files in your browser).
   - [ ] After = the adapted version, looking and working much the same, without the removed parts.
4. Ask for one follow-up ("keep a placeholder box where the weather was") and check the draft changes.
5. **Deploy** (the name is prefilled from the file name). Open the app as a builder and as a guest.
   - [ ] Items added by one person show for the other after a reload (shared data, not localStorage).
6. Reopen the conversation from the list ("imports <file>").
   - [ ] Before/After still works (the original is loaded from the server).
7. Size limit: upload a file over 300 KB.
   - [ ] **Adapt with AI** is disabled with "The AI adapts files up to 300 KB…"; Deploy as is still works.

**Pass:** steps 1–6 ticked, the deployed app works for two people.

---

## 6. Quick regression (things that worked before)

- [ ] App list loads; opening an app works for a guest and a builder; **Open in new tab** shows the app (data calls
  fail there with `NOT_IN_SHITCLOUD`, which is expected).
- [ ] Manage menu: Download HTML / data, Replace HTML…, Archive → Restore, Delete with data (on a throwaway app).
- [ ] `/guide` (builder guide) opens; **Upload → Deploy as is** of a simple valid file works.

---

## When everything passes

Tell the chat which sections passed. Then:
- Phase 2 is done when section 1 reaches 4/5.
- Phase 3 is done when sections 3 and 5 pass.
- SHC-0008 can be closed (`[~]` in `product-roadmap.md`), and this checklist can be trimmed to what is still
  unchecked.
