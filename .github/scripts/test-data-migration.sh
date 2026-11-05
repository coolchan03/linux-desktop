#!/usr/bin/env bash
# Regression tests for migration and explicit cleanup. Operates ONLY in a new temporary directory.
set -euo pipefail
# Git Bash on Windows does not reliably create POSIX symlinks. GitHub CI runs on Linux.
if [ "$(uname -s)" != Linux ]; then
  echo 'SKIP: migration symlink integration test requires Linux (runs in CI)'
  exit 0
fi
root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
script="$root/app/src/main/assets/desktop/linux-desktop-data"
test -f "$script"

sandbox="$(mktemp -d)"
trap 'rm -rf -- "$sandbox"' EXIT
export HOME="$sandbox/home"
mark="$HOME/.config/linux-desktop"
target="$sandbox/external"
mkdir -p "$mark" "$HOME/Documents" "$target"
printf 'LXDesk migration sample\n' > "$HOME/Documents/sample.txt"
printf '%s\n' "$target" > "$mark/datadir"

# Existing destination content must never be overwritten by migration.
mkdir -p "$target/Documents"
printf 'separate destination data\n' > "$target/Documents/sample.txt"
if bash "$script"; then
  echo 'ERROR: migration overwrote a conflicting destination file' >&2
  exit 1
fi
test "$(cat "$target/Documents/sample.txt")" = 'separate destination data'
test -f "$HOME/Documents/sample.txt"
test ! -L "$HOME/Documents"
rm -f "$target/Documents/sample.txt"

bash "$script"
test ! -d "$mark/data.lock"
test -L "$HOME/Documents"
test "$(readlink "$HOME/Documents")" = "$target/Documents"
test -f "$target/Documents/sample.txt"
test "$(cat "$mark/datadir-applied")" = "$target"

backup="$(find "$mark" -mindepth 1 -maxdepth 1 -type d -name 'backup-Documents-*' -print -quit)"
test -n "$backup"
test -f "$backup/sample.txt"

# A fresh lock must prevent concurrent cleanup without touching originals.
mkdir "$mark/data.lock"
printf '%s\n' "$$" > "$mark/data.lock/pid"
if bash "$script" --purge-backups; then
  echo 'ERROR: concurrent storage operation ignored lock' >&2
  exit 1
fi
test -f "$backup/sample.txt"
rm "$mark/data.lock/pid"
rmdir "$mark/data.lock"

# An abandoned lock older than one minute can be recovered.
mkdir "$mark/data.lock"
printf '999999\n' > "$mark/data.lock/pid"
touch -d '2 minutes ago' "$mark/data.lock"

# Cleanup must never run silently. The explicit command removes old internal copies.
bash "$script" --purge-backups
test -f "$target/Documents/sample.txt"
test ! -d "$backup"

# Refuse if the chosen destination is not the previously applied location.
newbackup="$mark/backup-Documents-100-200"
mkdir -p "$newbackup"
printf 'must preserve\n' > "$newbackup/preserve.txt"
printf '%s\n' "$sandbox/other-destination" > "$mark/datadir"
if bash "$script" --purge-backups; then
  echo 'ERROR: cleanup accepted an unapplied destination' >&2
  exit 1
fi
test -f "$newbackup/preserve.txt"
printf '%s\n' "$target" > "$mark/datadir"

# Refuse if a live home link no longer points to the applied destination.
rm -f "$HOME/Documents"
mkdir -p "$HOME/Documents"
if bash "$script" --purge-backups; then
  echo 'ERROR: cleanup accepted a mismatched home directory' >&2
  exit 1
fi
test -f "$newbackup/preserve.txt"
echo 'PASS: LXDesk data migration and guarded manual backup cleanup'
