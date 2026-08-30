# FreeFTP

An FTP, FTPS and SFTP client for Android. Free, no ads, no paid tier, no telemetry.

The app talks to your servers and to nothing else: the only network permission it
holds is `INTERNET`, and the only host it ever contacts is the one you typed in.

## Features
- Browse, download, upload, rename, delete, create folders
- Configurable per-server start folder
- In-app text viewer
- Folder downloads and multi-select
- Transfer queue UI with pause/resume and cancel abilities
- Configurable downloads directory; full storage permissions not needed
- Standard security features found in other popular FTP clients
- Handles passive and active mode, `MLSD` with a  `LIST` fallback, and other edge cases

## Installation
You can find the latest prebuilt APK in [Releases](https://github.com/builderpepc/free-ftp/releases), or compile it from source by following the instructions below.

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

The test suite structure was drawn from the suites of the major open-source clients
— chiefly **Cyberduck**, plus **FileZilla**, **curl** and **lftp**.

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
