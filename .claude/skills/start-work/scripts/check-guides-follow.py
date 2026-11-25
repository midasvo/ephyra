#!/usr/bin/env python3
"""Checks that a pull request keeps the area guides with the code they describe (#928).

An area in docs/areas.yaml that has a guide (docs/areas/<id>.md) lists the files to open first
(`paths`). When a pull request changes one of those files, it has to do one of these:
  - change the area's guide, or the area's entry in docs/areas.yaml;
  - say in its description why the guide still holds, on a line of its own:
        Docs: no change needed — <the reason>
    (a hyphen or a colon instead of the dash is fine; the reason needs a few words, and the
    template's `<…>` placeholder does not count).

Not counted: areas without a guide, test sources (src/test, src/androidTest), translations
(res/values-<lang>/), and pull requests from Renovate or Dependabot or an upstream sync (a branch
named sync/…). A changed file can match several areas; the message names each guide and the
paths that matched.

Locally, against origin/main, with an optional description in a file, inline text, or from a PR:
    python3 .claude/skills/start-work/scripts/check-guides-follow.py [--body FILE | --body-text TEXT | --pr NUMBER]
In CI (.github/workflows/docs.yml) it reads the pull request's files as JSON lines
({"filename", "previous_filename", "patch"}, from the GitHub API) with --files, and the
description, author and branch from the environment (PR_BODY, PR_AUTHOR, PR_BRANCH).
No packages; it reads docs/areas.yaml without a YAML parser, like check-docs.py.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[4]
MAP = "docs/areas.yaml"
BOTS = {"app/renovate", "renovate[bot]", "renovate", "dependabot[bot]", "app/dependabot"}
NOT_COUNTED = [
    re.compile(r"/src/(test|androidTest|testFixtures)[^/]*/"),
    re.compile(r"/res/values-[^/]+/"),
]
# The reason may not be the template's placeholder (<…>).
NO_CHANGE = re.compile(r"^[ \t]*Docs:[ \t]*no change needed[ \t]*[—–:-]+[ \t]*([^\s<][^\n]*)$", re.I | re.M)
MIN_REASON = 10


def areas(map_file: Path) -> list[tuple[str, list[str], range]]:
    """Each area with a guide: its id, its paths, and the lines of its entry in the map."""
    lines = map_file.read_text(encoding="utf-8").splitlines()
    starts = [i for i, line in enumerate(lines) if line.startswith("  - id: ")]
    result = []
    for n, start in enumerate(starts):
        end = starts[n + 1] if n + 1 < len(starts) else len(lines)
        area = lines[start].removeprefix("  - id: ").strip()
        if not (ROOT / f"docs/areas/{area}.md").is_file():
            continue
        paths, in_paths = [], False
        for line in lines[start:end]:
            if re.match(r"^    \w+:", line):
                in_paths = line.startswith("    paths:")
                inline = line.removeprefix("    paths:").strip()
                if in_paths and inline.startswith("["):
                    paths += inline.strip("[]").split(",")
            elif in_paths and line.startswith("      - "):
                paths.append(line.removeprefix("      - "))
        paths = [unquote(item) for item in paths if item.strip()]
        # Line numbers are 1-based, as in a diff.
        result.append((area, paths, range(start + 1, end + 1)))
    return result


def unquote(item: str) -> str:
    item = item.strip()
    return item[1:-1] if len(item) > 1 and item[0] == item[-1] and item[0] in "'\"" else item


def changed_lines(patch: str) -> set[int]:
    """The lines of the new file a unified diff touches.

    A replaced line counts where its new text is. A line only deleted has no place in the new file:
    it counts on the line before it (the entry it was in, unless it was an entry's first line).
    """
    touched: set[int] = set()
    new = 0
    deleted: list[int] = []  # positions of a run of deletions not yet followed by an addition

    def flush() -> None:
        touched.update(max(n - 1, 1) for n in deleted)
        deleted.clear()

    for line in patch.splitlines():
        hunk = re.match(r"^@@ -\d+(?:,\d+)? \+(\d+)(?:,(\d+))? @@", line)
        if hunk:
            flush()
            # With no new lines in the hunk, its start is the line before the deletion.
            new = int(hunk.group(1)) + (1 if hunk.group(2) == "0" else 0)
        elif line.startswith("+"):
            deleted.clear()  # a replacement: the new text says where it belongs
            touched.add(new)
            new += 1
        elif line.startswith("-"):
            deleted.append(new)
        elif not line.startswith("\\"):
            flush()
            new += 1
    flush()
    return touched


def local_files() -> list[dict]:
    """The files of HEAD against origin/main, in the API's shape."""
    names = subprocess.run(
        ["git", "diff", "--name-status", "-M", "origin/main...HEAD"],
        cwd=ROOT, capture_output=True, text=True, check=True,
    ).stdout.splitlines()
    files = []
    for row in names:
        parts = row.split("\t")
        entry = {"filename": parts[-1]}
        if parts[0].startswith("R") and len(parts) == 3:
            entry["previous_filename"] = parts[1]
        if parts[-1] == MAP:
            entry["patch"] = subprocess.run(
                ["git", "diff", "-U0", "origin/main...HEAD", "--", MAP],
                cwd=ROOT, capture_output=True, text=True, check=True,
            ).stdout
        files.append(entry)
    return files


def main() -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    parser = argparse.ArgumentParser()
    parser.add_argument("--files", help="JSON lines of the pull request's files (default: git)")
    body_group = parser.add_mutually_exclusive_group()
    body_group.add_argument("--body", help="a file with the pull request's description")
    body_group.add_argument("--body-text", help="the pull request's description as inline text")
    body_group.add_argument("--pr", type=int, help="pull request number to read the description from via gh pr view")
    parser.add_argument(
        "--map",
        help="docs/areas.yaml as the pull request's head has it, so its line numbers match the patch "
        "(default: the one in this tree)",
    )
    args = parser.parse_args()

    author = os.environ.get("PR_AUTHOR", "")
    branch = os.environ.get("PR_BRANCH", "")
    if author in BOTS or branch.startswith("sync/"):
        print(f"Not checked: a pull request from {author or branch}.")
        return 0

    if args.files:
        text = Path(args.files).read_text(encoding="utf-8")
        files = [json.loads(line) for line in text.splitlines() if line.strip()]
    else:
        files = local_files()
    if args.body:
        body = Path(args.body).read_text(encoding="utf-8")
    elif args.body_text is not None:
        body = args.body_text
    elif args.pr is not None:
        try:
            out = subprocess.run(
                ["gh", "pr", "view", str(args.pr), "--json", "body"],
                cwd=ROOT,
                capture_output=True,
                text=True,
                check=True,
            ).stdout
        except (OSError, subprocess.CalledProcessError) as exc:
            detail = exc.stderr.strip() if isinstance(exc, subprocess.CalledProcessError) and exc.stderr else str(exc)
            sys.exit(f"failed to read PR #{args.pr} via gh pr view: {detail}")
        body = json.loads(out).get("body") or ""
    else:
        body = os.environ.get("PR_BODY", "")

    names = {f["filename"] for f in files} | {f["previous_filename"] for f in files if f.get("previous_filename")}
    counted = [n for n in sorted(names) if not any(p.search(n) for p in NOT_COUNTED)]
    map_lines = set()
    for f in files:
        if f["filename"] == MAP:
            map_lines |= changed_lines(f.get("patch") or "")

    owed = []
    map_file = Path(args.map) if args.map else ROOT / MAP
    for area, paths, entry_lines in areas(map_file):
        hits = [
            (name, path)
            for name in counted
            for path in paths
            if name == path.rstrip("/") or name.startswith(path.rstrip("/") + "/")
        ]
        if not hits:
            continue
        if f"docs/areas/{area}.md" in names or map_lines & set(entry_lines):
            continue
        owed.append((area, hits))

    if not owed:
        print("The area guides follow this change.")
        return 0
    reasons = [m.group(1).strip() for m in NO_CHANGE.finditer(body)]
    reason = next((r for r in reasons if len(r) >= MIN_REASON), None)
    if reason:
        print(f"Guides not changed, with a reason: {reason}")
        for area, _ in owed:
            print(f"  docs/areas/{area}.md")
        return 0

    print("This pull request changes files that an area guide names, and not the guide:\n")
    for area, hits in owed:
        print(f"docs/areas/{area}.md (area `{area}` in {MAP}):")
        for name, path in hits:
            print(f"  {name}" + ("" if name == path else f"  (under {path})"))
        if os.environ.get("GITHUB_ACTIONS"):
            print(f"::error file=docs/areas/{area}.md::The PR changes files this guide names: "
                  f"{', '.join(sorted({n for n, _ in hits}))}. Update the guide, or add "
                  f"'Docs: no change needed — <reason>' to the PR description.")
    print(
        "\nDo one of these:\n"
        "  - update the guide (or its entry in docs/areas.yaml) where the change makes it wrong or\n"
        "    leaves out something the next person needs: a new file, a moved rule, a fixed or new pitfall;\n"
        "  - or add a line to the pull request's description:\n"
        "        Docs: no change needed — <why the guide still holds>\n"
        "The check runs again when the description is edited."
    )
    return 1


if __name__ == "__main__":
    sys.exit(main())
