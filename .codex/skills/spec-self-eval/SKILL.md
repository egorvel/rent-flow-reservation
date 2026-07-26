---
name: spec-self-eval
description: >-
  Evaluate .specs/<feature>/{requirements,design,tasks}.md against the project-wide checklist at
  .specs/_eval-checklist.md and write a dated PASS / FAIL / WEAK report with paragraph-length,
  evidence-led reasoning. Use when the user runs /spec-self-eval, asks to self-evaluate or
  validate a feature spec, or after editing any of the three spec files. Reports are saved to
  .specs/<feature>/eval-reports/eval-report-<YYYY-MM-DD-HHMM>.md.
metadata:
  version: "1.1"
---

# spec-self-eval

Apply the project's spec checklist to one feature's `requirements.md`, `design.md`, and `tasks.md`, and write a verdict report whose claims a reader can grep and verify.

This is a **rigorous** review. The default verdict on thin evidence is **WEAK**, not PASS. A report that grants every check PASS with one hand-wavy sentence is a failed report — quoted evidence per check is the bar.

## When to use

- The user runs `/spec-self-eval` or `/spec-self-eval <feature>`.
- The user says: "self-evaluate the spec for X", "validate the X spec", "run the spec checklist against X", "check the specs for X".
- Before declaring a spec ready to hand to implementers, or after editing any of the three spec files.

## Inputs

- **`<feature>`** (required): folder name under `.specs/` (e.g. `query-api`). If the user omits it, list directories under `.specs/` (skip files whose name starts with `_`) and ask which one. Never guess.
- **`.specs/_eval-checklist.md`** is the **source of truth** for which checks to apply. Read it and use its items verbatim. Only fall back to the "Checklist (fallback)" section below if that file cannot be read (missing, unreadable, or empty).

## Style reference

If `.specs/<feature>/eval-reports/` already contains prior reports, read the most recent one and match its format. Otherwise the format is:

- Header: `Date: <YYYY-MM-DD-HHMM>`, `Evaluated: .../{requirements,design,tasks}.md (state at HEAD of <branch>).`
- Summary table: `| # | Check | Verdict |`, verdicts as plain `PASS` / `WEAK` / `**FAIL**` (only FAIL is bold; never use brackets like `[PASS]`).
- Per-check sections: `## N. <check title> — **<VERDICT>**` with em-dash separator (`—`, U+2014, not a hyphen).
- Paragraph reasoning (typically 2–6 sentences) with **direct quoted phrases** from the spec, AC ids, and `§X.Y` refs. Tables are welcome for trace coverage (the AC → design-§ check).
- Final `## Summary` paragraph naming blockers first, then notable non-blockers.

No "Overall verdict" line. No emoji. No bracketed verdicts.

## Workflow

### 1. Verify inputs

Confirm these files exist; if any are missing, stop and tell the user which (but see §Inputs for the `_eval-checklist.md` fallback):

- `.specs/_eval-checklist.md`
- `.specs/<feature>/requirements.md`
- `.specs/<feature>/design.md`
- `.specs/<feature>/tasks.md`

Capture the current branch with `git rev-parse --abbrev-ref HEAD` for the `Evaluated:` line.

### 2. Read all four files in full

Read each completely. Do not skim. For long files, page through them — partial reads invalidate every coverage and cross-reference check below.

### 3. Build working indices

Before assigning any verdict, extract:

- **AC index** — every AC id and its full text from `requirements.md`, grouped by user story.
- **Design heading index** — every heading in `design.md` with its `§` id and level.
- **Task index** — every task id with its `Refs.` and `DoD.` blocks.
- **Cross-reference list** — every `§X.Y`, `AC...`, "Section N", and `AGENTS.md §...` mentioned in `tasks.md` and `design.md`.

These indices feed the per-check evaluations. Without them, coverage and reference verdicts are guesses.

### 4. Evaluate each checklist item

For each item in `.specs/_eval-checklist.md` (or, only if that file cannot be read, each item in "Checklist (fallback)" below):

- **PASS** — clearly satisfied, with quotable evidence.
- **WEAK** — partially satisfied or borderline. The reasoning must end with one sentence on what would lift it to PASS.
- **FAIL** — clearly violated. The reasoning must quote the offending line and propose the smallest fix.

