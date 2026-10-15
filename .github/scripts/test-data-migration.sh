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

bash "$script"
test -L "$HOME/Documents"
test "$(readlink "$HOME/Documents")" = "$target/Documents"
test -f "$target/Documents/sample.txt"
test "$(cat "$mark/datadir-applied")" = "$target"

backup="$(find "$mark" -mindepth 1 -maxdepth 1 -type d -name 'backup-Documents-*' -print -quit)"
test -n "$backup"
test -f "$backup/sample.txt"

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
