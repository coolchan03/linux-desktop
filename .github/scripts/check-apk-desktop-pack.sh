#!/usr/bin/env bash
# Checks the offline desktop pack inside a built APK, reading it exactly like linux-desktop-setup does.
# Usage: check-apk-desktop-pack.sh APK ABI yes|no
set -euo pipefail

APK="$1" ABI="$2" EXPECTED="$3"
PACK_MARKER="LXDESK_DESKTOP_PACK_BEGIN_V1"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
fail() { echo "FAIL ($APK, $ABI): $*" >&2; exit 1; }

unzip -q -o "$APK" "lib/$ABI/liblxdesk-desktop-pack.so" -d "$WORK" || fail "liblxdesk-desktop-pack.so is missing"
PACK="$WORK/lib/$ABI/liblxdesk-desktop-pack.so"
offset="$(head -c 4194304 "$PACK" | LC_ALL=C grep -obaF "$PACK_MARKER" | head -n 1 | cut -d: -f1 || true)"

if [ "$EXPECTED" = no ]; then
  [ -z "$offset" ] || fail "unexpected desktop pack"
  echo "OK: $ABI has no desktop pack, as expected"
  exit 0
fi
[ -n "$offset" ] || fail "no desktop pack marker"

mkdir "$WORK/bundle"
tail -c +"$((offset + ${#PACK_MARKER} + 2))" "$PACK" | tar -x -C "$WORK/bundle" -f -
[ -s "$WORK/bundle/Packages" ] || fail "Packages index missing"

python3 - "$WORK/bundle" <<'EOF'
import hashlib, os, sys
root = sys.argv[1]
names = set()
for stanza in open(os.path.join(root, "Packages")).read().strip().split("\n\n"):
    fields = dict(line.split(": ", 1) for line in stanza.splitlines() if ": " in line)
    path = os.path.join(root, fields["Filename"])
    with open(path, "rb") as f:
        assert hashlib.sha256(f.read()).hexdigest() == fields["SHA256"], fields["Package"]
    names.add(fields["Package"])
for core in ("xfce4", "xfce4-session", "xfwm4", "thunar", "dbus", "mesa", "pulseaudio", "x11-repo"):
    assert core in names, "missing core package " + core
print("%d packages, all checksums match" % len(names))
EOF

for deb in "$WORK"/bundle/debs/*.deb; do
  [ "$(dpkg-deb -f "$deb" LXDesk-Relocated)" = com.lxdesk ] || fail "$(basename "$deb") is not relocated"
done
echo "OK: $ABI carries the desktop ($(du -sh "$WORK/bundle/debs" | cut -f1))"
