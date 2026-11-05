# LXDesk five-phase code overhaul

This working plan covers `coolchan03/linux-desktop` only. Local branch: `phase1/security-data-safety`.
No C: drive operations and no changes to unrelated Android projects.

## Phase 1 — security and user-data protection
- [x] Reject conflicting destination data during copy rather than overwrite.
- [x] Compare backup contents with external destination before purge.
- [x] Stage and verify rewritten Debian packages before replacement.
- [x] Limit embedded X11 broadcast receivers to the application; verify app_process startup on a device.
- [x] Enable native stack-protector compiler protection.
- [ ] Replace raw SAF tree URI assumptions with persisted URI permissions or a supported app-owned directory.
- [ ] Add migration concurrency lock and test interruption/rollback on Android.
- [ ] Validate X11 `-ac` access-control requirements, then remove or constrain it safely.

## Phase 2 — startup and installation
- [x] Treat unknown internal free space as unsafe, not unlimited.
- [x] Require a validated network for initial install.
- [ ] Replace the 900ms wait for desktop termination with confirmed process exit.
- [ ] Add atomic setup locks and recovery state machine for interrupted installs.
- [ ] Validate cold install, upgrade, stop, relaunch and software GPU fallback on device.

## Phase 3 — code quality and performance
- [x] Compare installed launcher scripts against APK assets every time.
- [x] Stage script rewrites before renaming them over existing executables.
- [ ] Split DesktopLaunchActivity into storage, setup, and diagnostics components.
- [ ] Remove dead state and audit resource usage with Android profiler.

## Phase 4 — tests, release, documentation
- [x] Add regression test for migration into a conflicting destination.
- [ ] Run Linux shell tests, Android unit tests, release build and on-device smoke tests.
- [ ] Document signing, verified upgrade, restore and Android storage caveats.
- [ ] Expand dependency update checks and supply-chain review.

## Phase 5 — features
- [x] Add `linux-desktop-doctor` read-only diagnostic command.
- [ ] Surface doctor results in a graphical Repair Center.
- [ ] Add phone/tablet/external-display presets and touch scaling controls.
- [ ] Add snapshot/restore UI and safe package management.

**Release gate:** Do not ship or merge until tests pass and embedded X11 launches on a real Android device. `bash` was unavailable on the connected Windows shell; device tests were not run. GitHub branch creation returned permission error 403, so GitHub publication is unconfirmed.
