#!/usr/bin/env bash
# Tests scripts/lxdesk-desktop-pack.py against a small fake Termux repository served over local HTTP:
# dependency resolution against the bootstrap, relocation of every kind of file and apt's view of the pack.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="$(mktemp -d)"
SERVER_PID=""
cleanup() {
  [ -z "$SERVER_PID" ] || kill "$SERVER_PID" 2>/dev/null || true
  rm -rf "$WORK"
}
trap cleanup EXIT
fail() { echo "FAIL: $*" >&2; exit 1; }

OLD=/data/data/com.termux/files/usr
NEW=/data/data/com.lxdesk/files/usr
ARCH=aarch64

# make_deb NAME VERSION "CONTROL FIELDS" [setup function]
make_deb() {
  local name="$1" version="$2" fields="$3" tree="$WORK/src/$1"
  mkdir -p "$tree/DEBIAN" "$tree$OLD/share/$name"
  printf 'Package: %s\nVersion: %s\nArchitecture: %s\nMaintainer: test\nDescription: test package %s\n%s' \
    "$name" "$version" "$ARCH" "$name" "$fields" > "$tree/DEBIAN/control"
  printf 'prefix=%s\n' "$OLD" > "$tree$OLD/share/$name/info"
  if [ -n "${4:-}" ]; then "$4" "$tree"; fi
  (cd "$tree" && find data -type f -exec md5sum {} + > DEBIAN/md5sums)
  dpkg-deb -Zxz --root-owner-group -b "$tree" "$WORK/repo/pool/${name}_${version}_$ARCH.deb" >/dev/null
}

xfce_files() {
  local tree="$1"
  mkdir -p "$tree$OLD/bin" "$tree$OLD/lib" "$tree$OLD/etc"
  # A binary with the prefix embedded between NUL bytes, as in ELF string tables.
  printf '\x7fELF\x00\x01%s/lib\x00/data/user/0/com.termux/cache\x00\xff' "$OLD" > "$tree$OLD/bin/startxfce4"
  chmod 755 "$tree$OLD/bin/startxfce4"
  printf '#!%s/bin/sh\necho hi\n' "$OLD" > "$tree$OLD/bin/xfce4-session"
  chmod 755 "$tree$OLD/bin/xfce4-session"
  ln -s "$OLD/bin/startxfce4" "$tree$OLD/bin/xfce-absolute-link"
  ln -s startxfce4 "$tree$OLD/bin/xfce-relative-link"
  ln "$tree$OLD/bin/xfce4-session" "$tree$OLD/bin/xfce4-session-hardlink"
  printf 'conf=%s/etc\n' "$OLD" > "$tree$OLD/etc/xfce.conf"
  printf '%s/etc/xfce.conf\n' "$OLD" > "$tree/DEBIAN/conffiles"
  printf '#!%s/bin/sh\nmkdir -p %s/var/lib/xfce\n' "$OLD" "$OLD" > "$tree/DEBIAN/postinst"
  chmod 755 "$tree/DEBIAN/postinst"
}

mkdir -p "$WORK/repo/pool" "$WORK/src"
make_deb libfoo 2.0 $'Depends: libc++\n'
make_deb libc++ 1.0 ''
make_deb libbar 1.0 $'Depends: libbaz | libqux, virtual-thing\nRecommends: helper\n'
make_deb libbaz 1.0 ''
make_deb libqux 1.0 ''
make_deb provider 1.0 $'Provides: virtual-thing, qemu-system-x86_64-headless\n'
make_deb helper 1.0 ''
make_deb unrelated 1.0 ''
make_deb oldtool 2.0 ''
make_deb xfce4 4.20 $'Depends: libfoo (>= 2.0), libbar, bash\nBreaks: oldtool (<< 2.0)\n' xfce_files
make_deb mesa-vulkan-icd-freedreno 1.0 ''

(cd "$WORK/repo" && dpkg-scanpackages -m pool /dev/null 2>/dev/null > "$WORK/Packages")
mkdir -p "$WORK/repo/dists/stable/main/binary-$ARCH"
xz -c "$WORK/Packages" > "$WORK/repo/dists/stable/main/binary-$ARCH/Packages.xz"
{
  echo "Origin: test"
  echo "SHA256:"
  printf ' %s %s main/binary-%s/Packages.xz\n' \
    "$(sha256sum "$WORK/repo/dists/stable/main/binary-$ARCH/Packages.xz" | cut -d' ' -f1)" \
    "$(wc -c < "$WORK/repo/dists/stable/main/binary-$ARCH/Packages.xz")" "$ARCH"
} > "$WORK/repo/dists/stable/Release"

# Bootstrap: an old libfoo (must be upgraded), bash (must not be bundled), an old oldtool (broken by xfce4).
mkdir -p "$WORK/bootstrap/var/lib/dpkg"
cat > "$WORK/bootstrap/var/lib/dpkg/status" <<EOF
Package: libfoo
Status: install ok installed
Version: 1.0
Architecture: $ARCH

