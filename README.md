# Couchglass

A light, liquid-glass home screen and memory care for low-RAM Android TVs.

Couchglass started on a Redmi TV with 1.7 GB of RAM, where Google TV home alone used 120–190 MB
and the TV often hung after waking from standby. It replaces the home screen with something
lighter and calmer, and adds the tools a small TV needs: a real memory cleaner, a proper power-off
button and a sleep timer.

It is two small apps that work together:

| App | Package | What it does |
|---|---|---|
| **Couchglass** | `app.couchglass.home` | The home screen (launcher) |
| **Couchglass Care** | `app.couchglass.care` | Memory cleaner, full shutdown, restart, sleep timer |

## Features

**Couchglass (home screen)**
- Frosted-glass panels and soft neumorphic controls, in four themes: Mist, Dusk, Aurora and Sand,
  or Auto (Mist by day, Dusk after sunset).
- Continue watching from Android TV's Watch Next list, with posters, episode and time left.
- Your apps as their own TV banners; hold OK on one to move or hide it.
- Game night card for your favourite game, with how many controllers are connected.
- Live TV tab with the channels your TV's inputs publish.
- Weather, Cast and mirroring panel, input switcher, voice search, and quick tiles for sleep timer,
  picture, sound and power off.
- Built for the remote: every move goes to the neighbour you'd expect, and popups keep focus inside.
- About 100 MB in use on a 1080p TV, with no background service of its own.

**Couchglass Care**
- Closes background apps for real (force-stop), never the app on screen, music that's playing,
  the system, Google services or the keyboard.
- Cleans automatically when the TV goes to sleep and when free memory drops below a limit you set.
- Full shutdown and restart, instead of the deep standby that some TVs fail to wake from.
- Works around TVs whose kernel reports a broken "available memory" value.

## How Care gets its powers

Android doesn't let a normal app close other apps or power off the TV. Couchglass Care connects to
the TV's own network debugging service (adbd on `127.0.0.1:5555`), the same one a computer uses for
`adb`, and runs the few shell commands it needs. The TV asks once, with its usual
"Allow USB debugging?" prompt, before it trusts the app.

That means **network debugging must stay on** in Developer options. Anyone on your network can then
ask to connect to the TV, but each new computer or app still needs your approval on screen.

Couchglass (the home screen) never talks to adbd. It asks Care to clean or power off through a
service that only apps signed with the same key can call.

## Install

You need a computer with `adb` and the TV's IP address, with USB / network debugging enabled.

```sh
adb connect <tv-ip>:5555
adb install out/couchglass-care.apk
adb install out/couchglass-home.apk

# Continue watching and recent apps
adb shell pm grant app.couchglass.home android.permission.READ_TV_LISTINGS
adb shell appops set app.couchglass.home GET_USAGE_STATS allow
```

Open **Couchglass Care** once on the TV and choose **Always allow** on the debugging prompt.

### Making Couchglass the home screen

On many Google TV devices the stock launcher has a system priority that wins over any default you
set, so it has to be disabled:

```sh
adb shell cmd package set-home-activity app.couchglass.home/.HomeActivity
adb shell pm disable-user --user 0 com.google.android.tvlauncher
```

To go back: `adb shell pm enable com.google.android.tvlauncher`.

**Known limitation:** Google TV's voice search opens result details pages inside the stock launcher.
While it is disabled, picking a search result shows "Google Assistant is having a problem
processing your request". Searching still works; opening the details page doesn't.

### Xiaomi and Redmi TVs

Tested on a Redmi TV (MiTV-MOOQ3, Android 11, MediaTek). On these TVs:
- PatchWall can be turned off with `pm disable-user --user 0 com.mitv.tvhome.atv` and
  `settings put global patchwall_enable false`.
- Point Xiaomi's start-up app at Couchglass:
  `settings put secure mitv_resume_pkg app.couchglass.home`.
- The Picture tile opens Xiaomi's quick settings; on other TVs it opens Android settings.

## Build

No Gradle. You need the Android SDK (build-tools and `platforms/android-34`) and a JDK 11 or newer.

```sh
./build.sh          # both apps → out/
./build.sh home     # just one
```

Both apps are signed with the same key (default `~/.android/couchglass.jks`, created on first
build). Set `KEYSTORE` and `KS_PASS` to use your own. Never commit the keystore.

## Privacy

- Weather: your approximate location comes from your internet address via [ipwho.is](https://ipwho.is),
  once a day; the forecast comes from [Open-Meteo](https://open-meteo.com) every 30 minutes.
  No account, key or tracking.
- Nothing else leaves the TV. Continue watching and app usage are read on the device.

## Licence

Couchglass is free software under the [GNU General Public License v3.0](LICENSE).

The bundled fonts, [Urbanist](https://github.com/coreyhu/Urbanist) and
[Figtree](https://github.com/erikdkennedy/figtree), are under the SIL Open Font License 1.1
(see `home/assets/fonts/`). App logos and posters shown on screen belong to their owners and are
read from the TV at run time; none are included here.
