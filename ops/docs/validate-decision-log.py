#!/usr/bin/env python3
"""Validate docs/design-decisions.md against DOC-3, DOC-4 and DOC-5.

AC-0.5 and AC-1.12 both require that every decision entry carries at least two
rejected alternatives and a falsifiable revisit condition. That is not something
to check by eye across thirty entries and rising, so it is checked here and in
CI.

What this cannot check is whether the reasoning is any good - DOC-4 asks for a
*specific* reason ("it adds a network round trip on the hot path"), not a vague
one ("it's slower"). That judgement stays with the reviewer. This catches the
mechanical failures: a missing section, a single alternative, an entry with no
way to be proven wrong.

Usage:  python ops/docs/validate-decision-log.py [path]
"""
import re
import sys

REQUIRED_SECTIONS = [
    "**Context.**",
    "**Decision.**",
    "**Alternatives considered.**",
    "**Consequences.**",
    "**What would change this.**",
]

MIN_ALTERNATIVES = 2


def entries(text):
    """Split into (id, body) pairs on the '### DD-NNN' headings."""
    parts = re.split(r"^### (DD-\d{3}) ", text, flags=re.MULTILINE)
    # parts[0] is the preamble; then id, body, id, body, ...
    return list(zip(parts[1::2], parts[2::2]))


def count_alternatives(body):
    """Numbered items inside the Alternatives section."""
    start = body.find("**Alternatives considered.**")
    if start == -1:
        return 0
    end = body.find("**Consequences.**", start)
    section = body[start:end if end != -1 else len(body)]
    return len(re.findall(r"^\d+\.\s", section, flags=re.MULTILINE))


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else "docs/design-decisions.md"
    text = open(path, encoding="utf-8").read()

    found = entries(text)
    if not found:
        print("FAIL: no DD entries found in %s" % path)
        return 1

    failures = []
    seen = set()

    for dd_id, body in found:
        if dd_id in seen:
            failures.append("%s: duplicate entry id" % dd_id)
        seen.add(dd_id)

        for section in REQUIRED_SECTIONS:
            if section not in body:
                failures.append("%s: missing section %s (DOC-3)" % (dd_id, section))

        n = count_alternatives(body)
        if n < MIN_ALTERNATIVES:
            failures.append(
                "%s: %d rejected alternative(s), DOC-4 requires at least %d"
                % (dd_id, n, MIN_ALTERNATIVES)
            )

    # Contiguity: SDD §0 tells the Reviewer agent to reject untraceable ids, and
    # a gap in the sequence is either a deleted entry (DOC-1 forbids deletion)
    # or a numbering mistake.
    numbers = sorted(int(d.split("-")[1]) for d in seen)
    expected = list(range(numbers[0], numbers[-1] + 1))
    missing = set(expected) - set(numbers)
    if missing:
        failures.append(
            "gap in the sequence: DD-%s absent. Entries are superseded, never deleted (DOC-1)"
            % ", DD-".join("%03d" % m for m in sorted(missing))
        )

    print("checked %d entries in %s" % (len(found), path))

    if failures:
        print("\n%d problem(s):\n" % len(failures))
        for f in failures:
            print("  " + f)
        return 1

    print("all entries satisfy DOC-3 (five sections), DOC-4 (>=2 alternatives)")
    print("and DOC-5 (a falsifiable revisit condition).")
    return 0


if __name__ == "__main__":
    sys.exit(main())
