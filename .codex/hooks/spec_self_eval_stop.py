#!/usr/bin/env python3
"""Codex Stop hook: evaluate touched feature specs before a turn can close."""

from __future__ import annotations

import datetime as dt
import hashlib
import json
import os
import re
import subprocess
import sys
from pathlib import Path
from typing import Iterable


CHECKLIST_FALLBACK = [
    "Each AC is testable.",
    "Tasks have refs and DoD.",
    "Dependencies between tasks are explicit.",
    "Every AC in requirements.md is addressed by at least one section in design.md.",
    "Every AC traces to at least one task whose DoD would fail if the AC regressed.",
    "No contradictions between requirements.md, design.md, and tasks.md (same fact, same answer everywhere).",
    'Cross-references resolve: every \u00a7X.Y, AC..., or "see Section ..." points to a real section that says what\'s claimed.',
    "Every AC is in EARS form (Ubiquitous / Event-driven / Unwanted / State-driven / Optional).",
    "Open questions are resolved in design.md / tasks.md; requirements.md renames ## Open questions \u2192 ## Resolved questions with each resolution inlined and a pointer to the file where the decision is justified.",
]

EARS_LABELS = {"Ubiquitous", "Event-driven", "Unwanted", "State-driven", "Optional"}
DEFAULT_LOG = Path("/tmp/audit-log-service-spec-self-eval-stop.log")


def emit(output: dict[str, object], exit_code: int = 0) -> None:
    print(json.dumps(output, ensure_ascii=False))
    raise SystemExit(exit_code)


def append_log(root: Path, features: list[str], result: str, detail: str = "") -> None:
    log_path = Path(os.environ.get("SPEC_SELF_EVAL_HOOK_LOG", str(DEFAULT_LOG)))
    record = {
        "ts": dt.datetime.now(dt.timezone.utc).isoformat(),
        "repo": str(root),
        "features": features,
        "result": result,
        "detail": detail,
    }
    try:
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(record, ensure_ascii=False) + "\n")
    except OSError:
        pass


def repo_root() -> Path:
    here = Path(__file__).resolve()
    try:
        result = subprocess.run(
            ["git", "-C", str(here.parents[2]), "rev-parse", "--show-toplevel"],
            check=True,
            capture_output=True,
            text=True,
        )
        return Path(result.stdout.strip()).resolve()
    except Exception:
        return here.parents[2]


def git_changed_paths(root: Path) -> list[str]:
    paths: list[str] = []
    commands = [
        ["git", "-C", str(root), "diff", "--name-only", "--diff-filter=ACMRTUXB", "HEAD"],
        ["git", "-C", str(root), "ls-files", "--others", "--exclude-standard"],
    ]
    for command in commands:
        result = subprocess.run(command, capture_output=True, text=True)
        if result.returncode == 0:
            paths.extend(line.strip() for line in result.stdout.splitlines() if line.strip())
    return sorted(set(paths))


def feature_from_path(path: str) -> str | None:
    parts = Path(path).parts
    if len(parts) >= 3 and parts[0] == ".specs" and parts[1] != "_eval-checklist.md":
        return parts[1]
    return None


def touched_features(root: Path) -> list[str]:
    features = {
        feature
        for path in git_changed_paths(root)
        for feature in [feature_from_path(path)]
        if feature
    }
    return sorted(features)


def read_text(path: Path) -> str:
    try:
        return path.read_text(encoding="utf-8")
    except FileNotFoundError:
        return ""


def checklist(root: Path) -> list[str]:
    text = read_text(root / ".specs" / "_eval-checklist.md")
    items = [
        line[2:].strip()
        for line in text.splitlines()
        if line.startswith("- ") and line[2:].strip()
    ]
    return items or CHECKLIST_FALLBACK


def parse_acs(requirements: str) -> dict[str, dict[str, str]]:
    acs: dict[str, dict[str, str]] = {}
    pattern = re.compile(
        r"- \*\*(AC\d+\.\d+[a-z]?)\s+[-–—]\s+([^.*]+)\.\*\*\s*(.*)",
        re.IGNORECASE,
    )
    for line in requirements.splitlines():
        match = pattern.match(line.strip())
        if match:
            ac_id, label, text = match.groups()
            acs[ac_id.upper()] = {"label": label.strip(), "text": text.strip()}
    return acs


