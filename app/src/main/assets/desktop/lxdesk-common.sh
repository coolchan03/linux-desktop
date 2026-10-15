#!/data/data/com.termux/files/usr/bin/bash
# Shared package requirements and bounded logs for LXDesk scripts.
# Installed into $PREFIX/bin by DesktopLauncher alongside the other scripts.
CORE_PACKAGES=(procps xorg-xrandr xkeyboard-config xfce4 xfce4-terminal thunar mousepad pulseaudio dbus mesa)
CORE_COMMANDS=(startxfce4 xfce4-session xfce4-panel xfwm4 xfdesktop xfce4-terminal thunar dbus-launch pulseaudio xrandr)

# Preserve the most recent 1 MiB log as .old rather than letting logs grow indefinitely.
lxdesk_rotate_log() {
  local file="$1" size
  [ -f "$file" ] || return 0
  size="$(wc -c < "$file" 2>/dev/null)" || return 0
  if [ "$size" -gt 1048576 ]; then
    mv -f "$file" "$file.old"
  fi
}

# ---- Packages bundled in the APK (liblxdesk-desktop-pack.so, built by scripts/lxdesk-desktop-pack.py) ----
LXDESK_PACK="@NATIVE_LIB_DIR@/liblxdesk-desktop-pack.so"
LXDESK_PACK_MARKER="LXDESK_DESKTOP_PACK_BEGIN_V1"

# apt restricted to an unpacked pack: no network, and the regular package lists are left alone.
lxdesk_pack_apt() {
  local dir="$1"
  shift
  DEBIAN_FRONTEND=noninteractive apt-get -o Dir::Etc::SourceList="$dir/sources.list" -o Dir::Etc::SourceParts=- \
    -o Dir::State::Lists="$dir/lists" -o APT::Get::List-Cleanup=false "$@"
}

# The pack is a tar that follows a marker near the start of the library. Prints where the tar starts,
# as a 1-based byte position for tail -c +N.
lxdesk_pack_start() {
  local offset
  [ -f "$LXDESK_PACK" ] || { echo "No desktop pack in this APK ($LXDESK_PACK)" >&2; return 1; }
  offset="$(head -c 4194304 "$LXDESK_PACK" | LC_ALL=C grep -obaF "$LXDESK_PACK_MARKER" | head -n 1 | cut -d: -f1)"
  [ -n "$offset" ] || { echo "This APK was built without desktop packages for $(uname -m)" >&2; return 1; }
  echo "$((offset + ${#LXDESK_PACK_MARKER} + 2))"
}

# Prints "package version architecture" for every bundled package without unpacking the pack.
lxdesk_pack_manifest() {
  local start
  start="$(lxdesk_pack_start)" || return 1
  tail -c +"$start" "$LXDESK_PACK" | tar -xO --occurrence=1 -f - manifest.txt
}

# Unpacks the pack into DIR as a local apt repository.
lxdesk_unpack_pack() {
  local dir="$1" start
  start="$(lxdesk_pack_start)" || return 1
  rm -rf "$dir"
  mkdir -p "$dir/lists/partial" || return 1
  tail -c +"$start" "$LXDESK_PACK" | tar -x -C "$dir" -f - || return 1
  [ -s "$dir/Packages" ] || { echo "The desktop pack is empty"; return 1; }
  printf 'deb [trusted=yes] file:%s ./\n' "$dir" > "$dir/sources.list"
  lxdesk_pack_apt "$dir" update
}

lxdesk_is_installed() {
  dpkg-query -W -f='${Status}' "$1" 2>/dev/null | grep -q 'install ok installed'
}
