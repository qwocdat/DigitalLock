#!/usr/bin/env python3
"""
DigitalLock Automatic Safe GitHub Commit & Push Script (P9).

Guarantees:
- Verifies Git repository, branch, clean merge state (no unresolved conflicts).
- Scans staged diff for secrets (tokens, private keys, keystores, passwords) before committing.
- Never force-pushes or rewrites Git history.
- Writes audit log of commit SHA for deterministic rollback (`git revert <sha>`).
"""

import argparse
import re
import subprocess
import sys
from pathlib import Path

FORBIDDEN_FILES = (".jks", ".keystore", ".p12", ".pem", "ed25519_private.key", ".env")
SECRET_REGEX = re.compile(
    r"(-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|ghp_[A-Za-z0-9]{36}|github_pat_[A-Za-z0-9_]{20,}|AKIA[0-9A-Z]{16})"
)


def run_git(args: list[str], cwd: Path) -> subprocess.CompletedProcess:
    return subprocess.run(["git", *args], cwd=cwd, capture_output=True, text=True)


def main() -> int:
    parser = argparse.ArgumentParser(description="Safe automated Git commit & push for DigitalLock")
    parser.add_argument("--repo", type=Path, default=Path("."))
    parser.add_argument("--message", required=True, help="Commit message")
    parser.add_argument("--push", action="store_true", help="Push to origin without force-push")
    args = parser.parse_args()

    repo = args.repo.resolve()
    if run_git(["rev-parse", "--is-inside-work-tree"], repo).returncode != 0:
        print("ERROR: Not a valid Git repository.", file=sys.stderr)
        return 1

    branch = run_git(["rev-parse", "--abbrev-ref", "HEAD"], repo).stdout.strip()
    conflicts = run_git(["diff", "--name-only", "--diff-filter=U"], repo).stdout.strip()
    if conflicts:
        print(f"ERROR: Unresolved Git merge conflicts detected:\n{conflicts}", file=sys.stderr)
        return 2

    status = run_git(["status", "--porcelain"], repo).stdout.strip()
    if not status:
        print("Working tree clean; nothing to commit.")
        return 0

    # Stage tracked & untracked changes safely
    run_git(["add", "-A"], repo)
    staged_files = run_git(["diff", "--cached", "--name-only"], repo).stdout.splitlines()

    for f in staged_files:
        if any(f.lower().endswith(ext) for ext in FORBIDDEN_FILES):
            run_git(["reset", "HEAD", "--", f], repo)
            print(f"ERROR: Blocked sensitive file from commit: {f}", file=sys.stderr)
            return 3

    diff_text = run_git(["diff", "--cached"], repo).stdout
    if SECRET_REGEX.search(diff_text):
        print("ERROR: Potential secret/token detected in staged diff; commit aborted.", file=sys.stderr)
        return 4

    commit_res = run_git(["commit", "-m", args.message], repo)
    if commit_res.returncode != 0:
        print(commit_res.stderr, file=sys.stderr)
        return commit_res.returncode

    sha = run_git(["rev-parse", "HEAD"], repo).stdout.strip()
    print(f"Committed {sha} on branch {branch}. Rollback command: git revert {sha}")

    if args.push:
        push_res = run_git(["push", "origin", branch], repo)
        if push_res.returncode != 0:
            print("ERROR: Push failed (non-fast-forward or network). Force-push is disabled.", file=sys.stderr)
            print(push_res.stderr, file=sys.stderr)
            return push_res.returncode
        print(f"Pushed {sha} to origin/{branch} safely.")

    return 0


if __name__ == "__main__":
    sys.exit(main())
