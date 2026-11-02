#!/usr/bin/env python3
"""Dependency-free static source audit for NPC Mind. This is not a Forge compile."""
from pathlib import Path
import re, sys
from network_policy import scan_java_source
ROOT = Path(__file__).resolve().parents[1]
JAVA = ROOT / "src/main/java"
RES = ROOT / "src/main/resources"
errors=[]
java_files=sorted(JAVA.rglob("*.java"))
registry=JAVA/"com/yaseen/npcmind/tool/ToolRegistry.java"
if not registry.exists(): errors.append("Missing ToolRegistry.java")
registered=[]
if registry.exists():
    txt=registry.read_text(encoding="utf-8")
    registered=re.findall(r'register\(new\s+([A-Za-z_][A-Za-z0-9_]*)\s*\(\)\s*\)',txt)
    if not registered: errors.append("No built-in tool registrations could be read")
seen={}
for cls in registered:
    matches=list(JAVA.rglob(f"{cls}.java"))
    if len(matches)!=1:
        errors.append(f"Registered class {cls}: expected one source file, found {len(matches)}")
        continue
    src=matches[0].read_text(encoding="utf-8")
    if "implements AITool" not in src: errors.append(f"{cls} does not implement AITool")
    m=re.search(r'\bString\s+name\s*\(\s*\)\s*\{\s*return\s+"([^"]+)"',src)
    if not m: errors.append(f"Could not parse literal name() for {cls}"); continue
    if m.group(1) in seen: errors.append(f"Duplicate tool name {m.group(1)}")
    seen[m.group(1)]=cls
    if not re.search(r'\bString\s+schema\s*\(\s*\)',src): errors.append(f"{cls} has no schema()")
controller=JAVA/"com/yaseen/npcmind/entity/PlayerLikeInteractionController.java"
if not controller.exists(): errors.append("Missing PlayerLikeInteractionController.java")
else:
    txt=controller.read_text(encoding="utf-8")
    for marker in ("calculateBreakTicks","findPlacementHit","placeHeldBlock","useItemOnFaceFromInventorySlot"):
        if marker not in txt: errors.append(f"Interaction controller missing {marker}")
    calls=re.findall(r'new\s+net\.minecraft\.world\.item\.context\.UseOnContext\s*\(([^)]*)\)',txt,re.S)
    if len(calls)<2 or any(not re.search(r'\blevel\s*,\s*actor\s*,\s*InteractionHand\.MAIN_HAND\s*,\s*interactionStack\s*,\s*hit\b',c) for c in calls):
        errors.append("UseOnContext constructor signature check failed")
for p in JAVA.rglob("*.java"):
    try: text=p.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError):
        errors.append(f"Could not read Java source as UTF-8: {p.relative_to(ROOT)}")
        continue
    errors.extend(scan_java_source(p.relative_to(ROOT).as_posix(), text))
mods=RES/"META-INF/mods.toml"
if mods.exists():
    t=mods.read_text(encoding="utf-8",errors="ignore").lower()
    for term in ("groq","openai","discord","http"):
        if term in t: errors.append(f"Unexpected provider/dependency marker {term} in mods.toml")
print(f"JAVA_FILES={len(java_files)}")
print(f"REGISTERED_TOOLS={len(registered)}")
print(f"UNIQUE_TOOL_NAMES={len(seen)}")
print(f"AUDIT_ERRORS={len(errors)}")
for e in errors: print("ERROR="+e)
sys.exit(1 if errors else 0)
