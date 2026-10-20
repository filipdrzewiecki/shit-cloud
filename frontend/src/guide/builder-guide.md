# Building a ShitCloud app — instructions for an AI assistant

You are helping a colleague build a small web app ("shitApp") for **S.H.I.T. Cloud** ("ShitCloud" — *Simple
HTML Internal Tools*), our internal platform for throwaway team tools. Read all of this before writing code; the platform enforces these rules in the browser,
so an app that ignores them simply breaks.

## What to deliver

- **One self-contained `.html` file**: HTML, CSS and JavaScript inline. No build step, no npm, no other files.
- Under 5 MB. Images only as inline `data:` URIs or inline SVG.
- Labels in the language the user writes to you in.

## Hard rules (enforced by the browser — not negotiable)

The app runs on its own origin inside a sandboxed iframe, with this Content Security Policy:

```
default-src 'self'; script-src 'self' 'unsafe-inline' 'unsafe-eval'; style-src 'self' 'unsafe-inline';
img-src 'self' data: blob:; font-src 'self' data:; media-src 'self' data: blob:; connect-src 'none';
form-action 'none'; base-uri 'none'; object-src 'none'
```

What that means for your code:

1. **No network at all.** `fetch`, `XMLHttpRequest`, `WebSocket`, `EventSource`, `navigator.sendBeacon` are all
   blocked. Data goes through `window.shitcloud.db` (below), nothing else.
2. **No external files.** No CDN scripts or stylesheets, no Google Fonts, no remote images. Only these
   libraries are available, served by ShitCloud itself — use exactly these tags:
   ```html
   <script src="/_lib/tailwind.js"></script>            <!-- Tailwind CSS 4, browser build -->
   <script src="/_lib/chart.min.js"></script>           <!-- Chart.js 4 (global Chart) -->
   <script defer src="/_lib/alpine.min.js"></script>    <!-- Alpine.js 3 (keep defer) -->
   ```
   Plain JavaScript and inline CSS are always fine. Fonts: system fonts
   (`font-family: system-ui, sans-serif`).
3. **No form submission.** `<form action>` posts are blocked. Handle `submit` in JavaScript and call
   `event.preventDefault()`.
4. **No `<base>` tag, no `<object>`/`<embed>`**, no navigating the top window.
5. `alert`, `confirm`, `prompt`, file downloads (e.g. CSV export via a `blob:` link) and `localStorage` work —
   but `localStorage` is only this browser's copy and, on our test environment, shared with other apps. Use it
   for UI preferences at most, never for the app's data.

## Data: `window.shitcloud`

ShitCloud injects a small SDK before your code runs. Every user of the app reads and writes **the same
shared data**. The data belongs to the app (its *space* is set by the platform — you never name it), and is
organised in **collections** of **documents**.

```js
const me = await shitcloud.me();
// { id: 'anna@company.com' or 'guest:Anna', displayName: 'Anna', guest: true/false }

const items = await shitcloud.db.find('tasks', { done: false, due: { $lte: '2026-12-31' } },
                                      { sort: { due: 1 }, limit: 100 });
// [{ id, ...yourFields, _version, _updatedBy, _updatedAt }, ...]

const saved = await shitcloud.db.set('tasks', 'task-42', { title: 'Order pizza', done: false });
// creates or replaces the document with that id; returns the stored document (same shape as find)

const newId = await shitcloud.db.add('tasks', { title: 'Book a room', done: false });
// creates a document with a generated id; returns the id (a 24-char hex string)

const removed = await shitcloud.db.remove('tasks', 'task-42');   // true, or false if it did not exist
```

All calls are **async** (return Promises) — always `await` them and handle errors.

### Documents

- A document is a plain JSON object: strings, numbers, booleans, `null`, nested objects and arrays.
  No functions, `Date` objects, `Map`/`Set` or class instances — store dates as ISO strings (`'2026-10-20'`),
  which also sort and compare correctly as text.
- `find` and `set` return documents **flat**: your fields plus `id`, `_version`, `_updatedBy` (who changed it
  last) and `_updatedAt`. Writing such an object back is safe — `id` and fields starting with `_` are dropped
  on write. So "read → change → `set`" just works.
- Your own field names: letters, digits, `_`, `-`; must not start with `_` or `$`, must not contain `.`.
- Collection names: 1–64 of `A–Z a–z 0–9 _ -`. Document ids: 1–128 of `A–Z a–z 0–9 _ . : @ -`
  (so ids like `anna@company.com_2026-10-20` or `guest:Anna` are fine).
- One document ≤ 256 KB.

### Queries (`find`)

- `filter` (optional) is a MongoDB-style filter on your fields. Allowed: plain equality `{ status: 'open' }`,
  `$eq $ne $gt $gte $lt $lte $in $nin $exists`, `$and` / `$or`, dotted paths for nested fields
  (`{ 'address.city': 'Kraków' }`). **Not allowed:** `$regex`, `$where`, `$expr` and anything else — for text
  search, load the documents and filter in JavaScript.
- How values compare (same as MongoDB):
  - equality on an **array** field also matches arrays that **contain** the value (`{ tags: 'x' }` finds
    `tags: ['x', 'y']`);
  - `{ field: null }` matches documents where the field is `null` **or missing**; `$ne` / `$nin` also match
    documents without the field;
  - `$gt`/`$gte`/`$lt`/`$lte` compare a string with strings, a number with numbers (text by character code:
    `'B' < 'a'`) — `'10'` (text) is not greater than `9` (number). Store numbers as numbers.
