# TV Home

**An Apple TV–style home screen for TCL Google TVs**, with Liquid Glass, smooth animations,
previews from your apps, a Control Center, search across your streaming apps, a Cinema mode that
helps you choose a film, weather and warning notices, messages and questions from your computer
(made for an AI assistant working while you watch), and a small tool that cleans up the TV.

[Română](README.ro.md)

![The home screen](docs/images/home.jpg)

> **For TCL TVs only, for now.** TV Home was built and tested on a TCL Google TV running Android
> TV 12 (4K, Europe). It relies on how TCL's system behaves (its settings pages, inputs and power
> manager), so on other brands parts of it will not work. It is a personal project, not affiliated
> with Apple, TCL or Google.
>
> **Open source, and still rough in places.** There are bugs; they can be fixed. Bug reports are
> welcome as issues; the code itself is changed only by its author, so pull requests are closed.

---

## Contents

- [What it does](#what-it-does)
- [What you need](#what-you-need)
- [Install](#install)
- [Using it with the remote](#using-it-with-the-remote)
- [Settings](#settings)
- [Updating and undoing everything](#updating-and-undoing-everything)
- [Privacy: what it talks to](#privacy-what-it-talks-to)
- [Permissions](#permissions)
- [Troubleshooting](#troubleshooting)
- [Building it yourself](#building-it-yourself)
- [Project layout](#project-layout)
- [Credits and licences](#credits-and-licences)

---

## What it does

### hello

When the TV comes on, "hello" comes out of a blur in silver over your wallpaper, with a soft sound,
then the home screen arrives: the dock rises and the apps light up one after another.

![hello](docs/images/hello.jpg)

### The home screen

- **Wallpapers**: nature photos that change every few minutes with a slow cross-fade; add your own.
- **The dock**: your favourite apps on a pane of Liquid Glass. Focus lifts a tile, tilts it a little
  and lights it; pressing OK sinks it.
- **Previews**: rest on an app and its newest programmes rise above the dock as cards (Netflix,
  YouTube, Disney+, HBO Max and any app that publishes Android TV channels). Filters on top: *For you*,
  *Continue*, *Top 10*, *Series*, *Films*.
- **The TV tile**: the antenna channels as cards, the first one playing muted.
- **All apps**: press Down for every app in a grid over the frosted wallpaper; group apps into folders.
- **Arrange**: hold OK on an app, then move it left or right.

![Previews over the dock](docs/images/previews.jpg)

![All apps](docs/images/apps.jpg)

### Control Center

Press Up from the top of the screen (or select the clock). Glass tiles and round shortcuts, like tvOS:

- **Cinema** (see below), **Wi-Fi** and **Bluetooth**, which open into a list of networks or devices;
- **Do Not Disturb**: no notices while it is on (red weather warnings still come through);
- **Sleep**: pick the hour and minute the TV switches itself off; a minute before, it tells you;
- **Shortcuts**: Search, Sources, Antenna, all apps, next wallpaper, picture and sound settings,
  accessibility, Settings, cameras, Google Play, screensaver. Hold OK on one to move or remove it,
  **+** adds one;
- **Inputs** (HDMI, antenna) and **Profiles** on their own tabs.

![Control Center with Do Not Disturb on](docs/images/control-center.jpg)

![The sleep timer](docs/images/sleep-timer.jpg)

### Notices

Weather and notifications arrive as drops of Liquid Glass that well out of the left edge of the
screen, over anything you are watching (except Cinema mode), with a quiet glass "clink":

- **Weather**: when the TV comes on, and when the sky changes (it starts raining, the storm passes),
  with the weather moving behind the glass: rain, snow, lightning, sun. It looks again when the
  forecast says the sky will change, otherwise every half hour.
- **Warnings**: yellow, orange and red warnings for your county (Romania, from MeteoAlarm).
- **Notifications** from your apps (optional, see [Install](#7-turn-on-the-extras-optional)).
- **Messages from your computer**: see [below](#messages-and-questions-from-your-computer).

Several at once stack under each other and make room for each other as they come and go; when
they would not fit on the screen, the oldest leaves. On the home screen, **Left** at the left edge
takes the remote into them: **Up / Down** go from one to the next (the one you are on comes
forward and opens up to show all its words), **Back** closes it, **Right** goes back out. While
you are in them, none leaves on its own.

![Notices](docs/images/notifications.jpg)

### Messages and questions from your computer

Made for an AI assistant (Claude) that works on your computer while you watch TV, so that you can
follow it and answer it from the sofa; any script can use it.

- **Messages** come out of the left edge as a small drop of Liquid Glass with Claude's mark and
  the title, which then opens up, wider and down, with the text and any details (lines starting
  with `- ` are a list, `**words**` are bold).
- **Clawd**, Claude Code's pixel mascot, lands on the top edge and does something different for
  each message: waves hello, hops with happy eyes when something is done, throws confetti, walks
  under a question mark while a question waits, holds its arms up when it needs you, works under
  Claude's spinning mark, sends a heart, or sleeps. It is chosen from the words (or with `--mood`),
  and is not the same twice in a row.
- **Questions** show their answers as glass buttons: choose one with the remote, or scan the QR
  code and answer from your phone (while the question waits, the TV serves a small page on your
  home network). The computer gets the answer.
- **Tasks**: a short to-do list under the weather when the TV comes on (the weather first, then it
  opens down to the list), with Liquid Glass check marks that fill in as things get done.
- **Usage**: a small glass pill at the top left of the home screen with how much of the plan is
  used and when it resets. It shows only on the home screen itself, and rises out of the way while
  notices show.
- **One account per profile**: every computer that talks to the TV is an account. In Control
  Center, the round Claude shortcut chooses which account the profile in use sees, or none. Other
  profiles see nothing of it; their messages wait until a profile linked to them is in use.

```bash
python tvtool.py claude --name "Ana"                       # name this computer's account on the TV
python tvtool.py say "Backup done" "All good" --details $'- 120 GB copied\n- **no errors**'
python tvtool.py ask "Deploy now?" --options "Yes|Not yet"  # prints the answer
python tvtool.py tasks --add "Renew the domain" --done 1    # the list under the weather
python tvtool.py usage 18 --resets "resets at 02:20"        # the pill at the top left
```

A plain broadcast works too, from any script:

```bash
adb shell am broadcast -n ro.tinulica.tvhome/.MessageReceiver --es title "Backup done" --es text "All good"
```

![A message from Claude](docs/images/claude-message.jpg)

### Search

A Liquid Glass search field and keyboard over the frosted home screen. Type (or speak, where the TV
can) and get films and series as cards, the ones on your apps first, with the app's name on each. OK
opens the title in its app, on its own page (Netflix, Disney+, HBO Max, Prime Video); *Trailer* plays
it on YouTube. Needs a free TMDB key ([step 8](#8-search-and-cinema-mode-optional)).

![Search](docs/images/search.jpg)

### Profiles

Several people, each with a name, a picture and their own dock order. Hold OK on a profile in Control
Center to rename it or change its picture.

### Shortcuts on the remote

- **Hold Back**: a ring fills; let go and the recent apps appear. Up on an app closes it.
- **Volume**: a glass volume bar instead of the TV's.
- **Inputs key**: a glass Sources panel.

![Recent apps](docs/images/app-switcher.jpg)

### Cinema mode

A full-screen way to find something to watch tonight. The lights go down with a soundtrack, a few
questions follow (who is watching, film or series, mood, how much time, new or classic), then the
results on the subscriptions you have, with trailers, details and *Watch on …*, which opens the title
in its app. Tabs: *For you*, *Popular now*, *Something else*, *Subscriptions*. Needs a TMDB key.

![Cinema mode](docs/images/cinema-intro.jpg)

![Cinema questions](docs/images/cinema-questions.jpg)

![Cinema results](docs/images/cinema-results.jpg)

### Cleaning up the TV

`tvtool.py debloat` turns off TCL's and Google's ads, telemetry and junk apps. It asks about every
app, only **disables** them (never uninstalls), logs every change in `tv-data/changes.json` and can
undo all of it. Chromecast, the antenna / Live TV, Google Play, Settings, input methods, HDMI-CEC,
Bluetooth, system updates and the apps in your dock are never offered.

---

## What you need

- A **TCL Google TV** (Android TV 12 or newer) on your home network.
- A computer (Windows, macOS or Linux) with **Python 3.9 or newer**, on the same network.
- About 15 minutes.
- Optional: a free **TMDB** account, for Search and Cinema mode.

---

## Install

### 1. Turn on developer options on the TV

1. **Settings › System › About**: select **Android TV OS build** and press OK **7 times**, until it
   says you are a developer.
2. **Settings › System › Developer options**: turn on **USB debugging** (on TCL this also allows
   debugging over the network).

### 2. Get the tool

```bash
git clone https://github.com/tinulica/tv-home.git
cd tv-home
python -m pip install -r requirements.txt
```

The tool uses the pure-Python `adb-shell` package, so you do not need Android's `adb` program. If you
have `adb`, you can use it with `--backend adb`.

### 3. Find and connect to the TV

```bash
python tvtool.py find        # looks for the TV on your network
python tvtool.py connect     # then choose "Always allow" on the TV and press OK
```

If `find` does not see it, give the TV's address (on the TV: **Settings › Network & Internet**):

```bash
python tvtool.py connect 192.168.x.y
```

### 4. Back up

```bash
python tvtool.py backup
```

Saves the list of apps, the settings and the current home screen to `tv-data/backups/` on your
computer. Nothing in `tv-data/` leaves your computer (it is ignored by git).

### 5. Clean up (optional)

```bash
python tvtool.py scan        # shows what could be turned off; changes nothing
python tvtool.py debloat     # asks about every app; Enter = the recommendation
```

### 6. Install TV Home

```bash
python tvtool.py launcher
```

Installs `dist/tv-home.apk`, puts your streaming apps in the dock, makes TV Home the home screen and
checks that the Home button brings it back from inside an app (if not, it rolls back to the old home
screen).

### 7. Turn on the extras (optional)

```bash
python tvtool.py surse        # hold-Back app switcher, volume bar, notices, Sources panel
python tvtool.py notificari   # your apps' notifications as glass drops
python tvtool.py wallpapers   # more nature wallpapers (--seconds 300 = how long each stays)
```

`surse` turns on TV Home's accessibility service and lets TV Home keep it on (TCL's own players
sometimes switch accessibility off). `notificari` gives TV Home access to notifications. Undo either
with `--off`, or in the TV's Settings.

### 8. Search and Cinema mode (optional)

1. Make a free account at [themoviedb.org](https://www.themoviedb.org/signup), then in
   **Settings › API** request an API key (personal use).
2. Run:

   ```bash
   python tvtool.py tmdb
   ```

   and paste the *API Read Access Token* (or the API key) when asked. It is not shown on the screen,
   it is saved only in `tv-data/tmdb.key` on your computer and in TV Home's private storage on the TV,
   and it is never written into the code. `--new` replaces it.

### 9. Make it yours (optional)

```bash
python tvtool.py dock com.netflix.ninja,com.google.android.youtube.tv   # the apps in the dock
python tvtool.py folder Games com.example.one,com.example.two          # a folder in the app grid
python tvtool.py profile me.jpg --name "Alex"                          # the current profile
python tvtool.py sounds my-sounds/                                     # your own navigation sounds
python tvtool.py cameras add "Door" rtsp://camera.local/stream          # a camera in Control Center
python tvtool.py shot home                                             # a screenshot of the TV
```

`python tvtool.py -h` lists every command; `python tvtool.py <command> -h` its options.

### 10. Messages from your computer (optional)

```bash
python tvtool.py claude --name "Ana"            # this computer becomes an account on the TV
python tvtool.py say "Hello from the computer"  # a first message
```

The first profile sees the first account until it chooses; any profile chooses its own in Control
Center (the round Claude shortcut). To let an AI assistant use it, tell it to run `tvtool say` when it
finishes something or needs you, and `tvtool ask` when it needs an answer (the command waits and
prints the answer). The QR codes need `segno` (in `requirements.txt`).

---

## Using it with the remote

| Where | Button | What happens |
|---|---|---|
| Home | Left / Right | Moves along the dock; the previews rise after a moment |
| Home | Up | Into the previews; again: the filters; again: Control Center |
| Home | Down | All apps |
| An app | Hold OK | Move it with Left / Right; OK or Back puts it down |
| Anywhere | Hold Back | Recent apps (let go once the ring is full); Up on one closes it |
| Anywhere | Volume | The glass volume bar |
| Home, notices showing | Left at the left edge | Into the notices: Up / Down between them, Back closes one, Right goes out |
| A question from the computer | Up / Down, OK | Choose an answer; Back closes it without one |
| Control Center | OK on the Claude circle | Which computer's messages this profile sees |
| Control Center | Hold OK on a shortcut | Move or remove it; **+** adds one |
| Control Center | OK / hold OK on a profile | Switch to it / edit it |
| Search | Letters, OK, Down | Type; the results; *Watch on …* and *Trailer* |
| Cinema mode | Home | Asks before leaving Cinema mode |

---

## Settings

Open **Settings** from Control Center (the gear) or from the app grid. Among them: weather notices,
weather warnings, the notice sound, the hello animation, built-in wallpapers, navigation sounds, and
shortcuts to the TV's own settings pages.

---

## Updating and undoing everything

```bash
git pull
python tvtool.py update                    # installs the newer dist/tv-home.apk, keeps your dock
python tvtool.py restore                   # turns back on every app the tool turned off, old home screen
python tvtool.py restore --uninstall-home  # ... and removes TV Home too
```

`tv-data/restore.bat` and `tv-data/restore.sh` do the same with the `adb` program. A factory reset
also brings everything back.

After an update, press **Home** once: TCL lets TV Home's background service start again only after TV
Home has been on screen.

---

## Privacy: what it talks to

TV Home has no account, no analytics and no server of its own. It connects to:

| Service | What for | What it sends |
|---|---|---|
| [TMDB](https://www.themoviedb.org) | Search, Cinema mode, posters | Your search words and your TMDB key |
| [Wikidata](https://www.wikidata.org) | The apps' own IDs for a title (to open it directly) | The title's TMDB ID |
| Netflix's Top 10 page | Netflix's Top 10 in the previews | Nothing personal |
| [Open-Meteo](https://open-meteo.com) | The weather | Your approximate coordinates |
| [ipwho.is](https://ipwho.is) | Your approximate city, at most once a day | Your public IP address (as with any request) |
| [MeteoAlarm](https://meteoalarm.org) | Weather warnings for Romania | Nothing personal |
| YouTube / Vimeo | Trailers | The trailer's ID |

Everything else (profiles, the dock, your choices) stays on the TV. `tvtool` talks only to your TV.
While a question from the computer waits, the TV answers on port 8642 of your home network (the
page the QR code opens); it closes with the question.

---

## Permissions

| Permission | Why |
|---|---|
| Internet, Wi-Fi state | The services above; the network name and list in Control Center |
| Bluetooth | The Bluetooth list in Control Center |
| Location (granted by `tvtool`) | Android needs it to list Wi-Fi networks |
| Usage stats, kill background processes | Recent apps, and closing them |
| TV listings, EPG | The antenna channels on the TV tile |
| Write secure settings (granted by `tvtool surse`) | Keeping the accessibility service on when TCL switches it off |
| Accessibility service (you turn it on) | Hold Back, the volume bar, notices over other apps |
| Notification access (you turn it on) | Your apps' notifications as drops |

---

## Troubleshooting

- **Hold Back, the volume bar or the notices stopped working**: press Home once. TCL's players can
  switch accessibility off, and after an update TCL only lets the service start from the home screen.
- **The TV is not found**: the TV and the computer must be on the same network, with USB debugging on;
  try `python tvtool.py connect <the TV's address>`.
- **"Always allow" never appeared**: unplug the TV for 10 seconds, then `connect` again.
- **Netflix opens on its profile picker instead of the title**: choose the profile; TV Home sends the
  title again right after.
- **Search or Cinema mode say they need a key**: `python tvtool.py tmdb`.
- **Anything looks wrong after an update**: `python tvtool.py restore`, then `launcher` again.
- **A message from the computer does not show**: the profile in use is not linked to that computer's
  account (Control Center, the Claude circle); it shows once a linked profile is in use. While the
  service is off (see the first point) it comes as a plain system toast.
- **The phone cannot open the question's page**: the phone must be on the same network as the TV.

---

## Building it yourself

The app is plain Java with no libraries and is built without Gradle. You need **JDK 17+** and the
**Android SDK** with `platforms;android-34` and `build-tools;35.0.0`:

```bash
ANDROID_HOME=~/android-sdk launcher/build.sh     # writes dist/tv-home.apk
```

The first build makes a signing key at `~/.android/tvhome.keystore` (or at `TVHOME_KEYSTORE`). Keep
it: Android only accepts an update signed with the same key.

The sounds are made by the scripts in `tools/` (`python tools/hello_sound.py` and the others).

The tests use a fake TV and check that nothing is ever uninstalled, that protected apps are never
offered, that restore undoes everything and that a failed Home-button test rolls back:

```bash
python -m pytest tests
```

---

## Project layout

```
tvtool.py          the command-line tool: connect, backup, debloat, install, restore, ...
catalog.py         known TCL / Google TV packages and what each one does
launcher/          TV Home, the Android app
  src/             Java sources: MainActivity (the home screen), SourcesService (the accessibility
                   service: notices, hold Back, volume), CinemaActivity, LiquidDrop, ClaudeCard,
                   Clawd, ClaudeLink (accounts per profile), QuestionServer (answers from a phone), ...
  assets/          wallpapers, sounds, avatars, Cinema mode's pictures
  build.sh         the build script
dist/tv-home.apk   the built app
tools/             the scripts that make the sounds
tests/             tests for tvtool
docs/              these images, and notes on how the Liquid Glass is drawn
```

---

## Credits and licences

- **Wallpapers**: Wikimedia Commons (CC BY-SA and CC0); authors and licences in
  [launcher/assets/wallpapers/CREDITS.txt](launcher/assets/wallpapers/CREDITS.txt). Some were selected
  by [mihirgupta0900/tv-home](https://github.com/mihirgupta0900/tv-home) (MIT), which also inspired the
  Inputs menu and the TCL package list.
- **Cinema mode's pictures and the avatars**: [Fluent Emoji](https://github.com/microsoft/fluentui-emoji)
  by Microsoft (MIT, see [launcher/assets/FLUENT-EMOJI-LICENSE.txt](launcher/assets/FLUENT-EMOJI-LICENSE.txt)).
- **Icons**: see [launcher/ICONS-LICENSE.txt](launcher/ICONS-LICENSE.txt) (MIT).
- **Film and series data**: [TMDB](https://www.themoviedb.org). *This product uses the TMDB API but is
  not endorsed or certified by TMDB.*
- **Weather**: [Open-Meteo](https://open-meteo.com) (CC BY 4.0). **Warnings**: MeteoAlarm / EUMETNET.
- **Sounds**: made for this project by the scripts in `tools/`.
- **Clawd**: drawn from the 12 × 8 pixel grid that [ClawdMoji](https://github.com/afspies/ClawdMoji)
  (MIT) recovered from Claude Code's logo; the poses are this project's own. **Claude's mark**: the
  symbol on Wikimedia Commons.

Apple TV, tvOS and Liquid Glass are trademarks of Apple Inc.; Claude, the Claude logo and Clawd are
trademarks of Anthropic; TCL, Google TV, Netflix and the other app names belong to their owners.
This project is not affiliated with, or endorsed by, any of them.
