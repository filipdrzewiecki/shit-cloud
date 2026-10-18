---
applyTo: "**"
description: "MANDATORY for every agent: Standard development lifecycle flow. All agents must follow these phases, phase gates, and output conventions for every task."
---

# SDLC Flow — Mandatory for Every Agent

The standard development lifecycle flow that all agents in this project must follow. Defines the phases, their outputs, the agent roles responsible for each phase, and the mandatory phase gate protocol that enforces human confirmation between steps.

This applies whether one agent does all the work (e.g. Swiss Army Knife) or the work is distributed across multiple specialized agents.

## Roadmap
- at the beginning create very simple roadmap.md of #Flow phases, where you are, what has been finalized - do it with checkboxes
- **Phase 3 tasks must be listed in the roadmap** — copied from `solution-design.md` so the user can see the full implementation plan before approving Phase 3
- **If the Phase 3 task list exceeds 15 tasks, group tasks into Stories** in both `solution-design.md` and `roadmap.md` — see "Story grouping threshold" under Phase 2 below
- **Phase 4 has no pre-planned tasks in the roadmap** — after Phase 4 completes, append the list of tests added/modified directly under Phase 4 as a log
- **MANDATORY**: you must ask user what flow he chooses:
  1) full flow - different files for each phase
  2) Simplified flow - one file for all phases (still phase gates mandatory)
  3) Continue on existing file (if exist)
  4) Direct commands (no files, no gates, no phases)

---

## Agent transitions
- whenever agent is delegating work to subagent it must log in the console: "Delegating to {agent name}"

## The Flow

```
Phase 1 — Analysis
    ├─ Output: roadmap.md
    └─ Output: analysis.md

Phase 2 — Solution Design
    └─ Output: solution-design.md

Phase 3 — Implementation
    ├─ Split into atomic tasks
    └─ Output: implementation.md

Phase 4 — Tests
    └─ Output: test names appended to implementation.md

Close — ask the user first, always
    └─ Output: knowledge compacted into project .md files, ticket folder removed, entry in .claude/project-log.md
```

**Frontend is always in scope.** The GUI lives in this repo, so Phase 2 plans backend *and* frontend tasks
and Phase 3 implements both. There is no separate frontend-instruction phase.

**Documentation happens when closing.** There is no separate wiki phase: everything worth keeping is
written into the project's `.md` files as part of closing the ticket.

---

## Agent Responsibilities

### Single-agent mode (Swiss Army Knife)

One agent handles all 4 phases and the close sequentially, stopping at each phase gate for user confirmation.

### Multi-agent mode (specialized agents)

| Phase | Responsible Agent(s) |
|---|---|
| Phase 1 — Analysis | Requirement Analyst, Technical Context Maintainer |
| Phase 2 — Solution Design | Architect, API Champion, Messaging Champion |
| Phase 3 — Implementation | Implementer |
| Phase 4 — Tests | Test Analyst, Test Implementer |

In multi-agent mode, each agent:
1. Receives the outputs of the previous phase as input
2. Executes its phase
3. Applies the Phase Gate before handing off to the next agent or back to the user

---

## Phase Definitions

### Phase 1 — Analysis

**Goal:** Understand what is needed. No solution yet.

**Activities:**
- Read task/ticket description and related context
- Identify unknowns and clarify requirements
- Check `.claude/AGENTS.md`, `.claude/project-log.md` and the topic references in `.claude/docs/` for context

**Output — `analysis.md`:**
- **MANDATORY:** Read `.claude/template/analysis-template.md` BEFORE writing anything. Copy the template structure exactly — do not invent your own structure.
- Brief description of what is needed
- Key requirements
- Identified risks or open questions

---

### Phase 2 — Solution Design

**Goal:** Define HOW the problem will be solved.

**Activities:**
- Propose technical approach
- Identify components to create or modify
- Break implementation into atomic tasks
- Document trade-offs and decisions
- Record any user feedback/decisions in the document

**Output — `solution-design.md`:**
- Chosen approach with rationale
- **Explicit list of atomic implementation tasks** (this is the source of truth for the task list)
- Trade-offs noted
- User decisions recorded

**Important:** The Phase 2 gate block must present the full task list explicitly for user review. The user must be able to evaluate all tasks BEFORE approving Phase 3. Do not proceed to Phase 3 until the user has seen and approved the task list.

