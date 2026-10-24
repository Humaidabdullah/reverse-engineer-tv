# Make my Android TV feel like an Apple TV

Paste everything below the line into Claude Code (or another coding assistant with shell access) on a
computer on the same Wi-Fi as the TV. It works on most Android TV and Google TV sets. It does not work on
Samsung (Tizen), LG (webOS) or Fire TV.

---

You are helping me make my Android TV / Google TV feel like an Apple TV, using the code in
https://github.com/Humaidabdullah/reverse-engineer-tv (branch `android-tv-apple-tv-style`). Someone did all of
this on a VU 4K Google TV; my TV is probably a different model, so check everything on mine rather than
assuming. Work step by step, show me what you find, and ask me before anything optional or anything you
cannot undo. Nothing gets uninstalled: apps are only disabled, and every step must stay reversible.

## What I will end up with

- **Debloated TV**: telemetry, ads, content recognition and preinstalled junk disabled (never uninstalled),
  with a one-command undo.
- **An Apple TV-style home screen** (the launcher in `glassos/launcher`): a big clock, a glass dock, my own
  wallpapers, and tvOS-style motion: the focused icon lifts on a spring with a shadow, a lean and a sheen,
  with a soft click; apps open by growing out of their icon and close back into it.
- **An app switcher**: double-press Home on the TV remote. Recent apps as big cards (a snapshot of where I
  left each one), older apps stacked like a deck on the left, Home on the right; Up closes an app.
- **A wallpaper screensaver** that pans through my own wallpapers.
- **A phone remote** served by the TV itself: an Apple-style touch surface, buttons, volume, Now Playing
  with artwork, presets (wallpaper + icon style + volume + an app), Looks (pick or add wallpapers from the
  phone), apps, and an app switcher button. On Android there is an app (`/app.apk` on the TV) with voice,
  haptics and the phone's volume buttons driving the TV; on an iPhone it is a web page.
- **Faster apps**: less background memory used, so apps reopen warm (instantly) instead of cold.

## Rules for you

- Use the repo's own tools (`tools/tv.py`, `tools/disable.sh`, `tools/restore.sh`, `glassos/install.sh`):
  they carry safety checks (core packages, the setup wizard owning Wi-Fi, HDMI inputs, an undo record).
- Back up before changing anything. Never disable a core Android package or anything that drives the HDMI
  inputs. Ask me before disabling anything I might use (streaming apps, casting, voice, updates).
- After every change, take a screenshot from the TV and look at it:
  `tools/venv/bin/python -I tools/tv.py 'screencap -p /data/local/tmp/s.png'` then
  `tools/venv/bin/python -I tools/tv.py --pull /data/local/tmp/s.png shots/s.png`.
- Judge animation smoothness with `dumpsys gfxinfo com.gathin.tvhome framestats` (per-frame times), not
  with `screenrecord`: on a weak TV the recorder itself drops frames.
- Write down what you did in `my-tv/README.md` (model, what was disabled, settings changed, how to undo).

## Steps

1. **My computer.** Check for (and help me install) Python 3, a JDK 17 or 21, and the Android SDK with
   `platforms;android-34` and `build-tools;34.0.0`. The Gradle wrapper is in the repo. The scripts look for
   Homebrew's `openjdk@21` and Android Studio's SDK on a Mac; anywhere else, export `JAVA_HOME` and
   `ANDROID_HOME` first. On Windows, run the shell scripts in Git Bash or WSL.
2. **My TV.** Walk me through Settings > System > About > press OK on "Android TV OS build" 7 times, then
   Developer options > USB debugging on (and Network/Wireless debugging if it has one). Find its IP address
   (Settings > Network). Give the TV a fixed address in the router if I can.
3. **The code.** Clone the repo (branch `android-tv-apple-tv-style`), then
   `python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`.
   Delete the `my-tv/` folder that came with it: that was the other person's TV. Make my own.
4. **Connect.** `export TV_HOST=<the TV's IP>` and run `tools/venv/bin/python -I tools/tv.py --probe`. I will
   accept "Allow USB debugging?" on the TV (tick "Always allow"). Tell me the model, device and Android version.
