#!/usr/bin/env python3
# SPDX-FileCopyrightText: Open Camera contributors
# SPDX-License-Identifier: Apache-2.0
"""Merges a generated baseline profile into the committed app/src/main/baseline-prof.txt.

Keeps the header and the existing rules in place, drops generated Compose rules (Compose ships
its own profile) and appends the new rules sorted, so diffs stay small and the file is stable.
"""
import sys


def main(generated: str, target: str) -> None:
    with open(target, encoding="utf-8") as f:
        lines = f.read().splitlines()
    header = []
    for line in lines:
        if not line.startswith("#"):
            break
        header.append(line)
    rules = list(dict.fromkeys(l for l in lines[len(header):] if l.strip()))
    before = len(rules)
    with open(generated, encoding="utf-8") as f:
        new = {l.strip() for l in f if l.strip() and not l.startswith("#")}
    added = sorted({l for l in new if "Landroidx/compose/" not in l} - set(rules))
    with open(target, "w", encoding="utf-8") as f:
        f.write("\n".join(header + rules + added) + "\n")
    print(f"baseline-prof.txt: {before} -> {before + len(added)} rules ({len(new)} generated, {len(added)} new)")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("usage: merge_profile.py GENERATED TARGET")
    main(sys.argv[1], sys.argv[2])