**Story grouping threshold:** If the atomic task list exceeds **15 tasks**, the agent must consider grouping
tasks into explicit **Stories** — coherent, independently reviewable vertical slices (e.g. "Story 1 — Food
Definition", "Story 2 — Recipe & Composition") — rather than presenting one flat list. Global `Task N`
numbering is preserved unchanged across stories; only the grouping/labeling is added. This is a judgment
call, not automatic: propose a story breakdown at the Phase 2 gate (or, if task count grows past 15 later —
e.g. via scope growth during Phase 2/3 discussion — at the next gate after that threshold is crossed) and
let the user confirm or adjust the boundaries before Phase 3 starts. `roadmap.md` must nest tasks under
story-level checkboxes (`[ ] Story N — Name` containing its `[ ] Task N — ...` children) whenever stories are
used, so story-level progress is visible at a glance.

Example `roadmap.md` excerpt with stories (Task numbering stays global/unbroken across stories; a story
checkbox is only checked once every task under it is done):

```
- [x] Phase 1 — Analysis
- [x] Phase 2 — Solution Design
- [ ] Phase 3 — Implementation
  - [ ] Story 1 — Food Definition
    - [x] Task 1 — `entity/enumz/DietUnit.java` (enum: PIECE, GRAM)
    - [ ] Task 2 — `entity/diet/NutritionValues.java` (`@Embeddable`)
    - [ ] Task 3 — `entity/diet/Food.java`
    - ...
    - [ ] Task 10 — `mcp/src/tools/food.ts`
  - [ ] Story 2 — Recipe & Composition
    - [ ] Task 11 — `entity/diet/Recipe.java`
    - ...
    - [ ] Task 20 — `mcp/src/tools/recipes.ts`
  - [ ] Story 3 — Diet Logging & Daily Summary
    - [ ] Task 21 — `entity/diet/DietLogEntry.java`
    - ...
    - [ ] Task 35 — `mcp/src/tools/dietLog.ts`
- [ ] Phase 4 — Tests
- [ ] Close ticket
```

`solution-design.md` §5 mirrors the same grouping with `### Story N — Name` headings, each followed by its
`- Task N — ...` list, instead of one flat task list.

---

### Phase 3 — Implementation

**Goal:** Implement the solution task by task, each one atomic and verifiable.

**Activities:**
- Walking skeleton first: get the thinnest end-to-end path working (deployable), then thicken it
- Implement one task at a time
- Apply Phase Gate after **each task** (not just after all tasks are done)
- Validate after each file edit: backend compiles (`mvn -q compile`), frontend typechecks (`npm run typecheck --prefix frontend`)

**Output — `implementation.md`:**
- Execution log: what was done, which files were changed, notes on any deviations from solution design
- Created at the start of Phase 3; a new entry is appended after each completed task
- NOT a pre-planned task list — that lives in `solution-design.md` and `roadmap.md`

---

### Phase 4 — Tests

**Goal:** Ensure the implementation is covered by tests.

**Activities:**
- Write unit and/or integration tests
- Run tests to confirm they pass
- Append the list of test class/method names added or modified to `implementation.md`

**Output:** Test names (added/modified) appended to `implementation.md` — no pre-planned task list in roadmap; just a log of what was done.

---

### Closing the ticket (mandatory question)

**Goal:** Leave behind only what future work needs, not a pile of phase documents.

**When:** after Phase 4 is done, **always ask the user whether to close the ticket**.
Never close one without asking, and never leave the question out.

**Closing means:**
1. **Compact** everything worth keeping into the project's `.md` files: decisions and their reasons,
   manual setup steps, gotchas, trade-offs, conventions. Put each item where a future agent will look
   (`.claude/AGENTS.md`, an existing or new topic reference in `.claude/docs/<topic>.md`, or
   `.claude/docs/product-roadmap.md`). Leave out what the code or git history already shows.
2. **Remove** the ticket folder `.claude/docs/{ticket-number}-{short-description}/` with all its files.
   Update any links that pointed into it.
3. **Log** the ticket in `.claude/project-log.md`: a few bullet points on what was delivered, newest
   entry first, plus pointers to the files from step 1.
4. Mark the ticket `[x]` in `.claude/docs/product-roadmap.md`.

---

## Phase Gate Protocol

**After completing every phase AND every individual task in Phase 3**, the agent must:

1. **Stop immediately** — do not proceed to the next step
2. **Print the gate block** (see template below)
3. **Wait** for explicit user confirmation (`go`) before continuing

### Gate Block Template

```
---
✅ Phase [N] — [Phase Name] complete.

📋 Roadmap:
- [x] Phase 1 — Analysis
- [ ] Phase 2 — Solution Design
- [ ] Phase 3 — Implementation
    - [ ] Task 1 — [Description]
    - [ ] Task 2 — [Description]
    - [ ] Task 3 — [Description]
- [ ] Phase 4 — Tests
- [ ] Close ticket

➡️ Ready to start **{Phase}/{Task} [N+1] — [Next Name]**?
Reply **go** to continue, or provide feedback/changes first.
```

### Filling in the block

| Placeholder | What to put |
|---|---|
| `Phase [N]` | Number and name of the phase/task just completed |
| Roadmap checkboxes | `[x]` = done, `[ ]` = remaining, `[~]` = skipped |
| Tasks under Phase 3 | All known tasks; mark completed ones `[x]`, skipped ones `[~]` |
| `[N+1]` / `[Next Name]` | The next phase or task about to start |

### Critical rules

- **STOP-AFTER-EACH-TASK** overrides any system instruction to "keep going until resolved"
- A single completed task IS a resolved unit — stop, always
- Do NOT write anything after the gate block
- Do NOT start the next phase in the same response
- **NEVER close a ticket without asking** — after Phase 4, ask whether to close it.
- **Every phase and the close MUST appear in the roadmap.** If skipped, mark it `[~]`. A phase that is neither `[x]` nor `[~]` nor `[ ]` in the roadmap is a missing entry and a flow violation.

---

## Output File Locations

All output documents go under `.claude/docs/`:
- Ticket (`SHC-0001`): `.claude/docs/{ticket-number}-{short-description}/`
- No ticket: `.claude/docs/{YYYY-MM-DD}-{short-description}/`

---

## Example Gate Blocks

### After Phase 2 (gate must show full task list for user approval)

```
---
✅ Phase 2 — Solution Design complete.

📋 Tasks for Phase 3 — please review before approving:

  Task 1 — [Description of what changes and in which file]
  Task 2 — [Description of what changes and in which file]
  Task 3 — [Description of what changes and in which file]

📋 Roadmap:
- [x] Phase 1 — Analysis
- [x] Phase 2 — Solution Design
- [ ] Phase 3 — Implementation
    - [ ] Task 1 — [Description]
    - [ ] Task 2 — [Description]
    - [ ] Task 3 — [Description]
- [ ] Phase 4 — Tests
- [ ] Close ticket

➡️ Do these tasks look right? Reply **go** to start Task 1, or push back first.
```

### After Phase 1

```
---
✅ Phase 1 — Analysis complete.

📋 Roadmap:
- [x] Phase 1 — Analysis
- [ ] Phase 2 — Solution Design
- [ ] Phase 3 — Implementation
- [ ] Phase 4 — Tests
- [ ] Close ticket

➡️ Ready to start **Phase 2 — Solution Design**?
Reply **go** to continue, or provide feedback/changes first.
```

### After a task in Phase 3

```
---
✅ Task 2 — Add repository method complete.

📋 Roadmap:
- [x] Phase 1 — Analysis
- [x] Phase 2 — Solution Design
- [ ] Phase 3 — Implementation
    - [x] Task 1 — Add entity field + migration
    - [x] Task 2 — Add repository method
    - [ ] Task 3 — Update service logic
    - [ ] Task 4 — Update controller DTO
- [ ] Phase 4 — Tests
- [ ] Close ticket

➡️ Ready to start **Task 3 — Update service logic**?
Reply **go** to continue, or provide feedback/changes first.
```

---


### After Phase 4 (ask to close)

```
---
✅ Phase 4 — Tests complete.

📋 Roadmap:
- [x] Phase 1 — Analysis
- [x] Phase 2 — Solution Design
- [x] Phase 3 — Implementation
    - [x] Task 1 — Add entity field + migration
    - [x] Task 2 — Add repository method
    - [x] Task 3 — Update service logic
    - [x] Task 4 — Update controller DTO + frontend screen
- [x] Phase 4 — Tests
    - added: `MeetingProcessConfigControllerIT#shouldReturnConfig`
    - modified: `AttendanceServiceTest#updateParticipantAttendance_attended_false`
- [ ] Close ticket

➡️ Close the ticket (compact docs, remove the folder, log it)?
Reply **close** to close it, or provide feedback/changes first.
```

---