Package: bash
Status: install ok installed
Version: 5.2
Architecture: $ARCH

Package: libc++
Status: install ok installed
Version: 1.0
Architecture: $ARCH

Package: oldtool
Status: install ok installed
Version: 1.0
Architecture: $ARCH
EOF
(cd "$WORK/bootstrap" && python3 -c 'import zipfile; z = zipfile.ZipFile("../bootstrap.zip", "w"); z.write("var/lib/dpkg/status"); z.close()')

PORT="$(python3 -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')"
python3 -m http.server "$PORT" --bind 127.0.0.1 --directory "$WORK/repo" >/dev/null 2>&1 &
SERVER_PID=$!
for _ in $(seq 50); do
  curl -sf --noproxy '*' "http://127.0.0.1:$PORT/dists/stable/Release" >/dev/null && break
  sleep 0.1
done

printf 'CORE_PACKAGES=(xfce4)\n' > "$WORK/common.sh"
run_pack() {
  NO_PROXY='*' no_proxy='*' python3 "$ROOT/scripts/lxdesk-desktop-pack.py" --arch "$ARCH" \
    --repo "http://127.0.0.1:$PORT stable main" --bootstrap "$WORK/bootstrap.zip" \
    --packages-from "$WORK/common.sh" --optional mesa-vulkan-icd-freedreno --optional not-in-repo \
    --new-pkg com.lxdesk --cache "$WORK/cache" --output "$WORK/pack.tar" "$@"
}
run_pack

mkdir -p "$WORK/pack"
tar -xf "$WORK/pack.tar" -C "$WORK/pack"
bundled="$(cut -d' ' -f1 "$WORK/pack/manifest.txt" | sort | tr '\n' ' ')"
expected="helper libbar libbaz libfoo mesa-vulkan-icd-freedreno oldtool provider xfce4 "
[ "$bundled" = "$expected" ] || fail "bundled '$bundled', expected '$expected'"

# Every bundled package matches the Packages index apt will read.
python3 - "$WORK/pack" <<'EOF'
import hashlib, os, sys
root = sys.argv[1]
for stanza in open(os.path.join(root, "Packages")).read().strip().split("\n\n"):
    fields = dict(line.split(": ", 1) for line in stanza.splitlines() if ": " in line)
    data = open(os.path.join(root, fields["Filename"]), "rb").read()
    assert hashlib.sha256(data).hexdigest() == fields["SHA256"], fields["Package"]
    assert len(data) == int(fields["Size"]), fields["Package"]
EOF

deb="$(ls "$WORK/pack/debs/"xfce4_*.deb)"
dpkg-deb -f "$deb" LXDesk-Relocated | grep -qx com.lxdesk || fail "relocation marker missing"
dpkg-deb -R "$deb" "$WORK/xfce"
[ -d "$WORK/xfce$NEW" ] || fail "prefix directory not renamed"
[ ! -e "$WORK/xfce/data/data/com.termux" ] || fail "old prefix directory left behind"
! grep -rqa 'com\.termux' "$WORK/xfce" || fail "old package name left in: $(grep -rla 'com\.termux' "$WORK/xfce")"
[ "$(readlink "$WORK/xfce$NEW/bin/xfce-absolute-link")" = "$NEW/bin/startxfce4" ] || fail "absolute symlink not relocated"
[ "$(readlink "$WORK/xfce$NEW/bin/xfce-relative-link")" = "startxfce4" ] || fail "relative symlink changed"
[ "$(stat -c %i "$WORK/xfce$NEW/bin/xfce4-session")" = "$(stat -c %i "$WORK/xfce$NEW/bin/xfce4-session-hardlink")" ] || fail "hard link lost"
[ -x "$WORK/xfce$NEW/bin/startxfce4" ] && [ -x "$WORK/xfce/DEBIAN/postinst" ] || fail "executable bits lost"
grep -qx "$NEW/etc/xfce.conf" "$WORK/xfce/DEBIAN/conffiles" || fail "conffiles not relocated"
cmp -s <(printf '\x7fELF\x00\x01%s/lib\x00/data/user/0/com.lxdesk/cache\x00\xff' "$NEW") "$WORK/xfce$NEW/bin/startxfce4" || fail "binary not relocated byte for byte"
(cd "$WORK/xfce" && md5sum --quiet -c DEBIAN/md5sums) || fail "md5sums do not match the relocated files"
grep -q "data/data/com.lxdesk/files/usr/bin/startxfce4" "$WORK/xfce/DEBIAN/md5sums" || fail "md5sums not relocated"

