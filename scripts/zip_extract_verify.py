#!/usr/bin/env python3
"""
DigitalLock Automatic Safe ZIP Extraction & Structure Verifier (P8).

Security & Reliability Guarantees:
- Rejects ZIP Slip (path traversal outside staging directory) and symlinks.
- Rejects ZIP Bombs (checks uncompressed total size & compression ratio cap).
- Never executes any script contained inside the ZIP.
- Detects project root, Gradle settings, build scripts, AndroidManifest, Kotlin, C++, Rust Cargo.toml.
- Detects duplicate paths and scans for leaked secrets (private keys, keystores, tokens).
- Extracts into a temporary staging workspace first; applies to repo only if all checks pass,
  while strictly protecting `.github/workflows/`.
"""

import argparse
import hashlib
import os
import re
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path

MAX_TOTAL_BYTES = 150 * 1024 * 1024  # 150 MB
MAX_FILES = 5000
MAX_COMPRESSION_RATIO = 100.0

SECRET_FILENAME_PATTERNS = (
    ".jks",
    ".keystore",
    ".p12",
    "ed25519_private.key",
    "id_rsa",
    ".env",
)
SECRET_CONTENT_REGEX = re.compile(
    r"(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|AKIA[0-9A-Z]{16})"
)


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()


def verify_and_extract_zip(zip_path: Path, staging_dir: Path) -> tuple[Path, list[str]]:
    if not zip_path.exists() or not zipfile.is_zipfile(zip_path):
        raise ValueError(f"Invalid or missing ZIP archive: {zip_path}")

    seen_names: set[str] = set()
    duplicates: list[str] = []
    total_uncompressed = 0

    with zipfile.ZipFile(zip_path, "r") as zf:
        infos = zf.infolist()
        if len(infos) > MAX_FILES:
            raise ValueError(f"ZIP file count exceeds safety limit ({len(infos)} > {MAX_FILES})")

        for info in infos:
            # Anti-symlink check (Unix symlink mode 0o120000)
            if (info.external_attr >> 16) & 0o170000 == 0o120000:
                raise ValueError(f"Symlink entry forbidden in ZIP: {info.filename}")

            norm = os.path.normpath(info.filename)
            if os.path.isabs(info.filename) or norm.startswith("..") or ".." in Path(info.filename).parts:
                raise ValueError(f"ZIP Slip path traversal detected: {info.filename}")

            if info.filename in seen_names:
                duplicates.append(info.filename)
            seen_names.add(info.filename)

            total_uncompressed += info.file_size
            if total_uncompressed > MAX_TOTAL_BYTES:
                raise ValueError(f"ZIP Bomb suspected: uncompressed size > {MAX_TOTAL_BYTES} bytes")
            if info.compress_size > 0 and (info.file_size / info.compress_size) > MAX_COMPRESSION_RATIO:
                raise ValueError(f"ZIP Bomb suspected (ratio > {MAX_COMPRESSION_RATIO}): {info.filename}")

            target = (staging_dir / info.filename).resolve()
            if not str(target).startswith(str(staging_dir.resolve())):
                raise ValueError(f"Resolved path escaped staging dir: {info.filename}")

        zf.extractall(staging_dir)

    # Detect single top-level wrapper folder (e.g., DigitalLock-main/)
    entries = [p for p in staging_dir.iterdir() if p.name != "__MACOSX"]
    project_root = entries[0] if len(entries) == 1 and entries[0].is_dir() else staging_dir
    return project_root, duplicates


def scan_structure_and_secrets(root: Path) -> dict:
    findings = {
        "gradle_settings": [],
        "build_scripts": [],
        "manifests": [],
        "kotlin_count": 0,
        "cpp_count": 0,
        "rust_cargo": [],
        "secret_warnings": [],
    }
    for p in root.rglob("*"):
        if not p.is_file():
            continue
        rel = p.relative_to(root).as_posix()
        name_lower = p.name.lower()
        if p.name in ("settings.gradle", "settings.gradle.kts"):
            findings["gradle_settings"].append(rel)
        elif p.name in ("build.gradle", "build.gradle.kts"):
            findings["build_scripts"].append(rel)
        elif p.name == "AndroidManifest.xml":
            findings["manifests"].append(rel)
        elif p.suffix == ".kt":
            findings["kotlin_count"] += 1
        elif p.suffix in (".cpp", ".cc", ".h", ".hpp"):
            findings["cpp_count"] += 1
        elif p.name == "Cargo.toml":
            findings["rust_cargo"].append(rel)

        if any(name_lower.endswith(pat) for pat in SECRET_FILENAME_PATTERNS):
            findings["secret_warnings"].append(f"Secret filename detected: {rel}")
        elif p.stat().st_size <= 512 * 1024:
            try:
                text = p.read_text(encoding="utf-8", errors="ignore")
                if SECRET_CONTENT_REGEX.search(text):
                    findings["secret_warnings"].append(f"Potential secret pattern in: {rel}")
            except OSError:
                pass
    return findings


def apply_to_repo(src_root: Path, repo_root: Path, dry_run: bool) -> dict:
    added, modified, skipped = [], [], []
    for src_file in src_root.rglob("*"):
        if not src_file.is_file():
            continue
        rel = src_file.relative_to(src_root).as_posix()
        # Protect workflow directory and git metadata
        if rel.startswith(".git/") or rel.startswith(".github/workflows/"):
            skipped.append(rel)
            continue
        dst_file = repo_root / rel
        if not dst_file.exists():
            added.append(rel)
            if not dry_run:
                dst_file.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(src_file, dst_file)
        else:
            if sha256_file(src_file) != sha256_file(dst_file):
                modified.append(rel)
                if not dry_run:
                    shutil.copy2(src_file, dst_file)
    return {"added": added, "modified": modified, "skipped_protected": skipped}


def main() -> int:
    parser = argparse.ArgumentParser(description="Safe ZIP extraction and verification for DigitalLock")
    parser.add_argument("zip_file", type=Path)
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--apply", action="store_true", help="Apply verified files to repository")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="digitallock_zip_stage_") as tmp:
        stage = Path(tmp)
        project_root, duplicates = verify_and_extract_zip(args.zip_file, stage)
        structure = scan_structure_and_secrets(project_root)

        print("=== ZIP VERIFICATION REPORT ===")
        print(f"Detected root: {project_root.name}")
        print(f"Gradle settings: {structure['gradle_settings']}")
        print(f"Build scripts:   {structure['build_scripts']}")
        print(f"Manifests:       {structure['manifests']}")
        print(f"Kotlin files:    {structure['kotlin_count']}")
        print(f"C++ files:       {structure['cpp_count']}")
        print(f"Rust Cargo.toml: {structure['rust_cargo']}")
        print(f"Duplicates:      {len(duplicates)}")

        if structure["secret_warnings"]:
            print("ERROR: Secrets detected inside ZIP; aborting!", file=sys.stderr)
            for w in structure["secret_warnings"]:
                print(f"  - {w}", file=sys.stderr)
            return 2

        diff = apply_to_repo(project_root, args.repo.resolve(), dry_run=not args.apply)
        print(f"Added: {len(diff['added'])}, Modified: {len(diff['modified'])}, Protected skipped: {len(diff['skipped_protected'])}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
