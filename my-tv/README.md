# My TV: VU 4K (lushan), "Hall TV"

Google TV 12 (SDK 31), firmware STT2.230929.001.080, at `192.168.1.6` (give it a fixed address in the
router). Built from [cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT, vendored at the repo root),
the project behind the "I hacked my old TV to make it feel brand new" video, and
[glassOS-tv](https://github.com/jmarknavarro/glassOS-tv) (MIT, in `glassos/`).

## What is on the TV

- **Debloat** (`debloat.txt`, from the tested `examples/vu-4k-lushan`): Vu ACR telemetry, Google feedback,
  partner auto-installs, store demo mode, Odin browser, Vu Family Moment, Vu My Music, Alexa, the Google TV
  setup wizard, and Google Assistant (to free memory; the TV remote's mic button no longer works). Kept by
  choice: Play Games, Google TV app, MX Player, Chromecast, AirPlay, DLNA.
- **Launcher**: `glassos/launcher`, glassOS-tv plus this repo's work: tvOS-style focus (spring lift, lean,
  sheen, click), a tvOS app-launch zoom, a wallpaper screensaver, a faster Home, and the phone remote.
  Stock `launcherx` is disabled. Dock: Apple TV, Netflix, YouTube, Prime Video.
- **Phone remote**: open `http://192.168.1.6:8642` on a phone on the same Wi-Fi, or install the TV Remote
  app from `http://192.168.1.6:8642/app.apk` (`glassos/phone`). Pair with the code the TV shows.
- **Settings changed over adb**: Private DNS `p2.freedns.controld.com` (ads and tracking; AdGuard's server
  was 56 ms away and slowed cold app starts by ~0.5 s), screensaver `com.gathin.tvhome/.WallpaperDream`,
  overlay permission for the launcher.
- **Wallpapers** (Luffy, True Detective, Haikyuu) are only on the TV, not in git. Add more from the phone.

## Security notes

Keys for other apps need USB debugging left on. This TV's adbd trusts any connection from the TV itself
(127.0.0.1) without a prompt, which is how the launcher starts its key helper; it also means any app on the
TV could get a shell while debugging is on. From the network, unknown keys still get the "Allow USB
debugging?" prompt. The phone remote is plain HTTP on the home network. Switches: `phone_remote`,
`tvos_focus`, `focus_sounds` in `glassos/launcher/app/src/main/res/values/config.xml`.

## Commands

```sh
export TV_HOST=192.168.1.6
glassos/install.sh                                         # rebuild and update the launcher and phone app
tools/venv/bin/python -I tools/check.py my-tv/debloat.txt  # after a firmware update: what came back
tools/restore.sh my-tv/debloat.txt com.google.android.apps.tv.launcherx   # undo the debloat, stock Home back
```

The undo record is `~/.cleanyourtv/recovery-192.168.1.6-*.json`; the pre-change dumps are in `backup/vu-4k/`
(git-ignored: they hold the TV's serial numbers). The launcher and phone app are signed with this Mac's
debug key: update them from this Mac.
