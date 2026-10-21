"""Which packages on a TCL / Google TV are safe to disable, and what they do.

Only packages that are actually installed on the TV are ever shown, so it is
fine for this list to mention packages that exist on some models only.
Every change is `pm disable-user`, which `pm enable` undoes; nothing is
uninstalled.
"""

# Categories shown to the user.
ADS = "reclame"
TELEMETRY = "telemetrie"
JUNK = "inutil"
ASK = "s-ar putea să-l folosiți"
UNKNOWN = "necunoscut (TCL)"

# package -> (category, Romanian description, disable by default?)
KNOWN = {
    # Google TV / Android TV
    "com.google.android.tvrecommendations": (ADS, "Recomandări și reclame pe ecranul principal Google TV", True),
    "com.google.android.leanbacklauncher.recommendations": (ADS, "Recomandări pe ecranul principal Android TV", True),
    "com.google.android.feedback": (TELEMETRY, "Trimite rapoarte și feedback la Google", True),
    "com.google.android.tv.bugreportsender": (TELEMETRY, "Trimite rapoarte de erori", True),
    "com.google.android.play.games": (JUNK, "Google Play Jocuri", True),
    "android.autoinstalls.config.google.gtvpai": (ADS, "Instalează singur aplicații promovate", True),
    "com.google.android.apps.tv.dreamx": (ASK, "Screensaver-ul Google TV (Ambient), poate afișa recomandări", False),
    "com.google.android.katniss": (ASK, "Asistentul Google / căutarea vocală de pe telecomandă", False),
    "com.google.android.videos": (ASK, "Aplicația Google TV (filme închiriate/cumpărate de la Google)", False),
    "com.google.android.youtube.tvmusic": (ASK, "YouTube Music", False),
    "com.google.android.youtube.tvkids": (ASK, "YouTube Kids", False),
    "com.google.android.apps.youtube.kids": (ASK, "YouTube Kids", False),
    # TCL (confirmed on a TCL Google TV by github.com/mihirgupta0900/tv-home)
    "com.tcl.ttvs": (TELEMETRY, "Serviciu TCL: statistici (BI), legătura cu Alexa, sincronizare cont", True),
    "com.tcl.usercenter": (TELEMETRY, "Cont TCL / legarea de telefon", True),
    "com.tcl.messagebox": (ADS, "Mesaje promoționale TCL care apar peste imagine", True),
    "com.tcl.partnercustomizer": (ADS, "Rânduri de aplicații și promoții ale partenerilor în ecranul vechi", True),
    "com.tcl.channelplus": (ADS, "Aplicația „TCL Channel”", True),
    "com.tcl.tv.tclhome_passive": (ADS, "Fila de conținut TCL din ecranul vechi", True),
    "com.tcl.hotelmenu": (JUNK, "Modul hotel", True),
    "com.tcl.miracast": (ASK, "Oglindirea ecranului telefonului (Miracast)", False),
    "com.tcl.esticker": (ADS, "Etichete promoționale din modul demo de magazin", True),
    "com.tcl.waterfall.overseas": (ADS, "Fluxul de conținut/canale TCL cu reclame", True),
    "com.tcl.appmarket": (JUNK, "Magazinul de aplicații TCL (aveți Google Play)", True),
    "com.tcl.common.weather": (JUNK, "Widget-ul de vreme TCL", True),
    "com.tcl.notereminder": (JUNK, "Notițe / mementouri TCL", True),
    "com.tcl.screenrecorder": (JUNK, "Înregistrare ecran TCL", True),
    "com.tcl.camera": (JUNK, "Aplicația cameră TCL (fără cameră conectată)", True),
    "com.tcl.guard": (JUNK, "„Curățare/optimizare” TCL", True),
    "com.tcl.tvweishi": (JUNK, "„Curățare/optimizare” TCL", True),
    "com.tcl.bi": (TELEMETRY, "Colectare de date de utilizare TCL", True),
    "com.tcl.logserver": (TELEMETRY, "Colectare de jurnale TCL", True),
    "com.tcl.feedback": (TELEMETRY, "Feedback TCL", True),
    "com.tcl.MultiScreenInteraction_TV": (ASK, "T-Cast: control/oglindire din aplicația TCL de pe telefon", False),
    "com.tcl.ui_mediaCenter": (ASK, "Player media pentru stick USB", False),
    "com.tcl.gallery": (ASK, "Galerie foto TCL (poze de pe stick USB)", False),
    # Preinstalled third-party apps
    "com.amazon.amazonvideo.livingroom": (ASK, "Prime Video", False),
    "com.spotify.tv.android": (ASK, "Spotify", False),
    "com.facebook.katana": (JUNK, "Facebook preinstalat", True),
}