The procedures in "Per-check procedures" below cover the nine items currently in the checklist; apply them whenever the checklist names one of those items. For any additional checklist item not covered, apply the same spirit: enumerate the relevant evidence, quote it, and judge.

### 5. Compose the report

Use the template in the next section. Match the style guide above: terse, evidence-led, no fluff, no recap of what the checklist says. Quote spec lines verbatim — readers should be able to grep for your evidence.

### 6. Write the file

Get the local timestamp:

```bash
date +%Y-%m-%d-%H%M
```

Report path:

```
.specs/<feature>/eval-reports/eval-report-<YYYY-MM-DD-HHMM>.md
```

Create the `eval-reports/` directory if it does not exist. Write the report with the `Write` tool. Never overwrite an existing report — if the path already exists (same-minute collision), bump the suffix by one minute and try again.

### 7. Report back

Reply to the user in one or two short sentences:

```
N PASS / N WEAK / N FAIL — .specs/<feature>/eval-reports/eval-report-<YYYY-MM-DD-HHMM>.md
```

If any check is FAIL, name the blocker in a second short sentence.

## Report template

````markdown
# Specs self-evaluation — <feature>

Date: <YYYY-MM-DD-HHMM>
Evaluated: `.specs/<feature>/{requirements,design,tasks}.md` (state at HEAD of `<branch>`).

| # | Check | Verdict |
| - | ----- | ------- |
| 1 | <check 1 title> | PASS |
| 2 | <check 2 title> | PASS |
| … | … | … |
| N | <check N title> | **FAIL** |

---

## 1. <check 1 title> — **PASS**

<2–6 sentences with quoted evidence. If WEAK, close with one sentence on what would lift it to PASS.>

## 2. <check 2 title> — **PASS**

<...>

## … AC → design coverage check — **PASS**

Sample trace:

| AC | Design section |
| -- | -------------- |
| AC1.1 | §1.1 (resource path) |
| AC1.2–1.6 | §1.2 (query params) |
| … | … |

No orphan ACs.

## … AC → task DoD coverage check — **PASS**

Spot-checks: AC1.10 → T6 *"<quoted DoD bullet>"*; AC2.1/AC2.2 → T4 *"<quoted DoD bullet>"*; … Every AC has an analogous test bullet in T1–TN's DoD.

## … cross-references check — **FAIL**

<Quote the broken citation. Name the missing target. Suggest the smallest fix.>

## … EARS form check — **PASS**

- Ubiquitous: AC..., AC..., …
- Event-driven: AC..., AC..., …
- Unwanted: AC..., AC..., …

<...>

---

## Summary

<One paragraph. Blockers first ("One blocking issue: …"), then notable non-blockers.>
````

The row count and titles in the table mirror whatever the checklist actually contains; the surrounding format stays.

## Per-check procedures

The procedures below cover the nine items currently in `.specs/_eval-checklist.md`. If the checklist is reworded, match by intent.

### Each AC is testable

For each AC, ask: can a server-side observation (HTTP response, DB state, log line, exit code) decide whether this AC holds? Claims about *client* behavior, about absence of knowledge, or about "the system shall not allow" that have no observable surface are usually WEAK. Name any AC that does not pass this test.

### Tasks have refs and DoD

For each task, verify both a `Refs.` line (citing AC ids and design §s) and a `DoD.` line with verifiable bullets (e.g. `mvn verify` green, specific assertions, files exist). A task whose DoD is woolly ("works correctly", "tested") is WEAK even if `Refs.` is present.

### Dependencies between tasks are explicit

`tasks.md` must make order/dependencies legible — a Mermaid graph, prose ("T2 and T3 are independent; T5 depends on T3 and T4"), or per-task "depends on Tn" lines. Implicit ordering by document position alone is WEAK.

### Every AC in requirements.md is addressed by at least one section in design.md

Walk every AC from your AC index. For each, find at least one design section that addresses it. Build a short trace table (AC → §) for the report — abbreviation by group is fine (`AC1.2–1.6 → §1.2 (query params)`). Any orphan AC is FAIL.

### Every AC traces to at least one task whose DoD would fail if the AC regressed

Walk every AC. For each, find at least one task whose DoD bullet would visibly fail if the AC's behavior regressed. A task that *mentions* the AC area but whose DoD would still pass on regression does not count. Quote specific DoD bullets in the report (a few spot-checks plus an "every AC has an analogous bullet" closer is the canonical style).

