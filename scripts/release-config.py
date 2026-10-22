"""Read the pinned game and validate release tags without running build tools."""
import os
from pathlib import Path
import re

root = Path(__file__).resolve().parent.parent
properties = dict(line.split("=", 1) for line in (root / "game-release.properties").read_text().splitlines() if line and not line.startswith("#"))
version = re.search(r'^version\s*=\s*"([0-9]+\.[0-9]+\.[0-9]+)"', (root / "build.gradle.kts").read_text(), re.MULTILINE).group(1)
if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", properties["repository"]):
    raise SystemExit("Invalid game repository")
if not re.fullmatch(r"[a-f0-9]{40}", properties["revision"]):
    raise SystemExit("Pin the game to an exact commit in game-release.properties")
if os.environ.get("GITHUB_REF_TYPE") == "tag" and os.environ.get("GITHUB_REF_NAME") != f"v{version}":
    raise SystemExit(f"Release tag must be v{version}, matching build.gradle.kts")
for key, value in {"game_repository": properties["repository"], "game_revision": properties["revision"], "version": version}.items():
    print(f"{key}={value}")
    if os.environ.get("GITHUB_OUTPUT"):
        with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
            output.write(f"{key}={value}\n")
