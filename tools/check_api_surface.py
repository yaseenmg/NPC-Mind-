#!/usr/bin/env python3
"""API surface gate for the NPC Mind developer library (docs/API_STABILITY.md).

Enforces, over src/main/java/com/yaseen/npcmind:
  1. Every top-level `public` type OUTSIDE the stable surface carries @Internal
     (immediately above the declaration) plus the matching import.
  2. No stable-surface file is annotated @Internal (the marker would contradict
     the stability guarantee).
  3. The stable surface is exactly the documented set: the api package, the stable
     tool contract (AITool / ToolContract / JsonArgs) and the NPCMindLibrary facade.

This is a source-level gate, not a compile: it catches accidental API widening
(adding a public type without the marker) and accidental marker drift.
"""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "src/main/java/com/yaseen/npcmind"
IMPORT = "import com.yaseen.npcmind.api.Internal;"
STABLE = {
    "api/NPCMindAPI.java", "api/EntityAITool.java", "api/EntityToolRegistry.java",
    "api/NPCMindActions.java", "api/PlayerLikeExperience.java", "api/PlayerLikeState.java",
    "api/ToolPack.java", "api/Internal.java",
    "tool/AITool.java", "tool/ToolContract.java", "tool/JsonArgs.java",
    "NPCMindLibrary.java",
}
DECL = re.compile(
    r"^public\s+(?:(?:final|abstract|sealed|non-sealed|static)\s+)*(class|interface|enum|record)\s+[A-Z]")

errors = []
stable_files, marked_files, unmarked = 0, 0, []
for path in sorted(SRC.rglob("*.java")):
    rel = path.relative_to(SRC).as_posix()
    text = path.read_text(encoding="utf-8")
    lines = text.splitlines()
    decls = [i for i, line in enumerate(lines) if DECL.match(line)]
    if rel in STABLE:
        stable_files += 1
        if "@Internal" in text and rel != "api/Internal.java":
            errors.append(f"stable surface file must not carry @Internal: {rel}")
        continue
    if not decls:
        if "@Internal" in text:
            errors.append(f"@Internal referenced without a top-level public type: {rel}")
        continue
    unannotated = [i for i in decls if i == 0 or lines[i - 1].strip() != "@Internal"]
    if unannotated:
        unmarked.append(rel)
        continue
    marked_files += 1
    if IMPORT not in text:
        errors.append(f"@Internal applied without import in {rel}")
    if text.count("{") != text.count("}"):
        errors.append(f"unbalanced braces in {rel}")

print(f"STABLE_SURFACE_FILES={stable_files}")
print(f"INTERNAL_MARKED_FILES={marked_files}")
print(f"UNMARKED_FILES={len(unmarked)}")
for rel in unmarked:
    print(f"ERROR=public type missing @Internal: {rel}")
for e in errors:
    print("ERROR=" + e)
print(f"API_SURFACE_ERRORS={len(unmarked) + len(errors)}")
sys.exit(1 if (unmarked or errors) else 0)
