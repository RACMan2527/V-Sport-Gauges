#!/usr/bin/env python3
"""Reject newly added public-documentation language about unverified work or bug fixes.

Only added lines are checked: existing historical text is not retroactively rewritten.
"""
import re
import subprocess
import sys

FILES = ("README.md", "docs/USER_GUIDE.md")
PATTERNS = (
    (r"\bverification status\b", "verification-status label"),
    (r"\b(?:awaiting|pending|requires|needs|not yet|still awaiting)\s+(?:vehicle\s+)?(?:verification|validation|testing|confirmation)\b", "pending verification/testing"),
    (r"\b(?:remain|remains)\s+to\s+be\s+(?:verified|validated|tested|confirmed)\b", "pending validation"),
    (r"\b(?:unverified|not yet verified|not yet validated|untested)\b", "unverified feature language"),
    (r"\b(?:bug\s*fix(?:es)?|hotfix(?:es)?|internal maintenance)\b", "internal fix history"),
    (r"\b(?:build|ci|workflow)\s+(?:status|pending|failed|passed|succeeded|successful)\b", "internal build status"),
    (r"\b(?:commit|pull request|pr)\s+(?:sha|hash|status)\b", "internal development status"),
)
RULES = [(re.compile(p, re.IGNORECASE), why) for p, why in PATTERNS]


def main():
    base = sys.argv[1] if len(sys.argv) > 1 else "HEAD^"
    diff = subprocess.run(
        ["git", "diff", "--unified=0", "--no-ext-diff", base, "HEAD", "--", *FILES],
        check=True, capture_output=True, text=True,
    ).stdout
    filename = None
    errors = []
    for line in diff.splitlines():
        if line.startswith("+++ b/"):
            filename = line[6:]
        elif line.startswith("+") and not line.startswith("+++"):
            content = line[1:]
            for rule, why in RULES:
                if rule.search(content):
                    errors.append(f"{filename}: {why}: {content[:180]}")
                    break
    if errors:
        print("Documentation policy failed: public docs describe verification, fixes, or internal process.")
        print("\n".join(errors))
        print("Keep these details in commits/PRs; document confirmed user-facing features only.")
        return 1
    print("Documentation policy check passed for newly added README/User Guide lines.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