# Launcher-related packages are handled only by the `launcher` step, after the
# new home screen has been installed and tested.
LAUNCHER_PACKAGES = [
    "com.google.android.apps.tv.launcherx",
    "com.google.android.tvlauncher",
    "com.google.android.leanbacklauncher",
]
SETUPWRAITH = "com.google.android.tungsten.setupwraith"

# Never offered, whatever the catalog or heuristics say.
NEVER_EXACT = {
    "android",
    "com.android.systemui",
    "com.android.tv.settings",
    "com.android.vending",
    "com.google.android.gms",
    "com.google.android.gsf",
    "com.google.android.apps.mediashell",  # Chromecast
    "com.google.android.tv.remote.service",
    "com.google.android.tts",
    "com.google.android.webview",
    "ro.tinulica.tvhome",
}
NEVER_KEYWORDS = (
    "tvinput", "tv.input", "livetv", "live_tv", "tvplayer", "channel", "dtv", "atv", "tuner",
    "factory", "bluetooth", "hdmi", "cec", "audio", "sound", "remote", "keyboard",
    "inputmethod", "ime", "packageinstaller", "permissioncontroller", "provider",
    "webview", "update", "ota", "systemui", "setting", "launcher", "partner", "framework",
    "service", "wifi", "network", "display", "power", "storage", "media.tv",
)
# Hint words for unknown TCL packages.
SUSPECT_KEYWORDS = ("ad", "ads", "advert", "promo", "bi", "report", "log", "analytic",
                    "collect", "statistic", "feedback", "recommend", "waterfall", "sticker")


def is_protected(pkg, keep=()):
    """True for packages this tool must never touch."""
    if pkg in NEVER_EXACT or pkg in keep:
        return True
    if pkg in LAUNCHER_PACKAGES or pkg == SETUPWRAITH:
        return True
    if pkg in KNOWN:
        return False
    lowered = pkg.lower()
    # Segments like "tv", "tvos" or "tvplayer" are likely the antenna / Live TV app.
    if any(part.startswith("tv") for part in lowered.split(".")[2:]):
        return True
    return any(word in lowered for word in NEVER_KEYWORDS)


def is_tcl(pkg):
    return pkg.startswith("com.tcl.") or pkg.startswith("com.tcl") or ".tcl." in pkg


def suspicious(pkg):
    parts = pkg.lower().replace("_", ".").split(".")
    return any(part.startswith(word) for part in parts[2:] for word in SUSPECT_KEYWORDS)


def candidates(installed, keep=()):
    """Return [(pkg, category, description, default)] for installed packages worth asking about."""
    result = []
    for pkg in sorted(installed):
        if is_protected(pkg, keep):
            continue
        if pkg in KNOWN:
            category, description, default = KNOWN[pkg]
            result.append((pkg, category, description, default))
        elif is_tcl(pkg):
            hint = "Aplicație TCL necunoscută"
            if suspicious(pkg):
                hint += " (numele sugerează reclame/telemetrie)"
            result.append((pkg, UNKNOWN, hint, False))
    order = [ADS, TELEMETRY, JUNK, ASK, UNKNOWN]
    result.sort(key=lambda item: (order.index(item[1]), item[0]))
    return result