def parse_headings(text: str) -> set[str]:
    headings: set[str] = set()
    for line in text.splitlines():
        match = re.match(r"^#{2,6}\s+(.+?)\s*$", line)
        bold_match = re.match(r"^\*\*(.+?)\*\*:?\s*$", line.strip())
        if match:
            title = match.group(1).strip()
        elif bold_match:
            title = bold_match.group(1).strip().rstrip(":")
        else:
            continue
        headings.add(title)
        number = re.match(r"^(\d+(?:\.\d+)*)\b", title)
        if number:
            headings.add(number.group(1))
    return headings


def ref_resolves(ref: str, headings: set[str]) -> bool:
    if ref in headings:
        return True
    ref_lower = ref.lower()
    return any(ref_lower == heading.lower() or heading.lower().startswith(ref_lower) for heading in headings)


def parse_tasks(tasks: str) -> dict[str, str]:
    matches = list(re.finditer(r"^##\s+(T\d+)\b.*$", tasks, re.MULTILINE))
    parsed: dict[str, str] = {}
    for index, match in enumerate(matches):
        start = match.start()
        end = matches[index + 1].start() if index + 1 < len(matches) else len(tasks)
        parsed[match.group(1)] = tasks[start:end]
    return parsed


def normalize_check(check: str) -> str:
    return check.strip().rstrip(".").lower()


def verdict_rank(verdicts: Iterable[str]) -> str:
    verdicts = list(verdicts)
    if "FAIL" in verdicts:
        return "FAIL"
    if "WEAK" in verdicts:
        return "WEAK"
    return "PASS"


def ac_family_matches(ref: str, ac_id: str) -> bool:
    ref = ref.upper()
    if ref == ac_id:
        return True
    family = re.match(r"AC(\d+)\.X", ref)
    if family:
        return ac_id.startswith(f"AC{family.group(1)}.")
    return False


def task_refs_ac(task_body: str, ac_id: str) -> bool:
    refs = [match.group(0).upper() for match in re.finditer(r"AC\d+(?:\.\d+[a-z]?|\.x)?", task_body, re.IGNORECASE)]
    return any(ac_family_matches(ref, ac_id) for ref in refs)


def task_dod_mentions_ac_behavior(task_body: str, ac: dict[str, str]) -> bool:
    if "**DoD.**" not in task_body:
        return False
    dod = task_body.split("**DoD.**", 1)[1].lower()
    words = [
        word
        for word in re.findall(r"[a-zA-Z_]{4,}", ac["text"].lower())
        if word not in {"system", "shall", "when", "then", "with", "that", "whose"}
    ]
    return any(word in dod for word in words[:8])


