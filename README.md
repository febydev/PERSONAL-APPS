# Private Media Vault

A native Android app that stores sensitive images and videos in a PIN-protected,
blurred-by-default, locally encrypted vault. It runs 100% offline — no network
permissions, no cloud, no sync. All data stays on the device.

## How to get the app onto your phone (no Android Studio needed)

This project builds in the cloud with GitHub Actions, so you don't need a JDK or the
Android SDK on your own computer.

1. Push this project to GitHub (the `main` branch).
2. Go to the repo's **Actions** tab. The **Build APK** workflow runs automatically on
   every push — or click **Run workflow** to start it manually.
3. When the run finishes (green check), open it and download the **app-debug-apk**
   artifact from the **Artifacts** section. It contains `app-debug.apk`.
4. Copy that APK to your Android phone and tap it to install. You'll need to allow
   "install from unknown sources" the first time.

> The first build may fail with compile errors — the code was written but had never
> been compiled on a machine with the Android toolchain. Read the failed step's log in
> the Actions tab to see what needs fixing.

## What's inside

- 100% offline: no `INTERNET` permission, no networking libraries, backups disabled.
- PIN auth with Argon2id hashing and a 5-attempt / 30-second lockout.
- AES-256-GCM encryption (Android Keystore + Tink streaming for large videos).
- Blurred-by-default vault grid; unblur/re-blur only within an authenticated session.
- Auto-lock on background, `FLAG_SECURE` to block screenshots and the recents preview.
- Import, export, delete, and PIN change.

## Project layout

- `app/src/main/java/com/privatemediavault/` — application code (domain, data, ui, viewmodel)
- `app/src/test/` — JVM unit and property-based tests (jqwik)
- `app/src/androidTest/` — Android instrumentation tests
- `.kiro/specs/private-media-vault/` — requirements, design, and task plan
