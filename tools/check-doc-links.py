#!/usr/bin/env python3
"""Check that every link in the top-level docs resolves for a reader.

    python3 tools/check-doc-links.py          # exits non-zero on any problem

Checked against `git ls-files`, deliberately, **not** against the working copy.
A reader meets these documents in a clone, which contains only what is
committed. A link verified with `os.path.exists` can still point at an untracked
local file and be broken for everyone else.

Covers:
  - relative file and directory targets  -> must be tracked by git
  - `#fragment` targets                  -> must match a heading in that file,
                                            slugged the way GitHub slugs them
  - bare `docs/...` mentions in backticks, which read as references even
    without link syntax
"""

from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
DOCS = ["README.md", "INSTALL.md", "USER_GUIDE.md"]

LINK = re.compile(r"\[([^\]]+)\]\(([^)]+)\)")
BARE = re.compile(r"`(docs/[^`]+)`")
HEADING = re.compile(r"^(#{1,6})\s+(.*)$")


def tracked_paths() -> set[str]:
    out = subprocess.run(["git", "ls-files"], cwd=REPO,
                         capture_output=True, text=True, check=True).stdout
    return set(out.split("\n")) - {""}


def is_tracked(path: str, tracked: set[str]) -> bool:
    path = path.rstrip("/")
    return path in tracked or any(t.startswith(path + "/") for t in tracked)


def anchors(md: Path) -> set[str]:
    """Heading ids as GitHub derives them: lowercase, punctuation dropped,
    spaces to hyphens. Leading digits are kept — pandoc drops them, GitHub does
    not, and these documents are written for GitHub."""
    found = set()
    for line in md.read_text(encoding="utf-8").splitlines():
        m = HEADING.match(line)
        if not m:
            continue
        text = re.sub(r"[`*_\[\]()]", "", m.group(2).strip().lower())
        found.add(re.sub(r"\s+", "-", re.sub(r"[^\w\s-]", "", text).strip()))
    return found


def main() -> int:
    tracked = tracked_paths()
    anchor_cache: dict[str, set[str]] = {}
    problems: list[str] = []

    for name in DOCS:
        src = REPO / name
        if not src.exists():
            continue
        text = src.read_text(encoding="utf-8")
        targets = [t for _, t in LINK.findall(text)
                   if not t.startswith(("http://", "https://", "mailto:"))]
        targets += BARE.findall(text)

        for target in targets:
            path, _, frag = target.partition("#")
            if path and not is_tracked(path, tracked):
                on_disk = (REPO / path).exists()
                why = "exists on disk but is not committed" if on_disk else "does not exist"
                problems.append(f"{name} -> {target}\n      {why}")
                continue
            if frag:
                holder = path or name
                if not holder.endswith(".md"):
                    continue
                if holder not in anchor_cache:
                    anchor_cache[holder] = anchors(REPO / holder)
                if frag not in anchor_cache[holder]:
                    problems.append(f"{name} -> {target}\n      no heading in {holder} slugs to '{frag}'")

    if problems:
        print(f"{len(problems)} broken reference(s):\n")
        for p in problems:
            print(f"  {p}")
        return 1
    print(f"all references in {', '.join(DOCS)} resolve")
    return 0


if __name__ == "__main__":
    sys.exit(main())
