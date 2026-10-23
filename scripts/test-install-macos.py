"""Exercise the macOS installer without network access, a JDK, or real mounts."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parent
APP_NAME = "Blockbox Launcher.app"
COMMAND_FIXTURE = r'''#!/usr/bin/env python3
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

root = Path(os.environ["INSTALLER_FIXTURE"])
command = Path(sys.argv[0]).name
args = sys.argv[1:]
with (root / "commands.jsonl").open("a") as log:
    log.write(json.dumps([command, *args]) + "\n")
mode = os.environ.get("FIXTURE_FAILURE", "")
if command == "uname":
    print(os.environ.get("FIXTURE_OS", "Darwin") if args == ["-s"] else os.environ.get("FIXTURE_ARCH", "arm64"))
elif command == "sysctl":
    print(os.environ.get("FIXTURE_ARM64", "0"))
elif command == "curl":
    url = args[-1]
    if mode == "download" and not url.endswith("/latest"):
        sys.exit(22)
    if url.endswith("/latest"):
        print("https://github.com/RobertFlexx/blockbox-launcher/releases/tag/v-test", end="")
    else:
        if "/download/v-test/" not in url:
            sys.exit("Download was not pinned to the resolved release")
        shutil.copyfile(root / "assets" / url.rsplit("/", 1)[-1], args[args.index("--output") + 1])
elif command == "hdiutil":
    if args[0] == "attach":
        if sys.stdin.read().strip().lower() not in ("y", "yes"):
            sys.exit("DMG license prompt was not answered")
        mount = Path(args[args.index("-mountpoint") + 1])
        subprocess.run(["/usr/bin/ditto", str(root / "app"), str(mount / "Blockbox Launcher.app")], check=True)
    else:
        if mode == "detach":
            sys.exit(1)
        shutil.rmtree(Path(args[1]) / "Blockbox Launcher.app")
elif command == "java":
    print("No installed Java runtime", file=sys.stderr)
    sys.exit(1)
else:
    if (command == "mv" and mode == "move" and ".blockbox-install." in args[0]) or (command == "xattr" and mode == "quarantine" and ".blockbox-install." in args[-1]) or (command == "ditto" and mode == "copy"):
        sys.exit(1)
    executable = {"mv": "/bin/mv", "xattr": "/usr/bin/xattr", "ditto": "/usr/bin/ditto"}[command]
    os.execv(executable, [executable, *args])
'''


@unittest.skipUnless(sys.platform == "darwin", "Uses macOS ditto and extended attributes")
class MacInstallerTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory(prefix="blockbox-installer-", dir="/tmp")
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / "bin"
        self.bin.mkdir()
        fixture = self.bin / "fixture-command"
        fixture.write_text(COMMAND_FIXTURE)
        fixture.chmod(0o755)
        for command in ("uname", "sysctl", "curl", "hdiutil", "mv", "xattr", "ditto", "java"):
            (self.bin / command).symlink_to(fixture)
        self.apps = self.root / "user's Applications"
        self.target = self.apps / APP_NAME
        self.downloads = self.root / "temporary downloads"
        self.downloads.mkdir()
        self.env = dict(os.environ, PATH=f"{self.bin}:{os.environ['PATH']}",
                        INSTALLER_FIXTURE=str(self.root), BLOCKBOX_APPLICATIONS_DIR=str(self.apps),
                        TMPDIR=str(self.downloads))
        self.app = self.root / "app"
        for relative in ("Contents/MacOS/Blockbox Launcher", "Contents/runtime/Contents/Home/bin/java",
                         "Contents/app/blockbox-launcher.jar", "Contents/app/game/blockbox-game.properties"):
            destination = self.app / relative
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text("fixture app")
            destination.chmod(0o755)
        self.java = Path("Contents/runtime/Contents/Home/bin/java")
        self.xattr("-w", "com.apple.quarantine", "0083;00000000;BlockboxTest;", self.app)
        self.xattr("-w", "com.apple.quarantine", "0083;00000000;BlockboxTest;", self.app / self.java)
        self.xattr("-w", "com.blockbox.installer-test", "keep this attribute", self.app / self.java)
        assets = self.root / "assets"
        assets.mkdir()
        checksums = []
        for arch in ("arm64", "x64"):
            name = f"blockbox-macos-{arch}.dmg"
            content = f"fixture DMG for {arch}".encode()
            (assets / name).write_bytes(content)
            checksums.append(f"{hashlib.sha256(content).hexdigest()}  {name}\n")
        (assets / "SHA256SUMS.txt").write_text("".join(checksums))

    def install(self, *args, source=False):
        # stdin reproduces curl | sh: no common.sh or checkout is available.
        command = ["/bin/sh", str(self.checkout / "scripts/install-macos.sh"), *args] if source else ["/bin/sh", "-s", "--", *args]
        return subprocess.run(command, input=None if source else (SCRIPTS / "install-macos.sh").read_text(),
                              env=self.env, cwd=self.root, text=True, capture_output=True, timeout=20)

    def commands(self):
        return [json.loads(line) for line in (self.root / "commands.jsonl").read_text().splitlines()]

    def xattr(self, *args):
        return subprocess.check_output(["/usr/bin/xattr", *map(str, args)], text=True).strip()

    def existing_app(self):
        self.target.mkdir(parents=True)
        (self.target / "keep.txt").write_text("previous installation")

    def assert_clean(self):
        self.assertEqual([], list(self.downloads.iterdir()))
        self.assertFalse(Path(f"{self.target}.previous").exists())
        self.assertEqual([], list(self.apps.glob(".blockbox-install.*")))

    def test_standalone_install_clears_nested_quarantine_and_preserves_other_attributes(self):
        self.existing_app()
        result = self.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("com.apple.quarantine", self.xattr(self.target))
        self.assertNotIn("com.apple.quarantine", self.xattr(self.target / self.java))
        self.assertEqual("keep this attribute", self.xattr("-p", "com.blockbox.installer-test", self.target / self.java))
        self.assertFalse((self.target / "keep.txt").exists())
        self.assertFalse(any(command[0] == "java" for command in self.commands()))
        self.assertTrue(any(command[0] == "xattr" and command[-1].endswith(".dmg") for command in self.commands()))
        self.assert_clean()

    def test_selects_intel_and_native_apple_silicon_under_rosetta(self):
        for arm64, expected in (("0", "x64"), ("1", "arm64")):
            with self.subTest(arm64=arm64):
                self.env.update(FIXTURE_ARCH="x86_64", FIXTURE_ARM64=arm64)
                result = self.install()
                self.assertEqual(0, result.returncode, result.stdout + result.stderr)
                self.assertIn(f"blockbox-macos-{expected}.dmg", result.stdout)
                self.assert_clean()

    def test_unquarantined_app_also_installs(self):
        self.xattr("-d", "com.apple.quarantine", self.app)
        self.xattr("-d", "com.apple.quarantine", self.app / self.java)
        result = self.install()
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assert_clean()

    def test_failed_checksum_never_mounts_or_replaces_the_existing_app(self):
        self.existing_app()
        (self.root / "assets/blockbox-macos-arm64.dmg").write_bytes(b"corrupted download")
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checksum verification failed", result.stderr)
        self.assertEqual("previous installation", (self.target / "keep.txt").read_text())
        self.assertFalse(any(command[0] in ("hdiutil", "xattr") for command in self.commands()))
        self.assert_clean()

    def test_missing_checksum_never_mounts_the_dmg(self):
        (self.root / "assets/SHA256SUMS.txt").write_text("")
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("No release checksum", result.stderr)
        self.assertFalse(any(command[0] == "hdiutil" for command in self.commands()))
        self.assert_clean()

    def test_failures_preserve_the_existing_app_and_clean_temporary_files(self):
        self.existing_app()
        for failure in ("download", "copy", "quarantine", "move"):
            with self.subTest(failure=failure):
                self.env["FIXTURE_FAILURE"] = failure
                result = self.install()
                self.assertNotEqual(0, result.returncode)
                self.assertEqual("previous installation", (self.target / "keep.txt").read_text())
                self.assert_clean()

    def test_failed_detach_keeps_the_mounted_tree_and_existing_app(self):
        self.existing_app()
        self.env["FIXTURE_FAILURE"] = "detach"
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Could not eject", result.stderr)
        self.assertEqual("previous installation", (self.target / "keep.txt").read_text())
        self.assertTrue(list(self.downloads.glob(f"*/mount/{APP_NAME}")))
        self.assertEqual([], list(self.apps.glob(".blockbox-install.*")))

    def test_existing_backup_stops_before_downloading(self):
        Path(f"{self.target}.previous").mkdir(parents=True)
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("earlier installation backup", result.stderr)
        self.assertFalse(any(command[0] == "curl" for command in self.commands()))

    def test_rejects_non_macos_and_relative_install_directory(self):
        self.env["FIXTURE_OS"] = "Linux"
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Run this installer on macOS", result.stderr)
        self.env.update(FIXTURE_OS="Darwin", BLOCKBOX_APPLICATIONS_DIR="relative")
        result = self.install()
        self.assertNotEqual(0, result.returncode)
        self.assertIn("absolute path", result.stderr)

    def test_source_install_uses_cached_bundle_and_rebuild_reports_missing_java(self):
        self.checkout = self.root / "source checkout"
        (self.checkout / "scripts").mkdir(parents=True)
        for name in ("install-macos.sh", "common.sh"):
            shutil.copyfile(SCRIPTS / name, self.checkout / "scripts" / name)
        (self.checkout / "build.gradle.kts").touch()
        cached = self.checkout / "build/native" / APP_NAME
        cached.parent.mkdir(parents=True)
        subprocess.run(["/usr/bin/ditto", str(self.app), str(cached)], check=True)
        result = self.install("--from-source", source=True)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertFalse(any(command[0] in ("curl", "java") for command in self.commands()))
        self.assert_clean()
        self.env["BLOCKBOX_LAUNCHER_JAVA_HOME"] = str(self.bin.parent)
        result = self.install("--rebuild", source=True)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("Could not start Java", result.stderr)
        self.assertIn("Install JDK 21+", result.stderr)
        self.assert_clean()


if __name__ == "__main__":
    unittest.main()
