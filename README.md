# SecuredLiberatedFrame

> A **security-hardened fork** of *LiberatedFrame* by ozanozkan (MIT). Same core idea, with a
> locked-down deployment, HTTPS-only sync, an encrypted SD-card photo cache, remote-config off by
> default, and several UX additions — see [What's different in this fork](#whats-different-in-this-fork).

Turn a subscription-gated **Frameo** digital photo frame into an open photo frame driven by a
**Google Photos shared album** — no server, no accounts on the frame, no fees.

Add a photo to the album from anywhere in the world, it appears on the frame within minutes.
Remove it, it disappears. The people managing the photos just use the Google Photos app they
already know, in their own language.

Built for and tested on a **Denver PFF-1042LWMK3** (Android 6.0.1, armeabi-v7a, 1280×800),
but should work on any Frameo-style Android 6+ frame you can reach with ADB.

## Why

- Frameo's app puts remote photo management behind a subscription.
- Google [locked down the Photos Library API in March 2025](https://developers.googleblog.com/en/google-photos-picker-api-launch-and-library-api-updates/):
  third-party apps can no longer read your albums, which killed every "sync my album" integration.
- These frames run Android too old (6.0) for Termux, OpenPhotoFrame, or any maintained
  open-source frame app.

So LiberatedFrame packages the one mechanism that still works — reading the **public shared-album
page** — into a single tiny APK (~25 KB, no dependencies) that is both the sync engine and the
fullscreen slideshow.

## How it works

```
Google Photos app ──> shared album ("anyone with the link")
                              │
                              ▼   every N minutes, on the frame itself
              fetch album page → extract photo URLs → diff
                              │
             download new photos at exactly the screen size
             (Google's server-side scaler + subject-aware crop)
                              │
                              ▼
             internal storage ──> fullscreen crossfade slideshow
```

- **Fail-closed:** if the album page can't be fetched or parsed (network down, Google changed
  the page format), local photos are left untouched — the frame never goes blank.
- **Pixel-exact:** photos are downloaded at exactly the panel resolution via Google's
  high-quality scaler (`=w1280-h800-p`), so the frame displays them 1:1 with zero client-side
  resampling.
- **Self-healing:** the app registers as the device's home app; every power cycle boots straight
  back into the slideshow.

## Features

- Sync interval, seconds-per-photo, and display mode — all configurable on-screen
- Smart display mode (default): photos close to the screen's shape fill it edge-to-edge; photos that would lose too much to the crop (portraits) are shown whole with black bars — decided per photo, still pixel-exact
- Hidden settings panel (tap the screen twice)
- Sleep schedule: screen truly OFF between configurable hours (native time pickers,
  cross-midnight windows like 08:00–00:30 supported); an exact alarm turns it back on in the
  morning. Pressing the power button at night shows photos for a 2-minute peek, then sleep
  resumes by itself. Needs the one-time device-admin grant below; without it the screen falls
  back to black at minimum brightness
- Escape hatches: Wi-Fi settings, Android settings, and the stock Frameo app, with a floating
  "◀ Photos" return chip and a 10-minute auto-return safety net
- Remote configuration over ADB (see below)
- Videos in the album appear as still poster frames (playback not implemented)

## What's different in this fork

Changes on top of upstream LiberatedFrame:

**Security hardening**
- **HTTPS-only sync.** `SyncEngine` refuses the initial URL and any redirect that isn't TLS, so a
  network attacker can't downgrade the album fetch to cleartext and inject a crafted page.
  `android:usesCleartextTraffic="false"` is set as a second layer.
- **Remote config off by default.** The exported HOME activity no longer honors `-e url …` intent
  extras from arbitrary callers; enable **"Allow remote config over ADB"** on-screen first. This
  closes a local attack surface (any installed app could otherwise repoint the frame).
- **Internal app-private photo store.** Photos live in internal app-private storage — invisible to
  other apps and wiped on uninstall (upstream used world-readable external storage). The store
  mirrors the album: removing a photo upstream deletes it from the frame on the next sync, so
  there's no external cache to manage by hand.
- **Signing key lives outside the repo.** `build.sh` keeps `release.keystore` at `~/.liberatedframe/`
  by default (override with `KEYSTORE=…`), so a fork can never bundle it.

**UX / features**
- **Two-stage tap.** A double-tap reveals a bottom-left **⚙ Settings** button (auto-hides after
  6 s); tap it to open settings, tap anywhere else to skip to the next photo.
- **Shuffled playback.** Each app start reshuffles (fresh seed) and walks the whole library before
  repeating — no more replaying the same opening images after a restart.
- **Device-admin wired up.** Upstream shipped the screen-off code but never declared the receiver;
  this fork adds the manifest `<receiver>` + `res/xml/device_admin.xml` policy and makes `build.sh`
  compile resources, so the sleep schedule can truly power the panel off.
- **New defaults:** 20-minute sync, 55 s per photo.

## Building

Requirements: JDK 17+, Android SDK command-line tools with `build-tools;35.0.0` and
`platforms;android-34`. No Gradle.

```bash
./build.sh          # -> out/liberatedframe.apk
```

A signing keystore is generated on first build at **`~/.liberatedframe/release.keystore`** —
outside the repo, so it can never be committed or bundled into a fork. Override its location with
`KEYSTORE=/path/to/release.keystore` and its password with `KEYSTORE_PASS=...`. **Back it up** —
updating the installed app requires the same key.

## Installing on a Frameo frame

1. On the frame: **Settings → About → enable Beta program → enable ADB access**.
2. Connect over USB, or over the network if the frame listens on port 5555
   (many do after a reboot with ADB enabled):
   ```bash
   adb connect <frame-ip>:5555
   ```
   Note: some frames (incl. the Denver PFF-1042) have a **host-only USB-C port** — it powers
   your laptop instead of enumerating. Use a USB-A adapter + USB-A→C data cable for first contact.
3. Install and start:
   ```bash
   adb install -r out/liberatedframe.apk
   adb shell appops set net.ozanozkan.liberatedframe SYSTEM_ALERT_WINDOW allow   # for the "◀ Photos" chip
   adb shell dpm set-active-admin net.ozanozkan.liberatedframe/.AdminReceiver    # lets the sleep schedule turn the screen off
   adb shell settings put global wifi_sleep_policy 2                             # keep Wi-Fi up while the screen is off
   adb shell am start -n net.ozanozkan.liberatedframe/.MainActivity
   ```
4. In Google Photos, create an album, add photos, and share it with **link sharing** on.
   Invite family members as collaborators so they can add/remove photos.
5. On the frame: tap twice → paste the album link (or configure via ADB, below) → **Save & start**.
6. Reboot the frame once; when Android asks which home app to use, pick
   **LiberatedFrame → Always**. From then on it survives power cuts on its own.

## Remote configuration

**Off by default in this fork.** Enable **"Allow remote config over ADB"** in the on-screen settings
first; until then the app ignores the config extras below. Once enabled, anything the panel can do,
ADB can do too:

```bash
adb shell am start -n net.ozanozkan.liberatedframe/.MainActivity \
  -e url "https://photos.app.goo.gl/XXXX" \
  -e syncMin 5 -e slideSec 30 -e mode smart \
  -e schedOn true -e schedStart 08:00 -e schedEnd 00:30
```

Useful maintenance one-liners:

```bash
# (photo count is shown on-screen in the settings panel — the cached files are internal
#  app-private storage and are not readable over ADB without root)
# see the screen
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
# update the app
./build.sh && adb install -r out/liberatedframe.apk
# revert the frame to stock Frameo (remove the device admin first, or uninstall is refused)
adb shell dpm remove-active-admin net.ozanozkan.liberatedframe/.AdminReceiver
adb uninstall net.ozanozkan.liberatedframe
```

## Security model

This frame runs **Android 6, which is end-of-life and receives no security patches** — the biggest
structural risk, and one that can't be fixed in-app. The fork compensates with a locked-down
deployment:

- **USB-only administration.** Keep the frame's ADB listener off the network; admin over a USB-A→C
  data cable (the Denver USB-C port is host-only and won't enumerate directly).
- **Wi-Fi client isolation on.** The frame only needs outbound HTTPS to Google; isolation stops
  other devices (including a compromised LAN peer) from reaching it. Sync is unaffected.
- **Disable the stock Frameo app** once this is the home launcher:
  `adb shell pm disable-user net.frameo.frame` (reverse with `pm enable`).
- **Photos live in internal app-private storage** (not external/SD), so other apps can't read the
  cached images and they're wiped if the app is uninstalled.
- **Remote config stays off** except while you're actively configuring over ADB.

The album itself is a **public unguessable URL** ("anyone with the link"), so the cache's
confidentiality is defense-in-depth, not secrecy of otherwise-private photos.

## Caveats

- The shared-album page format is **unofficial**. If Google changes it, syncing pauses
  (photos keep displaying) until the regex in `SyncEngine.java` gets a small update.
- Very large albums lazy-load; the initial page embeds roughly the first few hundred photos.
- A link-shared album is technically public (unguessable URL). Mind what goes in it.
- The sleep schedule uses the frame's local clock — set the frame's timezone for its
  final location (settings panel → Android settings → Date & time).

## License

[MIT](LICENSE)