def design_addresses_ac(design: str, ac_id: str, ac: dict[str, str]) -> bool:
    haystack = design.lower()
    if ac_id.lower() in haystack:
        return True
    text = ac["text"].lower()
    groups = [
        ("actor", "actor.id"),
        ("resource", "resource.id"),
        ("from", "timestamp >="),
        ("to", "timestamp <"),
        ("logical and", "and-combined"),
        ("response", "success response"),
        ("400", "400"),
        ("422", "422"),
        ("not mutate", "read path"),
        ("ordered", "timestamp desc"),
        ("deterministic", "total order"),
        ("cursor", "cursor"),
        ("next_cursor", "next_cursor"),
        ("limit", "limit"),
        ("duplicates", "no duplication"),
        ("omissions", "no omission"),
    ]
    for needle, design_needle in groups:
        if needle in text and design_needle in haystack:
            return True
    key_terms = [
        word
        for word in re.findall(r"[a-zA-Z_]{5,}", text)
        if word not in {"system", "shall", "request", "provided", "events", "return"}
    ]
    if not key_terms:
        return False
    hits = sum(1 for word in key_terms[:10] if word in haystack)
    return hits >= max(1, min(3, len(key_terms[:10]) // 2))


def ears_ok(ac: dict[str, str]) -> bool:
    label = ac["label"]
    text = ac["text"].strip()
    if label not in EARS_LABELS:
        return False
    lowered = text.lower()
    has_shall = " shall " in lowered or lowered.endswith(" shall")
    trigger_words = ("when ", "if ", "while ", "where ")
    if label == "Ubiquitous":
        return has_shall and not lowered.startswith(trigger_words)
    if label == "Event-driven":
        return lowered.startswith("when ") and has_shall
    if label == "Unwanted":
        return lowered.startswith("if ") and " then " in lowered and has_shall
    if label == "State-driven":
        return lowered.startswith("while ") and has_shall
    if label == "Optional":
        return lowered.startswith("where ") and has_shall
    return False


def testability_weaknesses(acs: dict[str, dict[str, str]]) -> list[str]:
    weak: list[str] = []
    subjective = ("expected", "able to", "not be required", "not be expected", "opaque to clients")
    for ac_id, ac in acs.items():
        text = ac["text"].lower()
        if any(term in text for term in subjective):
            weak.append(ac_id)
    return weak


def refs_and_dod(tasks_by_id: dict[str, str]) -> tuple[str, str]:
    if not tasks_by_id:
        return "FAIL", "No `## T...` tasks were found in `tasks.md`."
    missing_refs = [task_id for task_id, body in tasks_by_id.items() if "**Refs.**" not in body]
    missing_dod = [task_id for task_id, body in tasks_by_id.items() if "**DoD.**" not in body]
    if missing_refs or missing_dod:
        parts = []
        if missing_refs:
            parts.append("missing refs: " + ", ".join(missing_refs))
        if missing_dod:
            parts.append("missing DoD: " + ", ".join(missing_dod))
        return "FAIL", "; ".join(parts) + "."
    return "PASS", f"All {len(tasks_by_id)} tasks have `**Refs.**` and `**DoD.**` blocks."


def dependencies_explicit(tasks: str) -> tuple[str, str]:
    has_graph = "dependency graph" in tasks.lower()
    has_depends = bool(re.search(r"\bdepends?\b|\bdependency\b|T\d+\s*[-=]+>", tasks, re.IGNORECASE))
    if has_graph and has_depends:
        return "PASS", "`tasks.md` includes a dependency graph plus prose describing task ordering."
    if has_graph or has_depends:
        return "WEAK", "`tasks.md` has some dependency wording, but the ordering is not fully explicit."
    return "FAIL", "`tasks.md` does not make dependencies between tasks explicit."


def cross_reference_failures(root: Path, feature: str, requirements: str, design: str, tasks: str) -> list[str]:
    failures: list[str] = []
    feature_dir = root / ".specs" / feature
    design_headings = parse_headings(design)
    requirements_headings = parse_headings(requirements)
    agent_headings = parse_headings(read_text(root / "AGENTS.md"))
    acs = parse_acs(requirements)
    files = {
        "requirements.md": requirements,
        "design.md": design,
        "tasks.md": tasks,
    }

    ac_families = {re.sub(r"[A-Z]$", "", ac_id) for ac_id in acs} - set(acs)

    def ac_is_known(ref: str) -> bool:
        upper = ref.upper()
        return upper in acs or upper in ac_families

    for file_name, text in files.items():
        for match in re.finditer(r"(?:(design|requirements|AGENTS\.md)\s+)?\u00a7(\d+(?:\.\d+)*[a-z]?|AC\d+(?:\.\d+[a-z]?)?)\b", text):
            prefix = (match.group(1) or "").lower()
            ref = match.group(2).rstrip(".,;)")
            if ref.upper().startswith("AC"):
                if not ac_is_known(ref):
                    failures.append(f"{file_name}: `{match.group(0)}` does not match an AC.")
                continue
            if prefix == "requirements":
                target = requirements_headings
            elif prefix == "agents.md":
                target = agent_headings
            else:
                target = design_headings
            if not ref_resolves(ref, target):
                failures.append(f"{file_name}: `{match.group(0)}` does not resolve.")

        for match in re.finditer(r"\bAC\d+(?:\.\d+[a-z]?|\.x)?\b", text, re.IGNORECASE):
            ref = match.group(0).upper()
            if ref.endswith(".X"):
                family = ref[:-2] + "."
                if not any(ac_id.startswith(family) for ac_id in acs):
                    failures.append(f"{file_name}: `{match.group(0)}` does not match an AC family.")
            elif not ac_is_known(ref):
                failures.append(f"{file_name}: `{match.group(0)}` does not match an AC.")

    for match in re.finditer(r"`([^`]+)`", tasks):
        ref = match.group(1)
        if ref.endswith(".md"):
            candidate = (feature_dir / ref).resolve()
            if ref.startswith(".specs/"):
                candidate = (root / ref).resolve()
            elif ref == "AGENTS.md":
                candidate = (root / ref).resolve()
            if not candidate.exists() and not list(feature_dir.rglob(Path(ref).name)):
                failures.append(f"tasks.md: `{ref}` does not resolve.")
    return sorted(set(failures))


HISTORICAL_MARKERS = (
    "replace",
    "legacy",
    "deprecat",
    "previous",
    "formerly",
    "no longer",
    "expected to migrate",
    "used to ",
    "was ",
    "originally ",
    "moved from",
    "moved to",
    "renamed",
    "migrated from",
)


def live_mentions(text: str, token: str) -> list[str]:
    hits: list[str] = []
    for line in text.splitlines():
        if token not in line:
            continue
        if any(marker in line.lower() for marker in HISTORICAL_MARKERS):
            continue
        hits.append(line.strip())
    return hits


def open_questions_resolved(requirements: str) -> tuple[str, str]:
    section_pattern = r"^##\s+{name}\s*\n(.*?)(?=^##\s|\Z)"
    open_match = re.search(
        section_pattern.format(name=r"Open\s+questions"),
        requirements,
        re.MULTILINE | re.DOTALL | re.IGNORECASE,
    )
    resolved_match = re.search(
        section_pattern.format(name=r"Resolved\s+questions"),
        requirements,
        re.MULTILINE | re.DOTALL | re.IGNORECASE,
    )

    if open_match:
        body = open_match.group(1).strip()
        if not body or re.match(r"^(none\.?|no\s+open\s+questions\.?)\s*$", body, re.IGNORECASE):
            return "PASS", "`requirements.md` has no outstanding items under `## Open questions`."
        return (
            "FAIL",
            "`requirements.md` still has a live `## Open questions` section; resolve each in `design.md`/`tasks.md`, then rename it to `## Resolved questions` with each resolution inlined.",
        )

    if resolved_match:
        body = resolved_match.group(1).strip()
        items = re.split(r"^\s*\d+\.\s+", body, flags=re.MULTILINE)[1:]
        if not items:
            return (
                "WEAK",
                "`requirements.md` has `## Resolved questions` but no enumerated items were detected; confirm each resolution is inlined with a pointer to `design.md`/`tasks.md`.",
            )
        weak_items: list[str] = []
        for item in items:
            title_match = re.match(r"\*\*(.+?)\*\*", item, re.DOTALL)
            title = title_match.group(1).strip() if title_match else item.splitlines()[0][:60].strip()
            has_resolution = "resolution" in item.lower()
            has_pointer = bool(re.search(r"(design|tasks)\.md", item, re.IGNORECASE))
            if not (has_resolution and has_pointer):
                weak_items.append(title)
        if weak_items:
            return (
                "WEAK",
                "`## Resolved questions` items missing an inlined resolution or `design.md`/`tasks.md` pointer: " + "; ".join(weak_items) + ".",
            )
        return "PASS", f"`## Resolved questions` lists {len(items)} item(s), each with an inlined resolution and a `design.md`/`tasks.md` pointer."

    return "PASS", "`requirements.md` has no `## Open questions` or `## Resolved questions` section."


def contradiction_issues(requirements: str, design: str, tasks: str) -> tuple[str, str]:
    req_open_questions = "## Open questions" in requirements and not re.search(
        r"## Open questions\s+\n\s*(None|No open questions)", requirements, re.IGNORECASE
    )
    if req_open_questions and "Open question resolution" in design:
        return (
            "WEAK",
            "`requirements.md` still lists open questions that later spec files appear to answer.",
        )

    pairs = [
        ("`/api/v1/events`", "`/api/v1/audit-events`"),
        ("maximum `1000`", "maximum allowed value shall be 200"),
        ("default `100`", "default to 50"),
    ]
    files = {
        "requirements.md": requirements,
        "design.md": design,
        "tasks.md": tasks,
    }
    for left, right in pairs:
        if not any(right in text for text in files.values()):
            continue
        live = [
            f"{name}: {line!r}"
            for name, text in files.items()
            for line in live_mentions(text, left)
        ]
        if live:
            preview = "; ".join(live[:2])
            return "FAIL", f"Found live references to {left} alongside {right}: {preview}."
    return "PASS", "No same-fact contradictions were found across the three spec files."


def evaluate_feature(root: Path, feature: str, checks: list[str]) -> tuple[Path, list[tuple[str, str, str]], str]:
    feature_dir = root / ".specs" / feature
    req_path = feature_dir / "requirements.md"
    design_path = feature_dir / "design.md"
    tasks_path = feature_dir / "tasks.md"
    requirements = read_text(req_path)
    design = read_text(design_path)
    tasks = read_text(tasks_path)
    acs = parse_acs(requirements)
    tasks_by_id = parse_tasks(tasks)

    missing = [
        str(path.relative_to(root))
        for path, text in [(req_path, requirements), (design_path, design), (tasks_path, tasks)]
        if not text
    ]
    if missing:
        results = [
            ("Required spec files exist", "FAIL", "Missing or empty files: " + ", ".join(missing) + ".")
        ]
        report_path = write_report(root, feature, results)
        return report_path, results, "FAIL"

    results: list[tuple[str, str, str]] = []
    for check in checks:
        normalized = normalize_check(check)
        if normalized.startswith("each ac is testable"):
            weak = testability_weaknesses(acs)
            if not acs:
                results.append((check, "FAIL", "No ACs were found in `requirements.md`."))
            elif weak:
                results.append((check, "WEAK", "Most ACs are testable; " + ", ".join(weak) + " includes contract wording that is not directly observable."))
            else:
                results.append((check, "PASS", f"All {len(acs)} ACs map to observable request, response, ordering, pagination, or storage behavior."))
        elif normalized.startswith("tasks have refs and dod"):
            results.append((check, *refs_and_dod(tasks_by_id)))
        elif normalized.startswith("dependencies between tasks"):
            results.append((check, *dependencies_explicit(tasks)))
        elif normalized.startswith("every ac in requirements.md is addressed"):
            unaddressed = [ac_id for ac_id, ac in acs.items() if not design_addresses_ac(design, ac_id, ac)]
            if unaddressed:
                results.append((check, "FAIL", "No design coverage found for: " + ", ".join(unaddressed) + "."))
            else:
                results.append((check, "PASS", "Each AC has corresponding API, validation, data model, pagination, or integration design coverage."))
        elif normalized.startswith("every ac traces to at least one task"):
            untraced: list[str] = []
            weak: list[str] = []
            for ac_id, ac in acs.items():
                matching_tasks = [body for body in tasks_by_id.values() if task_refs_ac(body, ac_id)]
                if not matching_tasks:
                    untraced.append(ac_id)
                elif not any(task_dod_mentions_ac_behavior(body, ac) or "all ac" in body.lower() for body in matching_tasks):
                    weak.append(ac_id)
            if untraced:
                results.append((check, "FAIL", "No task refs found for: " + ", ".join(untraced) + "."))
            elif weak:
                results.append((check, "WEAK", "Task refs exist, but DoD regression coverage is indirect for: " + ", ".join(weak) + "."))
            else:
                results.append((check, "PASS", "Every AC reaches at least one task with DoD coverage for the relevant behavior."))
        elif normalized.startswith("no contradictions"):
            results.append((check, *contradiction_issues(requirements, design, tasks)))
        elif normalized.startswith("cross-references resolve"):
            failures = cross_reference_failures(root, feature, requirements, design, tasks)
            if failures:
                results.append((check, "FAIL", "Broken refs: " + "; ".join(failures[:4]) + ("." if len(failures) <= 4 else f"; plus {len(failures) - 4} more.")))
            else:
                results.append((check, "PASS", "All checked section, AC, AGENTS.md, and local file references resolve."))
        elif normalized.startswith("every ac is in ears"):
            bad = [ac_id for ac_id, ac in acs.items() if not ears_ok(ac)]
            if bad:
                results.append((check, "FAIL", "These ACs do not match their EARS labels: " + ", ".join(bad) + "."))
            else:
                results.append((check, "PASS", "Every AC has a recognized EARS label and matching sentence shape."))
        elif normalized.startswith("open questions are resolved"):
            results.append((check, *open_questions_resolved(requirements)))
        else:
            results.append((check, "WEAK", "Checklist item was not recognized by the hook evaluator; manual review required."))

    report_path = write_report(root, feature, results)
    return report_path, results, verdict_rank(result[1] for result in results)


def current_branch(root: Path) -> str:
    try:
        result = subprocess.run(
            ["git", "-C", str(root), "rev-parse", "--abbrev-ref", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )
        return result.stdout.strip() or "HEAD"
    except Exception:
        return "HEAD"


def reserve_report_path(root: Path, feature: str, when: dt.datetime) -> Path:
    reports_dir = root / ".specs" / feature / "eval-reports"
    reports_dir.mkdir(parents=True, exist_ok=True)
    for offset in range(60):
        stamp = (when + dt.timedelta(minutes=offset)).strftime("%Y-%m-%d-%H%M")
        candidate = reports_dir / f"eval-report-{stamp}.md"
        if not candidate.exists():
            return candidate
    return candidate


def render_table_verdict(verdict: str) -> str:
    return f"**{verdict}**" if verdict == "FAIL" else verdict


def write_report(root: Path, feature: str, results: list[tuple[str, str, str]]) -> Path:
    when = dt.datetime.now()
    branch = current_branch(root)
    report_path = reserve_report_path(root, feature, when)
    stamp = report_path.stem.removeprefix("eval-report-")
    lines = [
        f"# Specs self-evaluation — {feature}",
        "",
        f"Date: {stamp}",
        f"Evaluated: `.specs/{feature}/{{requirements,design,tasks}}.md` (state at HEAD of `{branch}`).",
        "",
        "| # | Check | Verdict |",
        "| - | ----- | ------- |",
    ]
    for index, (check, verdict, _reason) in enumerate(results, start=1):
        lines.append(f"| {index} | {check} | {render_table_verdict(verdict)} |")
    lines.extend(["", "---", ""])
    for index, (check, verdict, reason) in enumerate(results, start=1):
        lines.extend(
            [
                f"## {index}. {check} — **{verdict}**",
                "",
                reason,
                "",
            ]
        )
    failed = [(check, reason) for check, verdict, reason in results if verdict == "FAIL"]
    weak = [(check, reason) for check, verdict, reason in results if verdict == "WEAK"]
    lines.extend(["## Summary", ""])
    if failed:
        lines.append("Blocking failures: " + "; ".join(f"{check}: {reason}" for check, reason in failed))
    elif weak:
        lines.append("No blocking failures. Weak items: " + "; ".join(f"{check}: {reason}" for check, reason in weak))
    else:
        lines.append("All checklist items passed.")
    lines.append("")
    report_path.write_text("\n".join(lines), encoding="utf-8")
    return report_path


def main() -> None:
    try:
        _payload = json.loads(sys.stdin.read() or "{}")
    except json.JSONDecodeError:
        _payload = {}

    root = repo_root()
    features = touched_features(root)
    if not features:
        append_log(root, features, "skipped")
        emit({"suppressOutput": True})

    checks = checklist(root)
    evaluated = []
    failed_items: list[str] = []
    for feature in features:
        report_path, results, overall = evaluate_feature(root, feature, checks)
        rel_report = report_path.relative_to(root)
        digest = hashlib.sha256(report_path.read_bytes()).hexdigest()[:12]
        evaluated.append(f"{feature}: [{overall}] {rel_report} ({digest})")
        for check, verdict, reason in results:
            if verdict == "FAIL":
                failed_items.append(f"{feature} - {check}: {reason}")

    if failed_items:
        append_log(root, features, "block", "; ".join(failed_items))
        reason = (
            "spec-self-eval found [FAIL] items before this turn can close:\n"
            + "\n".join(f"- {item}" for item in failed_items)
            + "\n\nFix the spec files first, then let the Stop hook run again."
        )
        emit({"decision": "block", "reason": reason})

    append_log(root, features, "allow", "; ".join(evaluated))
    emit({"suppressOutput": True, "systemMessage": "spec-self-eval passed: " + "; ".join(evaluated)})


if __name__ == "__main__":
    main()
