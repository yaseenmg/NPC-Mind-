#!/usr/bin/env python3
"""Export all UTF-8 text files in this project into one reviewable source TXT file.

Binary artifacts such as gradle-wrapper.jar remain in the source ZIP and are listed in
an excluded-file manifest at the top of the TXT export.
"""
from __future__ import annotations

import argparse
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SKIP_PARTS = {".git", ".gradle", "build", "run", "logs", "crash-reports", "__pycache__"}
SKIP_NAMES = {".DS_Store"}
SKIP_SUFFIXES = {".class", ".jar", ".zip", ".7z", ".rar", ".png", ".jpg", ".jpeg", ".gif", ".webp", ".ico", ".dll", ".so", ".dylib", ".exe", ".args"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("output", type=Path, help="destination TXT path, preferably outside the project tree")
    args = parser.parse_args()
    output = args.output.resolve()

    included: list[tuple[Path, str]] = []
    excluded: list[str] = []
    for path in sorted(ROOT.rglob("*")):
        if not path.is_file() or path.resolve() == output:
            continue
        rel = path.relative_to(ROOT)
        if any(part in SKIP_PARTS for part in rel.parts) or path.name in SKIP_NAMES or path.suffix.lower() in SKIP_SUFFIXES:
            excluded.append(rel.as_posix())
            continue
        try:
            content = path.read_text(encoding="utf-8")
        except (UnicodeDecodeError, OSError):
            excluded.append(rel.as_posix())
            continue
        included.append((rel, content))

    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8", newline="\n") as stream:
        stream.write("NPC MIND — COMPLETE TEXT SOURCE EXPORT\n")
        stream.write(f"Project root: {ROOT.name}\n")
        stream.write(f"Included UTF-8 text files: {len(included)}\n")
        stream.write(f"Excluded binary/generated files: {len(excluded)}\n")
        stream.write("All excluded files remain listed below; required binaries are preserved in the ZIP distribution.\n\n")
        stream.write("=== INCLUDED FILE INDEX ===\n")
        for rel, _ in included:
            stream.write(f"{rel.as_posix()}\n")
        stream.write("\n=== EXCLUDED FILE INDEX ===\n")
        for name in excluded:
            stream.write(f"{name}\n")
        for rel, content in included:
            stream.write("\n\n" + "=" * 96 + "\n")
            stream.write(f"FILE: {rel.as_posix()}\n")
            stream.write("=" * 96 + "\n")
            stream.write(content)
            if not content.endswith("\n"):
                stream.write("\n")
    print(f"INCLUDED_TEXT_FILES={len(included)}")
    print(f"EXCLUDED_FILES={len(excluded)}")
    print(f"OUTPUT={output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
