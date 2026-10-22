"""Install build tools in a temporary CI directory; no tools are shipped to players."""
import gzip
import hashlib
import os
from pathlib import Path
import platform
import shutil
import subprocess
import tempfile
import zipfile

tools = Path(os.environ.get("RUNNER_TEMP", tempfile.gettempdir())) / "blockbox-release-tools"
downloads = tools / "downloads"
downloads.mkdir(parents=True, exist_ok=True)
system = platform.system()
arm = platform.machine().lower() in ("arm64", "aarch64")
if system == "Windows":
    asset = "scala-cli-x86_64-pc-win32.zip"
elif system == "Darwin":
    asset = f"scala-cli-{'aarch64' if arm else 'x86_64'}-apple-darwin.gz"
else:
    asset = f"scala-cli-{'aarch64' if arm else 'x86_64'}-pc-linux.gz"


def download(repo, tag, name):
    destination = downloads / name
    if not destination.exists():
        subprocess.run(["gh", "release", "download", tag, "--repo", repo, "--pattern", name, "--dir", str(downloads)], check=True)
    return destination


archive = download("VirtusLab/scala-cli", "v1.18.0", asset)
executable = tools / ("scala-cli.exe" if system == "Windows" else "scala-cli")
if asset.endswith(".zip"):
    with zipfile.ZipFile(archive) as zip_file:
        member = next(name for name in zip_file.namelist() if Path(name).name.lower() == "scala-cli.exe")
        with zip_file.open(member) as source, executable.open("wb") as target:
            shutil.copyfileobj(source, target)
else:
    with gzip.open(archive, "rb") as source, executable.open("wb") as target:
        shutil.copyfileobj(source, target)
executable.chmod(0o755)
environment = {"BLOCKBOX_SCALA_CLI": str(executable)}

if system == "Linux":
    name = f"appimagetool-{'aarch64' if arm else 'x86_64'}.AppImage"
    tool = download("AppImage/appimagetool", "1.9.1", name)
    expected = "f0837e7448a0c1e4e650a93bb3e85802546e60654ef287576f46c71c126a9158" if arm else "ed4ce84f0d9caff66f50bcca6ff6f35aae54ce8135408b3fa33abfc3cb384eb0"
    if hashlib.sha256(tool.read_bytes()).hexdigest() != expected:
        raise SystemExit("appimagetool checksum mismatch")
    tool.chmod(0o755)
    environment["APPIMAGETOOL"] = str(tool)

for key, value in environment.items():
    print(f"{key}={value}")
    if os.environ.get("GITHUB_ENV"):
        with open(os.environ["GITHUB_ENV"], "a", encoding="utf-8") as output:
            output.write(f"{key}={value}\n")
