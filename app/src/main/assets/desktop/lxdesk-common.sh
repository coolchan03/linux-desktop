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
