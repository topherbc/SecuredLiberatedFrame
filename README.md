# LiberatedFrame

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

- Sync interval, seconds-per-photo, and fill/fit display mode — all configurable on-screen
- Hidden settings panel (tap the screen 6 times quickly)
- Sleep schedule: screen dark between configurable hours (native time pickers,
  cross-midnight windows like 08:00–00:30 supported); syncing continues overnight
- Escape hatches: Wi-Fi settings, Android settings, and the stock Frameo app, with a floating
  "◀ Photos" return chip and a 10-minute auto-return safety net
- Remote configuration over ADB (see below)
- Videos in the album appear as still poster frames (playback not implemented)

## Building

Requirements: JDK 17+, Android SDK command-line tools with `build-tools;35.0.0` and
`platforms;android-34`. No Gradle.

```bash
./build.sh          # -> out/liberatedframe.apk
```

A signing keystore (`release.keystore`) is generated on first build.
**Back it up and never commit it** — updating the installed app requires the same key.
Override the default keystore password with `KEYSTORE_PASS=... ./build.sh`.

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
   adb shell am start -n net.ozanozkan.liberatedframe/.MainActivity
   ```
4. In Google Photos, create an album, add photos, and share it with **link sharing** on.
   Invite family members as collaborators so they can add/remove photos.
5. On the frame: tap 6× → paste the album link (or configure via ADB, below) → **Save & start**.
6. Reboot the frame once; when Android asks which home app to use, pick
   **LiberatedFrame → Always**. From then on it survives power cuts on its own.

## Remote configuration

Anything the panel can do, ADB can do (same LAN as the frame):

```bash
adb shell am start -n net.ozanozkan.liberatedframe/.MainActivity \
  -e url "https://photos.app.goo.gl/XXXX" \
  -e syncMin 5 -e slideSec 30 -e fit false \
  -e schedOn true -e schedStart 08:00 -e schedEnd 00:30
```

Useful maintenance one-liners:

```bash
# what's on the frame
adb shell ls /storage/emulated/0/Android/data/net.ozanozkan.liberatedframe/files/photos/
# see the screen
adb shell screencap -p /sdcard/s.png && adb pull /sdcard/s.png
# update the app
./build.sh && adb install -r out/liberatedframe.apk
# revert the frame to stock Frameo
adb uninstall net.ozanozkan.liberatedframe
```

## Caveats

- The shared-album page format is **unofficial**. If Google changes it, syncing pauses
  (photos keep displaying) until the regex in `SyncEngine.java` gets a small update.
- Very large albums lazy-load; the initial page embeds roughly the first few hundred photos.
- A link-shared album is technically public (unguessable URL). Mind what goes in it.
- The sleep schedule uses the frame's local clock — set the frame's timezone for its
  final location (settings panel → Android settings → Date & time).

## License

[MIT](LICENSE)
