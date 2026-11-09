# Claude Linux (Android)

A small Android app that runs **Alpine Linux** in-app (no root) and installs **Claude Code** in it,
with a settings screen for a **custom API endpoint / key / model**.

## How Horus (the reference APK) runs Linux — what I found

| Piece | What it does |
|---|---|
| `libproot.so`, `libproot_loader.so` | Termux's **PRoot** (patched for newer Android seccomp), shipped as *JNI libraries* so they land in `nativeLibraryDir`. Android 10+ blocks `exec` from app data, but allows it from there. Loader is passed via `PROOT_LOADER`. |
| `libhorus_pty.so` | Tiny JNI PTY helper: `posix_openpt` → `fork/exec` → read/write master fd. Gives a real terminal. |
| Rootfs | **Alpine minirootfs (aarch64)** downloaded on first launch (or Debian trixie), SHA-256 verified, unpacked into `filesDir/rootfs`. |
| Bootstrap | Runs inside proot: `apk add` nodejs, git, zsh, ripgrep… then installs Claude Code (npm / `install.sh`). |
| Launch | `proot --kill-on-exit --link2symlink -0 -r rootfs -b /dev -b /proc -b /sys … /bin/…` — `-0` fakes root, `--link2symlink` emulates hard links. |
| UI | React Native + custom terminal emulator; a foreground service keeps sessions alive. |

## What this project does differently
Plain Kotlin app. Terminal = Termux's `terminal-view`/`terminal-emulator` (which already include PTY JNI).
Same PRoot binaries (copied from Horus's APK into `jniLibs/arm64-v8a/`).

* `Linux.kt` – download/verify/unpack Alpine, build the proot command line + environment
* `assets/bootstrap.sh` – first-run install of packages + Claude Code
* `Config.kt` – turns your settings into `ANTHROPIC_BASE_URL`, `ANTHROPIC_API_KEY` or `ANTHROPIC_AUTH_TOKEN`,
  `ANTHROPIC_MODEL`, `ANTHROPIC_SMALL_FAST_MODEL`, plus any extra `KEY=VALUE`; also pre-seeds `~/.claude.json`
  so the login/"use this key?" prompts are skipped.
* `MainActivity.kt` – terminal, extra-keys row (Esc/Tab/Ctrl/Alt/arrows), multi-session, menu.

## Build (no Android Studio needed)
1. Create a GitHub repo, push this folder.
2. **Actions → build-apk → Run workflow** → download the `claude-linux-debug` APK artifact.
(Or open the folder in Android Studio and press Run.)

## Notes / caveats
* arm64 phones only (Android 8+).
* **Untested build** — written without an Android SDK; if Gradle reports a TerminalViewClient/TerminalSessionClient
  method mismatch, add/remove the overrides it names (Termux's interface changes slightly between versions).
* PRoot binaries are GPL-2.0 (Termux). If you distribute the APK, ship their source / rebuild them yourself.
* API key is stored in the app's private SharedPreferences (not encrypted) and passed to the guest as env vars.
* If `apk`/`npm` fails with permission errors on your Android version, try setting env `PROOT_NO_SECCOMP=1`
  in Settings → Extra environment variables, then Menu → Re-run setup.
* Android 12+ may kill background processes aggressively: allow notifications and set battery to "Unrestricted".
