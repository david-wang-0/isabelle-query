#!/usr/bin/env python3
"""Filter a single-file unified diff (on stdin) to keep only the hunks whose
*original* start line is listed on the command line, emitting a patch suitable
for `git apply --cached`.  Lets us stage a subset of a file's hunks
non-interactively (the terminal `git add -p`/`-i` are unavailable here).

    git diff -- FILE | python3 scripts/git_keep_hunks.py 148 252 260 276 > p
    git apply --cached p

Each hunk header is `@@ -<orig_start>,<len> +<new_start>,<len> @@`; a hunk is
kept iff <orig_start> is among the given numbers.  The file header (`diff`,
`---`, `+++`, and any `index`/mode lines) is always preserved.
"""
import sys


def main() -> int:
    keep = {int(a) for a in sys.argv[1:]}
    lines = sys.stdin.read().splitlines(keepends=True)
    out: list[str] = []
    i = 0
    n = len(lines)
    # File header: everything up to the first hunk (`@@`).
    while i < n and not lines[i].startswith("@@"):
        out.append(lines[i])
        i += 1
    while i < n:
        header = lines[i]                      # an `@@ -a,b +c,d @@` line
        orig_start = int(header.split(" ")[1].lstrip("-").split(",")[0])
        j = i + 1
        while j < n and not lines[j].startswith("@@"):
            j += 1
        if orig_start in keep:
            out.append(header)
            out.extend(lines[i + 1:j])
        i = j
    sys.stdout.write("".join(out))
    return 0


if __name__ == "__main__":
    sys.exit(main())