5. **Back up** the package lists, settings and properties into `backup/<model>/` (git-ignored: it holds
   serial numbers): `pm list packages -f`, `pm list packages -d`, `pm list packages -3`,
   `settings list global|secure|system`, `getprop`, and which app is Home.
6. **Debloat.** Look in `examples/` for my model (match `ro.product.model` and `ro.product.device`); the
   folder names and the comments at the top of each `debloat.txt` say which TV it was tested on. If none
   matches, follow the discovery steps in `PROMPT.md`. Make `my-tv/debloat.txt` for my TV, show me the
   optional apps on it and let me choose, then run `tools/disable.sh my-tv/debloat.txt` (it records the
   undo). On Google TV the setup wizard (`com.google.android.tungsten.setupwraith`) must be disabled for the
   new launcher to own Home; if the script says the wizard owns a saved Wi-Fi network, check which one: if
   it is not the network the TV is on, `FORCE_SETUPWRAITH=1` is safe (that saved network is forgotten).
7. **Fit the launcher to my TV**, in `glassos/launcher/app/src/main/res/values/config.xml`:
   `dock_apps` (ask me for my 4 favourite apps, by package name from `pm list packages`), `hidden_apps` and
   `live_tv_package` (my TV's Live TV / tuner app: find it with
   `pm query-services --components -a android.media.tv.TvInputService` and the vendor's TV apps; it is
   reached from the Inputs menu and should not be a tile).
8. **Install**: `TV_HOST=<ip> glassos/install.sh`. The first time, it hands Home over from the stock
   launcher and puts it back by itself if anything goes wrong. Press Home and screenshot it.
9. **The phone remote.** On my phone, open `http://<TV IP>:8642`. On Android, tap "Get app" (or open
   `http://<TV IP>:8642/app.apk`) and install it; the app finds the TV by itself. Pair with the 4-digit code
   the TV shows. If my phone runs a VPN app, the app still reaches the TV over Wi-Fi.
10. **Extras, one by one, asking me first:**
    - **Ad blocking over DNS**: measure `ping` from the TV to a few ad-blocking DNS providers, pick the
      nearest, and set it, e.g. `settings put global private_dns_specifier p2.freedns.controld.com;
      settings put global private_dns_mode hostname`. A far-away one slows cold app starts (on the VU,
      AdGuard's added ~0.5 s per cold YouTube start); check that ad domains stop resolving and YouTube does not.
    - **Screensaver**: `settings put secure screensaver_components com.gathin.tvhome/.WallpaperDream`.
    - **Wallpapers**: from the phone remote's Looks tab ("Add from your phone").
    - **Memory**: if the TV is short of RAM (`/proc/meminfo`, and the low-memory killer killing apps in
      `logcat`), offer to disable services I do not use (Google Assistant, AirPlay, DLNA...), through
      `my-tv/debloat.txt` so they are undone with the rest. Check with
      `am start -W` that apps come back WARM after switching.
11. **Tell me the trade-offs**: USB debugging has to stay on for the phone remote to press keys in other
    apps; on some TVs adbd trusts connections from the TV itself without a prompt, so while debugging is on
    any app on the TV could get a shell (check mine); the phone remote is plain HTTP on the home network.
12. **Finish** `my-tv/README.md` with what was done and the undo command:
    `TV_HOST=<ip> tools/restore.sh my-tv/debloat.txt <the stock launcher package>`.

## Things that were learned the hard way (so you do not repeat them)

- The launcher and phone app are signed with the debug key of the computer that builds them: always update
  from the same computer, or the TV will refuse the update until the old launcher is uninstalled.
- Some TVs set `animator_duration_scale` below 1 (the VU: 0.6); the launcher's motion uses springs so it is
  not affected. Do not change the global setting.
- `screencap` cannot read protected video (Netflix, Prime Video): their switcher cards and Now Playing fall
  back to artwork. That is expected.
- After a launcher update the TV may keep the Now Playing permission in its settings but stop honouring it;
  the launcher re-allows it by itself. Same for its key helper, which is replaced by the new version.
- Starting a process right after `&` in an adb shell that closes at once can get it killed; the launcher's
  key helper is started from a subshell for that reason.
- The phone page reloads itself when the TV is updated; nothing has to be reinstalled on the phone.
