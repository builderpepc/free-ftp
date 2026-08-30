# FreeFTP

An FTP, FTPS and SFTP client for Android. Free, no ads, no paid tier, no telemetry.

The app talks to your servers and to nothing else: the only network permission it
holds is `INTERNET`, and the only host it ever contacts is the one you typed in.

## Features

- **FTP, FTPS (explicit and implicit) and SFTP** in one client.
- Browse, download, upload, rename, delete, create folders. Back walks up the
  remote tree and only disconnects once you are at the root.
- **Per-server start folder** — browse to a folder and pick "Open here next time"
  from the ⋮ menu, and that connection opens straight into it from then on.
- **In-app text viewer.** Tap any file to read it — configs, logs, source, CSV —
  streamed straight into memory, with nothing written to device storage. The
  encoding is detected (BOM, then UTF-8, then a Latin-1 fallback) and shown, so
  you can tell real content from mojibake. Files past 1 MB show their opening
  megabyte with a banner; binaries say so instead of rendering noise.
- **Folder downloads and multi-select.** Long-press to start a selection, tick what
  you want, and download or delete in bulk; folders come down recursively with their
  structure intact. "Download all in this folder" is in the ⋮ menu. Anything large —
  more than 20 files or 100 MB, or a folder too big to finish scanning — asks first,
  and deleting always asks.
- **Transfer queue** with live progress, **pause and resume**, cancel and retry.
  A paused transfer keeps its partial file and continues from that offset. Tap a
  finished download to open it.
- **Downloads land in `Downloads/FreeFTP`**, where the Files app can actually see
  them, and you can point them at any folder you like from Settings. FreeFTP asks
  for no storage permission: you grant access to the one folder you choose.
- **SSH host key checking.** An unrecognised server is refused until you have seen
  its `SHA256:` fingerprint and accepted it; a key that later changes is refused
  outright.
- **Certificate checking for FTPS**, with an explicit opt-in for self-signed certs.
- Passwords, key passphrases and private keys are **encrypted at rest** with a key
  held in the Android key store.
- Password, SSH key (with passphrase) and anonymous authentication, with automatic
  fallback across `publickey`, `password` and `keyboard-interactive`.
- Handles the awkward parts of real FTP: passive and active mode, `MLSD` with a
  `LIST` fallback, a dozen server listing dialects, UTF-8 negotiation, and
  `LIST -a` when you ask to see hidden files.

## Project layout

| Module | What it holds |
|---|---|
| `core` | The protocol layer: `RemoteClient` and its FTP and SFTP implementations, listing parsers, the transfer queue, host-key and profile storage. No Android UI dependencies. |
| `app`  | The Jetpack Compose UI: server list, connection editor, file browser, transfer queue. |

The split exists so the interesting code can be tested against real servers on
the JVM in seconds, rather than through an emulator.

## Building

Requires JDK 17+ and the Android SDK with platform 37 and build-tools 36.0.0.
Nothing else: no keys, no accounts, no local services.

```bash
./gradlew :app:assembleDebug          # APK at app/build/outputs/apk/debug/
./gradlew :core:testDebugUnitTest     # the whole protocol test suite
```

`assembleRelease` produces a signed APK when a `keystore.properties` is present
(see `keystore.properties.example`) and an unsigned one when it is not, which is
what a distributor such as F-Droid expects.

> **ARM64 Linux developers:** AGP ships an x86_64-only `aapt2`, so builds fail
> until it is pointed at one that runs natively. That belongs to your machine,
> not to this project — set `android.aapt2FromMavenOverride` in
> `~/.gradle/gradle.properties`.

## Testing

The test plan is written out in [`docs/TEST_PLAN.md`](docs/TEST_PLAN.md), case by
case, and was written before the implementation. Its structure was drawn from the
suites of the major open-source clients — chiefly **Cyberduck**, whose FTP module
devotes a whole package to server-dialect listing parsers, plus **FileZilla**,
**curl** and **lftp**.

Nothing in the protocol layer is mocked. Every FTP case runs against a real
**Apache FtpServer** and every SFTP case against a real **Apache MINA SSHD**,
each booted on an ephemeral port per test class:

```bash
./gradlew :core:testDebugUnitTest
```

### Driving the app against a real server

The same fixtures start on fixed ports, so the app can be exercised by hand on
whatever device or emulator you develop with:

```bash
./gradlew :core:devServers -PserveDir=/tmp/freeftp-root
# FTP   on 127.0.0.1:2121  (passive data ports 30000-30009)
# FTPS  on 127.0.0.1:2122  (explicit TLS, self-signed)
# SFTP  on 127.0.0.1:2222
```

These listen on loopback, so forward them to the device. Forward the FTP passive
range as well as the control port — passive FTP opens a second connection on a
port the server chooses, and it has to be reachable too:

```bash
for p in 2121 2122 2222 $(seq 30000 30019); do adb reverse tcp:$p tcp:$p; done
```

`adb reverse` works the same for an emulator and for a phone on USB, so any
Android development setup will do; nothing here assumes a particular one.

## Icon

`art/FreeFTP.svg` is the source. The Android resources are generated from it, so
the icon can be rebuilt whenever the artwork changes:

```bash
python3 tools/generate-launcher-icon.py art/FreeFTP.svg   # needs cairosvg, svgelements
```

That produces the adaptive icon (background, foreground and a monochrome layer for
themed icons), raster fallbacks in every density, and the 512px PNG a store listing
wants. The foreground is scaled to 75% so the wordmark survives a circular launcher
mask — at full bleed its outer strokes get sliced off on round launchers.

## Licence

[Apache License 2.0](LICENSE). Third-party components and their licences are
listed in [NOTICE](NOTICE); all of them are free and open source.
