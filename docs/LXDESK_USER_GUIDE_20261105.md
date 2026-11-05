# LXDesk User Guide

## Installation
Install a verified official APK. First setup needs a validated network and adequate storage. Linux executable files remain inside the app private directory.

## Optional permissions
Normal startup asks for notifications on Android 13+. To enable camera, microphone, Bluetooth, media or location access, select Enable optional hardware permissions from the setup screen. All-files access may still be needed for public folders; document-picker grants are not enough for raw Linux file paths.

## Migration and backup safety
Use Storage location in the app shortcuts. The desktop must be stopped before moving files. Existing destination files are not overwritten, conflicting files halt migration, and old files remain backed up. Confirm every file before manually deleting old internal copies. The cleanup operation verifies backups against the destination.

## Diagnostics
Use Run desktop health check for read-only checks and a copyable report. Diagnostics are saved in ~/.config/linux-desktop/doctor.log. Copy debug info collects redacted, size-limited troubleshooting logs. Inspect output before sharing.

## Release and verification
GitHub CI is not a substitute for physical device tests. Verify initial install, XFCE startup, stop and restart, touch input, keyboard, audio, GPU/software rendering, external display and SD storage. Sign official releases with a private production key; the committed debug key is not suitable. Android scoped storage and older Termux target-SDK requirements remain compatibility limitations.