# apt resolves the offline install from the pack alone, on top of the bootstrap's packages.
APT="$WORK/apt"
mkdir -p "$APT/lists/partial" "$APT/cache/archives/partial" "$APT/etc/apt.conf.d" "$APT/etc/preferences.d"
cp "$WORK/bootstrap/var/lib/dpkg/status" "$APT/status"
printf 'deb [trusted=yes] file:%s ./\n' "$WORK/pack" > "$APT/sources.list"
apt_opts=(-o Dir::State::status="$APT/status" -o Dir::State::Lists="$APT/lists" -o Dir::Cache="$APT/cache"
  -o Dir::Etc::SourceList="$APT/sources.list" -o Dir::Etc::SourceParts=- -o Dir::Etc::Parts="$APT/etc/apt.conf.d"
  -o Dir::Etc::PreferencesParts="$APT/etc/preferences.d" -o APT::Architecture="$ARCH" -o APT::Architectures="$ARCH"
  -o Debug::NoLocking=1)
apt-get "${apt_opts[@]}" update >/dev/null
apt-get "${apt_opts[@]}" -s install xfce4 > "$WORK/simulate.log" || { cat "$WORK/simulate.log"; fail "apt cannot install from the pack"; }
grep -q '^Inst xfce4 ' "$WORK/simulate.log" || fail "apt does not install xfce4"
grep -q '^Inst libfoo \[1.0\] (2.0' "$WORK/simulate.log" || fail "apt does not upgrade the bootstrap's libfoo"

# The production path: embed the pack with the app's own assembly file, then let the shell helpers of
# lxdesk-common.sh find, list, unpack and install from it. APT_CONFIG keeps apt away from the host's state.
if command -v clang >/dev/null && command -v ld.lld >/dev/null; then
  ELF="$WORK/elf"
  mkdir -p "$ELF/lib"
  cp "$ROOT/app/src/main/cpp/lxdesk-desktop-pack.S" "$ROOT/app/src/main/cpp/lxdesk-desktop-pack.c" "$ELF/"
  cp "$WORK/pack.tar" "$ELF/desktop-pack-aarch64.tar"
  printf '#define LXDESK_PACK_AARCH64 1\n#define LXDESK_PACK_ARM 0\n#define LXDESK_PACK_I686 0\n#define LXDESK_PACK_X86_64 0\n' > "$ELF/lxdesk-desktop-pack-stamp.h"
  (cd "$ELF" &&
    clang --target=aarch64-linux-android24 -fPIC -I. -c lxdesk-desktop-pack.S -o pack.o &&
    clang --target=aarch64-linux-android24 -fPIC -std=c11 -Wall -Wextra -Werror -Os -c lxdesk-desktop-pack.c -o ref.o &&
    ld.lld -shared -z max-page-size=16384 --gc-sections pack.o ref.o -o lib/liblxdesk-desktop-pack.so)
  sed "s#@NATIVE_LIB_DIR@#$ELF/lib#" "$ROOT/app/src/main/assets/desktop/lxdesk-common.sh" > "$WORK/lxdesk-common.sh"
  printf 'Dir::State::status "%s";\nDir::Cache "%s";\nDir::Etc::Parts "%s";\nDir::Etc::PreferencesParts "%s";\nAPT::Architecture "%s";\nAPT::Architectures { "%s"; };\nDebug::NoLocking "1";\n' \
    "$APT/status" "$APT/cache" "$APT/etc/apt.conf.d" "$APT/etc/preferences.d" "$ARCH" "$ARCH" > "$WORK/apt.conf"
  APT_CONFIG="$WORK/apt.conf" bash -c '
    set -euo pipefail
    . "$1"
    lxdesk_pack_manifest | grep -q "^xfce4 4.20 " || { echo "FAIL: manifest not readable from the library"; exit 1; }
    lxdesk_unpack_pack "$2/bundle" >/dev/null
    lxdesk_pack_apt "$2/bundle" -s install xfce4 x11-thing-that-is-missing >/dev/null 2>&1 && { echo "FAIL: unknown package accepted"; exit 1; }
    lxdesk_pack_apt "$2/bundle" -s install xfce4 | grep -q "^Inst xfce4 " || { echo "FAIL: cannot install from the embedded pack"; exit 1; }
  ' _ "$WORK/lxdesk-common.sh" "$WORK" || fail "installing from the embedded pack"
else
  echo "NOTE: clang/ld.lld not available; skipping the embedded-library check"
fi

# Second run is served from the cache, and a broken repository keeps the existing pack with --keep-going.
before="$(sha256sum "$WORK/pack.tar")"
run_pack
[ "$before" = "$(sha256sum "$WORK/pack.tar")" ] || fail "rebuilding from cache is not reproducible"
kill "$SERVER_PID"; wait "$SERVER_PID" 2>/dev/null || true; SERVER_PID=""
run_pack --keep-going 2>/dev/null
[ "$before" = "$(sha256sum "$WORK/pack.tar")" ] || fail "--keep-going replaced the existing pack"
if run_pack 2>/dev/null; then fail "missing repository did not fail without --keep-going"; fi

echo "PASS: LXDesk offline desktop pack"
