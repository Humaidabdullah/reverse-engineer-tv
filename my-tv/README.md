# My TV: VU 4K (lushan)

Google TV 12 (SDK 31), firmware STT2.230929.001.080, at `192.168.1.6`.
This repo is a vendored copy of [cleanyourtv](https://github.com/Gathin23/cleanyourtv) (MIT), the project
behind the "I hacked my old TV to make it feel brand new" video.

What was done (2026-10-24):

- `debloat.txt` (based on `examples/vu-4k-lushan`) disabled: Vu ACR telemetry, Google feedback, partner
  auto-installs, store demo mode, Odin browser, Vu Family Moment, Vu My Music, Alexa, and the Google TV
  setup wizard. Kept by choice: Play Games, Google TV app, MX Player.
- Launcher installed as Home, stock `launcherx` disabled. Dock: Apple TV, Netflix, YouTube, Prime Video
  (`launcher/app/src/main/res/values/config.xml`). Live TV is `com.vt.tv.tvcenter`, reached from Inputs.
- Undo record: `~/.cleanyourtv/recovery-192.168.1.6-*.json`. Pre-change dumps: `backup/vu-4k/` (git-ignored).

Now running glassOS-tv (2026-10-24): [jmarknavarro/glassOS-tv](https://github.com/jmarknavarro/glassOS-tv) at
913847b, cloned to `work/glassOS-tv` (git-ignored), same package `com.gathin.tvhome`, installed over the
launcher above. Local changes: VU dock/hidden/Live TV in its `config.xml`, `versionCode 100` so it installs over
upstream's 8. Its 14 NASA wallpapers are on the TV; pick one with the remote (hold OK on the Settings icon).
`tools/theme.sh` does not apply to it. To go back to the upstream launcher, bump `versionCode` in
`launcher/app/build.gradle` above 100 and run `launcher/build-and-install.sh`.

On the `vu-tvos-focus` branch of that clone: tvOS-style focus motion (FocusMotion) and a phone remote
(PhoneRemote). Open `http://192.168.1.6:8642` on a phone on the same Wi-Fi, pair with the code the TV shows,
and add it to the home screen. Keys for other apps need USB debugging left on. This TV's adbd trusts any
connection from the TV itself (127.0.0.1) without a prompt, which is how the launcher starts its key
helper; it also means any app on the TV could get a shell while debugging is on. From the network, unknown
keys still get the "Allow USB debugging?" prompt. Switches: `phone_remote`, `tvos_focus`, `focus_sounds` in
the launcher's `config.xml`.

```sh
export TV_HOST=192.168.1.6
launcher/build-and-install.sh                       # rebuild after editing config.xml (JAVA_HOME=/opt/homebrew/opt/openjdk@21)
tools/theme.sh themes/aurora                        # try a theme
tools/venv/bin/python -I tools/check.py my-tv/debloat.txt   # after a firmware update
tools/restore.sh my-tv/debloat.txt com.google.android.apps.tv.launcherx   # undo everything
```
