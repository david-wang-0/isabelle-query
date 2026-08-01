#!/usr/bin/env python3
"""Print the concatenation of 1-indexed inclusive line ranges from a file.

The read half of the cli.py module split: paired with
`refactor_delete_lines.py`, it copies the exact source of the functions being
moved into a new module — no manual retyping of Unicode arrows or inline
comments.  Ranges are emitted in the order given (not sorted), so a straggler
function pulled from elsewhere can be appended after the main contiguous block.

    python3 scripts/refactor_extract.py FILE A-B [C-D ...] >> newmodule.py
"""

import sys
from pathlib import Path


def main() -> None:
    path = Path(sys.argv[1])
    lines = path.read_text().splitlines(keepends=True)
    out = []
    for spec in sys.argv[2:]:
        a, b = spec.split("-")
        out.extend(lines[int(a) - 1:int(b)])
    sys.stdout.write("".join(out))


if __name__ == "__main__":
    main()
