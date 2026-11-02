#!/usr/bin/env python3
"""Static release guard: MIT metadata and the bundled wrapper's Apache license."""
from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
NOTICE = ROOT / "gradle/wrapper/LICENSE-APACHE-2.0.txt"
errors = []
if not NOTICE.exists():
    print("ERROR: gradle/wrapper/LICENSE-APACHE-2.0.txt is missing")
    sys.exit(1)
notice_text = NOTICE.read_text(encoding="utf-8")
if (ROOT / "THIRD_PARTY_NOTICES.md").exists():
    errors.append("Obsolete THIRD_PARTY_NOTICES.md must remain deleted")

# Read project names from the notice, rather than hard-coding names into this verifier.
external_names = []
for line in notice_text.splitlines():
    match = re.match(r"\s*-\s+\*\*(.+?)\*\*", line)
    if not match:
        continue
    for candidate in match.group(1).split("/"):
        candidate = candidate.strip()
        if candidate:
            external_names.append(candidate)

license_path = ROOT / "LICENSE"
license_text = license_path.read_text(encoding="utf-8") if license_path.exists() else ""
if not license_text.startswith("MIT License"):
    errors.append("Root LICENSE does not start with MIT License")
if "Copyright (c) 2026 Yaseen" not in license_text:
    errors.append("Root LICENSE is missing the expected copyright notice")

# v1.2.0: the project is a developer library, NOT a mod — it must ship no mod metadata.
mods_path = ROOT / "src/main/resources/META-INF/mods.toml"
if mods_path.exists():
    errors.append("Library release must not ship META-INF/mods.toml (see docs/LIBRARY_CONVERSION_v1.2.0.md)")
pack_path = ROOT / "src/main/resources/pack.mcmeta"
if pack_path.exists():
    errors.append("Library release must not ship pack.mcmeta")

build_text = (ROOT / "build.gradle").read_text(encoding="utf-8")
api_text = (ROOT / "src/main/java/com/yaseen/npcmind/api/NPCMindAPI.java").read_text(encoding="utf-8")

# v1.2.0 hardening: gradle.properties is the single source of truth for the version;
# build.gradle must wire project.mod_version (and the Minecraft/Forge coordinates) from it.
props_path = ROOT / "gradle.properties"
props_text = props_path.read_text(encoding="utf-8") if props_path.exists() else ""
expected_version = None
if not props_text:
    errors.append("gradle.properties is missing (single version source of truth)")
else:
    version_match = re.search(r"(?m)^mod_version\s*=\s*([^\s#]+)", props_text)
    expected_version = version_match.group(1) if version_match else None
    if not expected_version:
        errors.append("gradle.properties has no mod_version")
    if not re.search(r"(?m)^minecraft_version\s*=\s*1\.20\.1\s*$", props_text):
        errors.append("gradle.properties minecraft_version is not 1.20.1")
    if not re.search(r"(?m)^forge_version\s*=\s*47\.4\.10\s*$", props_text):
        errors.append("gradle.properties forge_version is not 47.4.10")
if not re.search(r"version\s*=\s*project\.mod_version", build_text):
    errors.append("build.gradle does not wire version from gradle.properties (project.mod_version)")
if not re.search(r"minecraft ['\"]net\.minecraftforge:forge:\$\{project\.minecraft_version\}-\$\{project\.forge_version\}['\"]", build_text):
    errors.append("build.gradle does not wire the Forge dependency from gradle.properties")
if not re.search(r"name\s*=\s*'MIT License'", build_text):
    errors.append("Maven publication POM metadata does not declare MIT")
if "Apache License, Version 2.0" not in notice_text:
    errors.append("Bundled Gradle Wrapper Apache License 2.0 text is missing")
if expected_version and not re.search(r'API_VERSION\s*=\s*"' + re.escape(expected_version) + r'"', api_text):
    errors.append("Public API version does not match gradle.properties mod_version")
if (ROOT / "LICENSES").exists():
    errors.append("Stale LICENSES directory remains in the MIT release candidate")
library_text = (ROOT / "src/main/java/com/yaseen/npcmind/NPCMindLibrary.java").read_text(encoding="utf-8") if (ROOT / "src/main/java/com/yaseen/npcmind/NPCMindLibrary.java").exists() else ""
if not re.search(r"public static void bootstrap\(IEventBus hostModEventBus\)", library_text):
    errors.append("NPCMindLibrary bootstrap facade is missing")

# v1.2.0 hardening: the library governance artifacts must exist in the release.
for governance in ("CHANGELOG.md", "CONTRIBUTING.md", "SECURITY.md", "docs/API_STABILITY.md",
                   "docs/GENERIC_NPC_FRAMEWORK.md", "tools/check_api_surface.py",
                   "gradle/wrapper/LICENSE-APACHE-2.0.txt",
                   ".github/workflows/build.yml", "jitpack.yml"):
    if not (ROOT / governance).exists():
        errors.append(f"Library governance artifact missing: {governance}")

checked = 0
for file_path in ROOT.rglob("*"):
    if not file_path.is_file() or file_path == NOTICE:
        continue
    # Binary files are excluded; all authored text/config/source files are checked.
    try:
        content = file_path.read_text(encoding="utf-8")
    except (UnicodeDecodeError, OSError):
        continue
    checked += 1
    for name in external_names:
        if re.search(re.escape(name), content, re.IGNORECASE):
            errors.append(f"External reference name '{name}' appears outside the wrapper license file: {file_path.relative_to(ROOT)}")

print(f"MIT_LICENSE_OK={license_text.startswith('MIT License')}")
print(f"LIBRARY_PACKAGING_OK={not mods_path.exists() and not pack_path.exists()}")
print(f"TEXT_FILES_CHECKED={checked}")
print(f"REFERENCE_NAMES_CHECKED={len(external_names)}")
print(f"MIT_RELEASE_ERRORS={len(errors)}")
for error in errors:
    print("ERROR=" + error)
sys.exit(1 if errors else 0)
