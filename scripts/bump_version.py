#!/usr/bin/env python3
"""Increment project version by 0.01 (or patch for semver) across key files."""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

DECIMAL_FILES = {
    Path("_version.py"): (r'VERSION\s*=\s*"(\d+\.\d{2})"', 'VERSION = "{value}"'),
    Path("speedtest.py"): (r'VERSION\s*=\s*"(\d+\.\d{2})"', 'VERSION = "{value}"'),
    Path("speedtest-win.ps1"): (r'\$VersionValue\s*=\s*"(\d+\.\d{2})"', '$VersionValue = "{value}"'),
    Path("java/src/main/java/com/speedintranet/SpeedIntranet.java"): (
        r'private\s+static\s+final\s+String\s+VERSION\s*=\s*"(\d+\.\d{2})";',
        'private static final String VERSION = "{value}";',
    ),
    Path("java/src/main/java/com/speedintranet/SpeedIntranetGui.java"): (
        r'frame\s*=\s*new\s+JFrame\("speed-intranet\sv(\d+\.\d{2})"\);',
        'frame = new JFrame("speed-intranet v{value}");',
    ),
    Path("build.sh"): (
        r'state\s*=\s*\{"version":\s*"(\d+\.\d{2})",\s*"program_hash":\s*""\}',
        'state = {"version": "{value}", "program_hash": ""}',
    ),
}

SEMVER_FILES = {
    Path("java/pom.xml"): (r"<version>(\d+)\.(\d+)\.(\d+)</version>", "<version>{major}.{minor}.{patch}</version>"),
}

DOC_REPLACEMENTS = [
    (Path("speedtest.py"), r"speed-intranet\sv(\d+\.\d+\.\d+)", "speed-intranet v{semver}"),
    (Path("README.md"), r"speed-intranet\sv(\d+\.\d+\.\d+)\s+—", "speed-intranet v{semver} —"),
    (Path("java/README.md"), r"speed-intranet-java8-(\d+\.\d+\.\d+)\\.jar", "speed-intranet-java8-{value}.jar"),
]


def bump_decimal(value: str) -> str:
    return f"{(float(value) + 0.01):.2f}"


def bump_semver(value: str) -> str:
    major, minor, patch = [int(x) for x in value.split(".")]
    patch += 1
    return f"{major}.{minor}.{patch}"


def replace_first(path: Path, pattern: str, replacer) -> str:
    text = path.read_text(encoding="utf-8")

    def _sub(match: re.Match) -> str:
        return replacer(match)

    new_text, count = re.subn(pattern, _sub, text, count=1, flags=re.MULTILINE)
    if count != 1:
        raise RuntimeError(f"Pattern not found exactly once in {path}")
    path.write_text(new_text, encoding="utf-8")
    return new_text


def main() -> int:
    version_decimal = None
    version_semver = None

    for rel_path, (pattern, fmt) in DECIMAL_FILES.items():
        path = ROOT / rel_path

        def repl(match: re.Match) -> str:
            nonlocal version_decimal
            current = match.group(1)
            if version_decimal is None:
                version_decimal = bump_decimal(current)
            return fmt.format(value=version_decimal)

        replace_first(path, pattern, repl)

    for rel_path, (pattern, fmt) in SEMVER_FILES.items():
        path = ROOT / rel_path

        def repl(match: re.Match) -> str:
            nonlocal version_semver
            current = f"{match.group(1)}.{match.group(2)}.{match.group(3)}"
            if version_semver is None:
                version_semver = bump_semver(current)
            major, minor, patch = version_semver.split(".")
            return fmt.format(major=major, minor=minor, patch=patch)

        replace_first(path, pattern, repl)

    for rel_path, pattern, fmt in DOC_REPLACEMENTS:
        path = ROOT / rel_path

        def repl(match: re.Match) -> str:
            if version_semver is None:
                raise RuntimeError("Semver version is not initialized")
            return fmt.format(value=version_semver, semver=version_semver)

        replace_first(path, pattern, repl)

    print(f"Version incremented: decimal={version_decimal}, semver={version_semver}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