### No contradictions between requirements.md, design.md, and tasks.md

Look for same-fact disagreements across the three files: differing versions, table names, timestamp precision, error codes, response field names, HTTP statuses, library versions. List every drift. A version pinned in one file but loose in another is a drift.

### Cross-references resolve

For every entry in your cross-reference list, verify the cited target exists in the cited file *and* its content matches what the citation claims. A `§` id that exists but whose content is unrelated is unresolved. **If any ref is broken, this check is FAIL** — quote the citation, name the missing target, and suggest the smallest fix.

### Every AC is in EARS form

Every AC should carry an EARS label and match the label's grammar:

- **Ubiquitous**: "The <system> shall ..."
- **Event-driven**: "When <event>, the <system> shall ..."
- **Unwanted**: "If <condition>, then the <system> shall ..."
- **State-driven**: "While <state>, the <system> shall ..."
- **Optional**: "Where <feature>, the <system> shall ..."

Group the ACs by class in the reasoning. An AC labeled Ubiquitous whose prose starts "When ..." is a label/grammar mismatch and counts toward FAIL for this check.

### Open questions are resolved

`requirements.md` should not carry a live `## Open questions` section once decisions have been made. The resolution itself lives in `design.md` or `tasks.md`; `requirements.md` then renames the section to `## Resolved questions` and inlines each resolution with a pointer to the file where the decision is justified (e.g. *"Resolution. … See `design.md` §2.3."*). A live `## Open questions` heading with one or more outstanding items is FAIL — quote the items and propose moving each resolution into `design.md`/`tasks.md`. A `## Resolved questions` section whose items lack an inlined resolution or a pointer is WEAK.

## Checklist (fallback)

Use this list **only** if `.specs/_eval-checklist.md` cannot be read. Otherwise the in-tree file wins.

1. Each AC is testable.
2. Tasks have refs and DoD.
3. Dependencies between tasks are explicit.
4. Every AC in `requirements.md` is addressed by at least one section in `design.md`.
5. Every AC traces to at least one task whose DoD would fail if the AC regressed.
6. No contradictions between `requirements.md`, `design.md`, and `tasks.md` (same fact, same answer everywhere).
7. Cross-references resolve: every `§X.Y`, AC id, or "see Section ..." points to a real section that says what is claimed.
8. Every AC is in EARS form (Ubiquitous / Event-driven / Unwanted / State-driven / Optional).
9. Open questions are resolved in `design.md` / `tasks.md`; `requirements.md` renames `## Open questions` → `## Resolved questions` with each resolution inlined and a pointer to the file where the decision is justified.

## Anti-rubber-stamp rules

- **PASS requires quotable evidence.** A check whose reasoning has no quotes, AC ids, or `§` refs is not PASS — it is WEAK.
- **You must do the walks.** If you did not actually enumerate every AC (coverage checks) or extract every cross-reference (xref check), those checks are WEAK by default — you cannot honestly grant PASS to a check you did not perform.
- **Default to WEAK on doubt.** Borderline evidence is WEAK. Do not reach for PASS to be friendly.
- **Read-only.** Do not modify `requirements.md`, `design.md`, `tasks.md`, or the checklist. The report names issues; fixes are the user's call in a separate turn.
- **Never overwrite a prior report.** The timestamp suffix exists so multiple runs on the same day coexist.

## Example invocation

> **User:** `/spec-self-eval query-api`
>
> **Skill:**
> 1. Verifies `requirements.md`, `design.md`, `tasks.md`, and the checklist exist; captures current branch.
> 2. Reads all four files in full; extracts AC, design-§, task, and xref indices.
> 3. Evaluates each checklist item with the per-check procedures.
> 4. Writes `.specs/query-api/eval-reports/eval-report-2026-05-16-1430.md` in the template format.
> 5. Replies: `7 PASS / 1 WEAK / 0 FAIL — .specs/query-api/eval-reports/eval-report-2026-05-16-1430.md`.

## Keeping copies in sync

This skill is mirrored at `.claude/skills/spec-self-eval/SKILL.md` and `.codex/skills/spec-self-eval/SKILL.md`. When updating one, update the other so both agent environments behave identically.