- Special fields you may filter/sort on: `id`, `_updatedAt` (compare with an ISO date-time text such as
  `'2026-10-20T08:00:00Z'`), `_updatedBy`.
- `sort`: `{ field: 1 | -1 }`, at most 4 fields. Without it: oldest first.
- `limit`: 1–1000, default 1000. **One query returns at most 1000 documents.** If a collection can grow beyond
  that, page through it:
  ```js
  async function findAll(collection, filter) {
    const all = [];
    let after = null;
    for (;;) {
      const f = after ? { $and: [filter || {}, { id: { $gt: after } }] } : filter;
      const page = await shitcloud.db.find(collection, f, { sort: { id: 1 }, limit: 1000 });
      all.push(...page);
      if (page.length < 1000) return all;
      after = page[page.length - 1].id;
    }
  }
  ```

### Several people at once

- **Last write wins.** Two people saving the same document at the same moment: the later one stays. Every
  earlier state is kept in the platform's history, so nothing is truly lost — but design so it rarely matters:
- **One document per thing that people change independently.** E.g. a vacation calendar: one document per
  person per day (`set('days', \`${personId}_${date}\`, { personId, date, type })`), **not** one big document
  with an array of all days — with the array, two people clicking at once overwrite each other.
- Deterministic ids for "one per X" (one vote per person: `set('votes', me.id, {...})`); `add` for free lists.
- To refuse overwriting someone else's change, pass the version you read:
  `set(c, id, data, { expectedVersion: doc._version })` → fails with `VERSION_CONFLICT` if it changed
  (`expectedVersion: 0` = "only if it does not exist yet").
- **No live updates.** If others' changes should appear, re-load every 10–15 seconds
  (`setInterval(reload, 15000)`) and after each own write. Don't re-render a form while the user is typing in it.

### Errors

Rejected promises are `Error`s with a `code`:

| code | meaning |
|---|---|
| `NOT_IN_SHITCLOUD` | the app was opened outside ShitCloud (see below) |
| `BAD_REQUEST` | invalid filter, name, id or data (`message` says what) |
| `VERSION_CONFLICT` | `expectedVersion` did not match |
| `CONFLICT` | too many simultaneous writes to one document — retry |
| `TOO_LARGE` | document over 256 KB |
| `SIGNED_OUT` | the user's session ended |
| `TIMEOUT`, `UNAVAILABLE` | ShitCloud did not answer — retry |

Show `error.message` to the user (a small toast is enough); never fail silently.

### Who may do what

Everyone who can open the app can read and change all of its data — there are no per-user permissions.
`me()` is for showing names and keying "my" documents, not for security. Don't store secrets or anything
that must stay hidden from colleagues.

## Security inside the app

Data comes from other people. **Escape every value before putting it into HTML** — use `textContent`, or an
escape helper with `innerHTML`:

```js
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);
```

## Optional: also work outside ShitCloud

Opened as a plain file, or in a browser tab outside ShitCloud, `shitcloud` is missing or every call rejects with
`NOT_IN_SHITCLOUD`. If the user wants the file to work on its own too, pick the storage at startup and keep the
rest of the code identical — write a small object with the same `find / set / add / remove` signatures over
`localStorage` (namespaced key, e.g. `'<app-name>:data'`), and show a banner that data is only in this browser:

```js
let db, me;
try {
  me = await window.shitcloud.me();
  db = window.shitcloud.db;
} catch (e) {
  me = { id: 'local', displayName: '' };
  db = localDb('my-app');        // same interface, localStorage
  showLocalModeBanner();
}
```

## Template

```html
<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>My App</title>
  <style>
    body { font-family: system-ui, sans-serif; margin: 24px; }
  </style>
</head>
<body>
  <h1>My App</h1>
  <p id="error" style="color:#c00"></p>
  <ul id="list"></ul>
  <script>
    const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[c]);

    async function load() {
      try {
        const items = await shitcloud.db.find('items', null, { sort: { _updatedAt: -1 } });
        document.getElementById('list').innerHTML =
          items.map(i => `<li>${esc(i.title)} <small>${esc(i._updatedBy)}</small></li>`).join('');
        document.getElementById('error').textContent = '';
      } catch (e) {
        document.getElementById('error').textContent = e.message;
      }
    }

    load();
    setInterval(load, 15000);
  </script>
</body>
</html>
```

## Before you hand the file over, check

- [ ] One `.html` file; no `http(s)://` URLs to scripts, styles, fonts or images; libraries only from `/_lib/`.
- [ ] No `fetch` / `XMLHttpRequest` / `WebSocket`; no `<form action>` submits.
- [ ] All data through `shitcloud.db`, every call awaited, errors shown.
- [ ] One document per independently edited thing; dates as ISO strings; nothing over 1000 documents per
      query without paging.
- [ ] Every value from data escaped before going into HTML.
- [ ] Reloads data periodically if several people use it at once.

## Deploying (for the person, not the AI)

In ShitCloud: **Upload** → choose the file → name it → **Deploy**. The name decides the app's address and its
data: deploy a new version **under the same name** to keep the data. Earlier versions are kept for 90 days.
