#!/usr/bin/env python3
"""tvtool: debloat an Android / Google TV over Wi-Fi ADB and install TV Home.

Everything is reversible: apps are disabled with `pm disable-user`, never
uninstalled, and every change is logged in tv-data/changes.json so that
`python tvtool.py restore` (or the generated restore scripts) can undo it.

Typical order:
    python tvtool.py find          # find the TV on the network
    python tvtool.py connect IP    # accept the prompt on the TV
    python tvtool.py backup
    python tvtool.py debloat       # asks about each app
    python tvtool.py launcher      # installs TV Home, tests the Home button
    python tvtool.py wallpapers    # downloads nature wallpapers onto the TV
    python tvtool.py restore       # undoes everything
"""

import argparse
import concurrent.futures
import datetime as dt
import json
import os
import random
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

import catalog

HERE = Path(__file__).resolve().parent
DATA = HERE / "tv-data"
STATE_FILE = DATA / "state.json"
CHANGES_FILE = DATA / "changes.json"
APK = HERE / "dist" / "tv-home.apk"
HOME_PKG = "ro.tinulica.tvhome"
HOME_ACTIVITY = HOME_PKG + "/.MainActivity"
WALLPAPER_DIR = "/sdcard/Android/data/%s/files/wallpapers" % HOME_PKG
ADB_PORT = 5555

# Candidate packages for the dock, in order; the first installed one of each group is used.
DOCK_WISHES = [
    ("YouTube", ["com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged"]),
    ("Netflix", ["com.netflix.ninja"]),
    ("HBO Max", ["com.wbd.stream", "com.hbo.hbonow", "com.hbo.max"]),
    ("Disney+", ["com.disney.disneyplus"]),
]


# ---------------------------------------------------------------------------- output


def say(text=""):
    print(text, flush=True)


def ask(question, default=False):
    """Yes/no question in Romanian. Enter keeps the default."""
    hint = "[D/n]" if default else "[d/N]"
    while True:
        answer = input("%s %s " % (question, hint)).strip().lower()
        if not answer:
            return default
        if answer in ("d", "da", "y", "yes"):
            return True
        if answer in ("n", "nu", "no"):
            return False
        say("  Răspundeți cu d (da) sau n (nu).")


def now_stamp():
    return dt.datetime.now().strftime("%Y%m%d-%H%M%S")


# ---------------------------------------------------------------------------- state


def load_json(path, default):
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return default


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding="utf-8")


def record_change(action, pkg, **extra):
    changes = load_json(CHANGES_FILE, [])
    entry = {"time": now_stamp(), "action": action, "package": pkg}
    entry.update(extra)
    changes.append(entry)
    save_json(CHANGES_FILE, changes)
    write_restore_scripts()


# ---------------------------------------------------------------------------- devices


class Device:
    """Minimal interface used by the commands."""

    serial = "?"

    def shell(self, command, timeout=30):
        raise NotImplementedError

    def screencap(self):
        raise NotImplementedError

    def push(self, local, remote):
        raise NotImplementedError

    def install(self, apk):
        """Install via a push + pm install, which works for every backend."""
        remote = "/data/local/tmp/" + Path(apk).name
        self.push(str(apk), remote)
        output = self.shell("pm install -r -g %s" % remote, timeout=120)
        self.shell("rm -f %s" % remote)
        return output


class AdbShellDevice(Device):
    """Pure-Python ADB (adb-shell package); works where the adb binary is blocked."""

    def __init__(self, host, port=ADB_PORT):
        from adb_shell.adb_device import AdbDeviceTcp
        from adb_shell.auth.keygen import keygen
        from adb_shell.auth.sign_pythonrsa import PythonRSASigner

        key_dir = Path.home() / ".tvtool"
        key_dir.mkdir(exist_ok=True)
        key = key_dir / "adbkey"
        if not key.exists():
            keygen(str(key))
        signer = PythonRSASigner(Path(str(key) + ".pub").read_text(), key.read_text())
        self.serial = "%s:%d" % (host, port)
        self.device = AdbDeviceTcp(host, port, default_transport_timeout_s=15)
        say("Mă conectez la %s ... Dacă pe televizor apare o întrebare, alegeți"
            " „Permite întotdeauna” și apăsați OK." % self.serial)
        self.device.connect(rsa_keys=[signer], auth_timeout_s=90)

    def shell(self, command, timeout=30):
        return self.device.shell(command, timeout_s=timeout, read_timeout_s=timeout) or ""

    def screencap(self):
        return self.device.exec_out("screencap -p", decode=False, timeout_s=30, read_timeout_s=30)

    def push(self, local, remote):
        self.device.push(local, remote, read_timeout_s=120)


class AdbBinaryDevice(Device):
    """Uses the `adb` program from Android platform-tools."""

    def __init__(self, host, port=ADB_PORT):
        self.serial = "%s:%d" % (host, port)
        say("Mă conectez la %s ... Dacă pe televizor apare o întrebare, alegeți"
            " „Permite întotdeauna” și apăsați OK." % self.serial)
        for _ in range(30):
            out = self._adb("connect", self.serial).stdout
            state = self._adb("-s", self.serial, "get-state").stdout.strip()
            if state == "device":
                return
            if "unauthorized" in out or "unauthorized" in state or "authenticate" in out:
                time.sleep(3)
                continue
            time.sleep(2)
        raise RuntimeError("Nu m-am putut conecta la %s (adb get-state: %s)" % (self.serial, state))

    @staticmethod
    def _adb(*args, timeout=60, binary=False):
        return subprocess.run(["adb", *args], capture_output=True, text=not binary, timeout=timeout)

    def shell(self, command, timeout=30):
        return self._adb("-s", self.serial, "shell", command, timeout=timeout).stdout

    def screencap(self):
        return self._adb("-s", self.serial, "exec-out", "screencap -p", binary=True).stdout

    def push(self, local, remote):
        result = self._adb("-s", self.serial, "push", local, remote, timeout=300)
        if result.returncode != 0:
            raise RuntimeError(result.stderr.strip())


def have_adb_shell():
    try:
        import adb_shell  # noqa: F401
        return True
    except ImportError:
        return False


def open_device(host=None, backend="auto"):
    state = load_json(STATE_FILE, {})
    host = host or state.get("host")
    if not host:
        sys.exit("Nu știu adresa televizorului. Rulați întâi: python tvtool.py find")
    if backend == "auto":
        backend = "adb-shell" if have_adb_shell() else ("adb" if shutil.which("adb") else None)
    if backend is None:
        sys.exit("Lipsește ADB. Instalați pachetul Python: python -m pip install adb-shell")
    device = AdbShellDevice(host) if backend == "adb-shell" else AdbBinaryDevice(host)
    state["host"] = host
    save_json(STATE_FILE, state)
    return device


# ---------------------------------------------------------------------------- helpers on the TV


def packages(device, flag=""):
    out = device.shell("pm list packages %s" % flag)
    return sorted(line.split(":", 1)[1].strip() for line in out.splitlines() if line.startswith("package:"))


def current_home(device):
    out = device.shell("cmd package resolve-activity --brief -a android.intent.action.MAIN"
                       " -c android.intent.category.HOME").strip().splitlines()
    return out[-1].strip() if out else ""


def resumed_activity(device):
    out = device.shell("dumpsys activity activities | grep -E 'mResumedActivity|topResumedActivity'")
    for line in out.splitlines():
        for token in line.split():
            if "/" in token and "." in token:
                return token.rstrip("}")
    return ""


def keyevent(device, code):
    device.shell("input keyevent %s" % code)


def screenshot(device, name):
    folder = DATA / "screenshots"
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / ("%s-%s.png" % (now_stamp(), name))
    data = device.screencap()
    if not data or not data.startswith(b"\x89PNG"):
        say("  (captura de ecran a eșuat)")
        return None
    path.write_bytes(data)
    say("  Captură de ecran: %s" % path)
    return path


def disable(device, pkg, reason):
    out = device.shell("pm disable-user --user 0 %s" % pkg).strip()
    if "disabled" in out:
        record_change("disabled", pkg, reason=reason)
        say("  Dezactivat: %s" % pkg)
        return True
    say("  Nu s-a putut dezactiva %s: %s" % (pkg, out))
    return False


def launchable(device):
    """Packages with a TV (leanback) launcher entry."""
    out = device.shell("cmd package query-activities --brief -a android.intent.action.MAIN"
                       " -c android.intent.category.LEANBACK_LAUNCHER")
    found = []
    for line in out.splitlines():
        line = line.strip()
        if "/" in line:
            pkg = line.split("/", 1)[0]
            if pkg not in found:
                found.append(pkg)
    return found


def require_backup():
    if not any((DATA / "backups").glob("*/state.json")):
        sys.exit("Faceți întâi un backup: python tvtool.py backup")


# ---------------------------------------------------------------------------- commands


def local_ip():
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        s.connect(("8.8.8.8", 80))
        return s.getsockname()[0]


def port_open(host, port=ADB_PORT, timeout=0.5):
    try:
        with socket.create_connection((host, port), timeout=timeout):
            return True
    except OSError:
        return False


def cmd_find(args):
    ip = local_ip()
    prefix = ip.rsplit(".", 1)[0]
    say("Calculatorul are adresa %s. Caut televizoare cu ADB pornit în %s.0/24 ..." % (ip, prefix))
    hosts = ["%s.%d" % (prefix, i) for i in range(1, 255)]
    with concurrent.futures.ThreadPoolExecutor(max_workers=64) as pool:
        found = [h for h, ok in zip(hosts, pool.map(port_open, hosts)) if ok]
    if not found:
        say("Nu am găsit niciun aparat cu ADB pornit.")
        say("Verificați pe televizor: Setări > Sistem > Opțiuni pentru dezvoltatori > Depanare USB = Pornit.")
        say("Dacă știți IP-ul televizorului: python tvtool.py connect 192.168.x.y")
        return
    for host in found:
        say("  Găsit: %s" % host)
    state = load_json(STATE_FILE, {})
    state["host"] = found[0]
    save_json(STATE_FILE, state)
    say("Următorul pas: python tvtool.py connect %s" % found[0])


def cmd_connect(args):
    device = open_device(args.ip, args.backend)
    model = device.shell("getprop ro.product.model").strip()
    brand = device.shell("getprop ro.product.brand").strip()
    android = device.shell("getprop ro.build.version.release").strip()
    say("Conectat: %s %s, Android %s" % (brand, model, android))
    say("Ecran principal actual: %s" % current_home(device))
    screenshot(device, "conectat")


def cmd_backup(args):
    device = open_device(args.ip, args.backend)
    folder = DATA / "backups" / now_stamp()
    folder.mkdir(parents=True)
    say("Fac backup în %s ..." % folder)
    dumps = {
        "getprop.txt": "getprop",
        "packages-all.txt": "pm list packages -f -u",
        "packages-enabled.txt": "pm list packages -e",
        "packages-disabled.txt": "pm list packages -d",
        "packages-system.txt": "pm list packages -s",
        "packages-third-party.txt": "pm list packages -3",
        "settings-global.txt": "settings list global",
        "settings-secure.txt": "settings list secure",
        "settings-system.txt": "settings list system",
        "home-activities.txt": "cmd package query-activities --brief -a android.intent.action.MAIN"
                               " -c android.intent.category.HOME",
        "preferred-activities.txt": "dumpsys package preferred-activities",
    }
    for name, command in dumps.items():
        (folder / name).write_text(device.shell(command, timeout=60), encoding="utf-8")
    snapshot = {
        "time": now_stamp(),
        "device": device.serial,
        "model": device.shell("getprop ro.product.model").strip(),
        "home": current_home(device),
        "enabled": packages(device, "-e"),
        "disabled": packages(device, "-d"),
    }
    save_json(folder / "state.json", snapshot)
    state = load_json(STATE_FILE, {})
    state.setdefault("original_home", snapshot["home"])
    state.setdefault("first_backup", str(folder))
    save_json(STATE_FILE, state)
    screenshot(device, "backup")
    say("Backup gata: %d aplicații pornite, %d deja dezactivate, ecran principal %s."
        % (len(snapshot["enabled"]), len(snapshot["disabled"]), snapshot["home"]))


def cmd_scan(args):
    device = open_device(args.ip, args.backend)
    enabled = packages(device, "-e")
    for pkg, category, description, default in catalog.candidates(enabled, keep=dock_packages(enabled)):
        mark = "dezactivez" if default else "întreb"
        say("  [%-26s] %-48s %s  (%s)" % (category, pkg, description, mark))


def dock_packages(installed):
    chosen = []
    for _, options in DOCK_WISHES:
        for pkg in options:
            if pkg in installed:
                chosen.append(pkg)
                break
    return chosen


def cmd_debloat(args):
    require_backup()
    device = open_device(args.ip, args.backend)
    enabled = packages(device, "-e")
    items = catalog.candidates(enabled, keep=dock_packages(enabled))
    if not items:
        say("Nu am găsit nimic de dezactivat.")
        return
    say("Vă întreb pe rând. Enter = recomandarea mea. Nimic nu se dezinstalează;")
    say("totul se poate reporni cu: python tvtool.py restore")
    screenshot(device, "inainte-de-debloat")
    current = None
    for pkg, category, description, default in items:
        if category != current:
            current = category
            say()
            say("== %s ==" % category.upper())
        say("%s\n  %s" % (pkg, description))
        if ask("  Dezactivez?", default):
            disable(device, pkg, category)
    say()
    say("Verific că televizorul merge: apăs Home și fac o captură.")
    keyevent(device, "KEYCODE_HOME")
    time.sleep(3)
    screenshot(device, "dupa-debloat")


def choose_dock(device):
    installed = packages(device)
    dock = dock_packages(installed)
    missing = [name for name, options in DOCK_WISHES if not any(p in installed for p in options)]
    if missing:
        say("Nu sunt instalate: %s (le puteți instala din Google Play și le adăugați apoi"
            " în dock din TV Home, ținând apăsat OK)." % ", ".join(missing))
    extras = [p for p in launchable(device) if p not in dock and p != HOME_PKG]
    tv_like = [p for p in extras if any(w in p.lower() for w in ("tv", "live", "channel", "dtv"))
               and not p.startswith("com.google.android.youtube")]
    if tv_like:
        say("Aplicații care par a fi pentru antenă/canale TV:")
        for pkg in tv_like:
            if ask("  Pun %s în dock?" % pkg, False):
                dock.append(pkg)
    return dock


def cmd_launcher(args):
    require_backup()
    if not APK.exists():
        sys.exit("Lipsește %s" % APK)
    device = open_device(args.ip, args.backend)
    state = load_json(STATE_FILE, {})
    say("1/5 Instalez TV Home ...")
    install_home(device)
    grant_permissions(device)
    if not any(c["action"] == "installed" for c in load_json(CHANGES_FILE, [])):
        record_change("installed", HOME_PKG)

    say("2/5 Configurez dock-ul ...")
    dock = choose_dock(device)
    device.shell("am start -n %s --es dock '%s'" % (HOME_ACTIVITY, ",".join(dock)))
    time.sleep(3)
    say("  Dock: %s" % ", ".join(dock))
    screenshot(device, "tv-home-pornit")

    say("3/5 Setez TV Home ca ecran principal ...")
    original = state.get("original_home") or current_home(device)
    out = device.shell("cmd package set-home-activity %s" % HOME_ACTIVITY).strip()
    say("  %s" % (out or "ok"))
    record_change("home", HOME_ACTIVITY, previous=original)

    say("4/5 Dezactivez ecranul principal Google TV, ca butonul Home să ducă la TV Home.")
    say("    (pe Google TV, %s preia butonul Home dacă rămâne pornit)" % catalog.SETUPWRAITH)
    enabled = packages(device, "-e")
    targets = [p for p in catalog.LAUNCHER_PACKAGES + [catalog.SETUPWRAITH] if p in enabled]
    if targets and ask("  Dezactivez %s?" % ", ".join(targets), True):
        for pkg in targets:
            disable(device, pkg, "launcher")

    # A stale home task from the old launcher can make Home open TV Home *behind* the app.
    device.shell("am force-stop com.android.tv.settings")
    device.shell("am force-stop %s" % HOME_PKG)
    say("5/5 Testez butonul Home din interiorul unei aplicații ...")
    if test_home(device, dock):
        say("Gata! Butonul Home duce la TV Home.")
    else:
        say("Butonul Home NU duce la TV Home. Repun ecranul principal original ...")
        for pkg in targets:
            device.shell("pm enable %s" % pkg)
            record_change("enabled", pkg)
        if original and "/" in original:
            device.shell("cmd package set-home-activity %s" % original)
        say("Am revenit la ecranul vechi. Trimiteți-mi capturile din tv-data/screenshots.")


def grant_permissions(device):
    """Wi-Fi name in the Control Center, and the other apps' Watch Next / channels for the top shelf."""
    for permission in ("android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION",
                       "android.permission.READ_TV_LISTINGS"):
        out = device.shell("pm grant %s %s" % (HOME_PKG, permission)).strip()
        if out:
            say("  %s: %s" % (permission, out))
    # Which apps are used most, so the top shelf refreshes those first (an appop, not a runtime permission).
    out = device.shell("appops set %s GET_USAGE_STATS allow" % HOME_PKG).strip()
    if out:
        say("  GET_USAGE_STATS: %s" % out)
    # Google TV's own navigation clicks would play over TV Home's; restore turns them back on.
    state = load_json(STATE_FILE, {})
    if "sound_effects_enabled" not in state:
        state["sound_effects_enabled"] = device.shell("settings get system sound_effects_enabled").strip()
        save_json(STATE_FILE, state)
    device.shell("settings put system sound_effects_enabled 0")


def install_home(device):
    """Installs dist/tv-home.apk; a build signed with another key needs a reinstall (asked)."""
    out = device.install(APK)
    if "Success" in out:
        return
    if "INSTALL_FAILED_UPDATE_INCOMPATIBLE" in out:
        say("  TV Home de pe televizor e semnat cu altă cheie decât acest APK.")
        if not ask("  Reinstalez TV Home (se dezinstalează doar TV Home, apoi se pune la loc)?", False):
            sys.exit("Instalarea a fost anulată.")
        say("  %s" % device.shell("pm uninstall %s" % HOME_PKG).strip())
        record_change("reinstalled", HOME_PKG)
        out = device.install(APK)
        if "Success" in out:
            return
    sys.exit("Instalarea a eșuat: %s" % out.strip())


def cmd_update(args):
    """Puts a newer dist/tv-home.apk on the TV, keeping the dock and the home-screen setting."""
    require_backup()
    device = open_device(args.ip, args.backend)
    say("Instalez TV Home ...")
    install_home(device)
    grant_permissions(device)
    device.shell("cmd package set-home-activity %s" % HOME_ACTIVITY)
    device.shell("am force-stop %s" % HOME_PKG)
    keyevent(device, "KEYCODE_HOME")
    time.sleep(4)
    screenshot(device, "tv-home-actualizat")
    say("Gata: %s" % current_home(device))


SOURCES_SERVICE = HOME_PKG + "/" + HOME_PKG + ".SourcesService"


def cmd_surse(args):
    """Lets TV Home keep its accessibility service on (Sources panel, PS5 card, app switcher).

    TCL's auto-start blocker switches the service off after every update and some restarts; with
    WRITE_SECURE_SETTINGS the launcher switches it back on itself whenever it starts."""
    device = open_device(args.ip, args.backend)
    if args.off:
        device.shell("pm revoke %s android.permission.WRITE_SECURE_SETTINGS" % HOME_PKG)
        current = device.shell("settings get secure enabled_accessibility_services").strip()
        kept = [s for s in current.split(":") if s and s != "null" and s != SOURCES_SERVICE]
        device.shell("settings put secure enabled_accessibility_services '%s'" % ":".join(kept))
        say("Serviciul TV Home Surse este oprit și nu mai pornește singur.")
        return
    out = device.shell("pm grant %s android.permission.WRITE_SECURE_SETTINGS" % HOME_PKG).strip()
    if out:
        sys.exit("Nu am putut da permisiunea: %s" % out)
    device.shell("am force-stop %s" % HOME_PKG)
    keyevent(device, "KEYCODE_HOME")
    time.sleep(4)
    on = SOURCES_SERVICE in device.shell("settings get secure enabled_accessibility_services")
    say("TV Home Surse: %s" % ("pornit" if on else "încă oprit (deschide o dată ecranul principal)"))


NOTICE_LISTENER = HOME_PKG + "/" + HOME_PKG + ".NoticeListener"


def cmd_notificari(args):
    """Lets TV Home see notifications, to show them as its own Liquid Glass cards."""
    device = open_device(args.ip, args.backend)
    if args.off:
        device.shell("cmd notification disallow_listener %s" % NOTICE_LISTENER)
        say("TV Home nu mai vede notificările.")
        return
    out = device.shell("cmd notification allow_listener %s" % NOTICE_LISTENER).strip()
    if out:
        sys.exit("Nu am putut porni accesul la notificări: %s" % out)
    say("TV Home arată acum notificările ca carduri Liquid Glass. Oprire: python tvtool.py notificari --off")


def cmd_profile(args):
    """Shows a picture in the circle next to the clock (the Google account photo, for example),
    and/or gives the profile in use a name (--name)."""
    device = open_device(args.ip, args.backend)
    if args.name:
        device.shell("am start -n %s --es profile_name '%s'" % (HOME_ACTIVITY, args.name.replace("'", "")))
        say("Profilul se numește acum „%s”." % args.name)
        if not args.image:
            return
    if not args.image:
        sys.exit("Dați o poză (jpg/png/webp) sau --name.")
    source = Path(args.image)
    if not source.exists():
        sys.exit("Nu găsesc %s" % source)
    files_dir = WALLPAPER_DIR.rsplit("/", 1)[0]
    device.shell("am start -n %s" % HOME_ACTIVITY)
    time.sleep(2)
    device.shell("mkdir -p %s" % files_dir)
    for old in ("profile.jpg", "profile.png", "profile.webp"):
        device.shell("rm -f %s/%s" % (files_dir, old))
    suffix = source.suffix.lower() if source.suffix.lower() in (".jpg", ".png", ".webp") else ".jpg"
    device.push(str(source), "%s/profile%s" % (files_dir, suffix))
    device.shell("am force-stop %s" % HOME_PKG)
    keyevent(device, "KEYCODE_HOME")
    time.sleep(4)
    screenshot(device, "profil")
    say("Poza de profil a fost pusă pe televizor.")


FILES_DIR = WALLPAPER_DIR.rsplit("/", 1)[0]
CAMERAS_FILE = DATA / "cameras.txt"
TMDB_KEY_FILE = DATA / "tmdb.key"


def cmd_tmdb(args):
    """Puts the TMDB key on the TV for Cinema (free: themoviedb.org > Settings > API).

    The key is asked without showing it, kept in tv-data/tmdb.key (never committed) and copied
    into TV Home's own folder on the TV."""
    key = load_text(TMDB_KEY_FILE).strip()
    if args.new or not key:
        import getpass
        key = getpass.getpass("Cheia TMDB (API Read Access Token sau API Key; nu se afișează): ").strip()
        if not key:
            sys.exit("Nu ați introdus nicio cheie.")
    request = urllib.request.Request("https://api.themoviedb.org/3/configuration"
                                     + ("" if "." in key else "?api_key=" + urllib.parse.quote(key)))
    if "." in key:
        request.add_header("Authorization", "Bearer " + key)
    try:
        urllib.request.urlopen(request, timeout=15).read()
    except urllib.error.HTTPError as e:
        sys.exit("TMDB nu acceptă cheia (%s)." % e.code)
    except OSError as e:
        sys.exit("Nu am putut verifica cheia: %s" % e)
    TMDB_KEY_FILE.parent.mkdir(parents=True, exist_ok=True)
    TMDB_KEY_FILE.write_text(key + "\n", encoding="utf-8")
    device = open_device(args.ip, args.backend)
    device.shell("am start -n %s" % HOME_ACTIVITY)
    time.sleep(2)
    device.shell("mkdir -p %s" % FILES_DIR)
    device.push(str(TMDB_KEY_FILE), FILES_DIR + "/tmdb.key")
    say("Cheia TMDB este pe televizor. Cinema: Control Center > Cinema.")


def cmd_folder(args):
    """Groups apps into a folder on the grid (or removes the folder)."""
    device = open_device(args.ip, args.backend)
    if args.remove:
        device.shell("am start -n %s --es folder_remove '%s'" % (HOME_ACTIVITY, args.name))
        say("Folderul „%s” a fost desfăcut." % args.name)
    else:
        if not args.packages:
            sys.exit("Dați pachetele separate prin virgulă, sau --remove.")
        device.shell("am start -n %s --es folder_set '%s=%s'" % (HOME_ACTIVITY, args.name, args.packages))
        say("Folder „%s”: %s" % (args.name, args.packages))
    time.sleep(3)
    screenshot(device, "folder")


def cmd_cameras(args):
    """Security cameras for the Control Center: a Name=url list kept in tv-data/cameras.txt."""
    lines = [l for l in load_text(CAMERAS_FILE).splitlines() if "=" in l]
    if args.action == "add":
        if not args.name or not args.url:
            sys.exit("cameras add \"Nume\" rtsp://utilizator:parola@ip:554/cale")
        lines = [l for l in lines if not l.startswith(args.name + "=")] + ["%s=%s" % (args.name, args.url)]
    elif args.action == "remove":
        lines = [l for l in lines if not l.startswith((args.name or "") + "=")]
    CAMERAS_FILE.parent.mkdir(parents=True, exist_ok=True)
    CAMERAS_FILE.write_text("\n".join(lines) + ("\n" if lines else ""), encoding="utf-8")
    for line in lines:
        say("  " + line)
    if not lines:
        say("  (nicio cameră)")
    if args.action in ("add", "remove", "push"):
        device = open_device(args.ip, args.backend)
        device.shell("am start -n %s" % HOME_ACTIVITY)
        time.sleep(2)
        device.shell("mkdir -p %s" % FILES_DIR)
        if lines:
            device.push(str(CAMERAS_FILE), FILES_DIR + "/cameras.txt")
        else:
            device.shell("rm -f %s/cameras.txt" % FILES_DIR)
        say("Lista de camere a fost pusă pe televizor (Control Center > butonul cameră).")


SOUND_NAMES = {
    "move": ("navigate", "move", "cursor", "focus", "hover"),
    "select": ("click", "select", "enter", "ok", "tap"),
    "back": ("back", "cancel", "return"),
    "limit": ("limit", "bump", "edge"),
    "open": ("open", "menu", "panel"),
}


def cmd_sounds(args):
    """Copies navigation sounds (a folder or a Kodi ui-sounds zip) onto the TV; TV Home prefers them."""
    import tempfile
    import zipfile
    source = Path(args.source)
    if not source.exists():
        sys.exit("Nu găsesc %s" % source)
    found = {}
    tmp = Path(tempfile.mkdtemp(prefix="tvtool-sounds-"))
    candidates = []
    if source.is_file() and source.suffix.lower() == ".zip":
        with zipfile.ZipFile(source) as archive:
            for member in archive.namelist():
                if member.lower().endswith((".wav", ".mp3", ".ogg")):
                    target = tmp / Path(member).name
                    target.write_bytes(archive.read(member))
                    candidates.append(target)
    else:
        candidates = [p for p in source.iterdir() if p.suffix.lower() in (".wav", ".mp3", ".ogg")]
    for ours, aliases in SOUND_NAMES.items():
        for candidate in candidates:
            if candidate.stem.lower() in aliases or candidate.stem.lower() == ours:
                found[ours] = candidate
                break
    if not found:
        sys.exit("Nu am recunoscut niciun fișier (aștept nume ca navigate/click/back/move/select).")
    device = open_device(args.ip, args.backend)
    device.shell("am start -n %s" % HOME_ACTIVITY)
    time.sleep(2)
    device.shell("mkdir -p %s/sounds" % FILES_DIR)
    for ours, candidate in found.items():
        device.push(str(candidate), "%s/sounds/%s.wav" % (FILES_DIR, ours))
        say("  %s <- %s" % (ours, candidate.name))
    device.shell("am force-stop %s" % HOME_PKG)
    keyevent(device, "KEYCODE_HOME")
    say("Sunetele au fost puse pe televizor. Revenire la cele încorporate: python tvtool.py sounds --reset")


def cmd_sounds_reset(device):
    device.shell("rm -rf %s/sounds" % FILES_DIR)
    device.shell("am force-stop %s" % HOME_PKG)
    keyevent(device, "KEYCODE_HOME")
    say("Sunetele încorporate sunt din nou active.")


def cmd_icons(args):
    """Control Center icons: PNGs named power/wifi/moon/timer/image/access/search/camera/folder/gear/list/tv/hdmi/antenna/bluetooth."""
    device = open_device(args.ip, args.backend)
    if args.reset:
        device.shell("rm -rf %s/icons" % FILES_DIR)
        say("Pictogramele încorporate sunt din nou active.")
    else:
        source = Path(args.source or "")
        if not source.is_dir():
            sys.exit("Dați un folder cu fișiere PNG (nume: power.png, wifi.png, ...).")
        pngs = sorted(source.glob("*.png"))
        if not pngs:
            sys.exit("Niciun PNG în %s" % source)
        device.shell("am start -n %s" % HOME_ACTIVITY)
        time.sleep(2)
        device.shell("mkdir -p %s/icons" % FILES_DIR)
        for png in pngs:
            device.push(str(png), "%s/icons/%s" % (FILES_DIR, png.name.lower()))
            say("  " + png.name)
    device.shell("am force-stop %s" % HOME_PKG)
    keyevent(device, "KEYCODE_HOME")


def load_text(path):
    try:
        return path.read_text(encoding="utf-8")
    except OSError:
        return ""


def test_home(device, dock):
    app = dock[0] if dock else "com.android.tv.settings"
    device.shell("monkey -p %s -c android.intent.category.LEANBACK_LAUNCHER 1" % app)
    time.sleep(6)
    screenshot(device, "in-aplicatie-%s" % app)
    keyevent(device, "KEYCODE_HOME")
    time.sleep(3)
    top = resumed_activity(device)
    screenshot(device, "dupa-home")
    say("  Activitatea din față după Home: %s" % top)
    return top.startswith(HOME_PKG)


# Apple's aerials are drone shots of coasts, deserts, islands and cities at golden hour:
# the featured categories below are the closest free equivalents on Wikimedia Commons.
COMMONS_CATEGORIES = [
    "Featured pictures from unmanned aerial vehicles",
    "Featured pictures of aerial photographs",
    "Featured pictures of sunsets",
    "Featured pictures of beaches",
    "Featured pictures of islands",
    "Featured pictures of deserts",
    "Featured pictures of coasts",
    "Featured pictures of lakes",
    "Featured pictures of mountains",
    "Featured pictures of landscapes",
    "Featured pictures of waterfalls",
    "Featured pictures of forests",
]


def commons(**params):
    params["format"] = "json"
    url = "https://commons.wikimedia.org/w/api.php?" + urllib.parse.urlencode(params)
    request = urllib.request.Request(url, headers={"User-Agent": "tvtool/1.0 (personal TV wallpaper)"})
    for attempt in range(4):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            if error.code != 429 or attempt == 3:
                raise
            time.sleep(5 * (attempt + 1))   # Commons rate limit: back off and retry


def find_wallpapers(width=1920):
    """Freely licensed landscape photos (public domain, CC0, CC BY, CC BY-SA) from Commons featured pictures."""
    found = []
    for category in COMMONS_CATEGORIES:
        try:
            result = commons(action="query", generator="categorymembers", gcmtitle="Category:" + category,
                             gcmtype="file", gcmlimit="50", prop="imageinfo",
                             iiprop="url|size|extmetadata", iiurlwidth=str(width))
        except OSError as error:
            say("  %s: %s" % (category, error))
            continue
        for page in result.get("query", {}).get("pages", {}).values():
            if "imageinfo" not in page:
                continue
            info = page["imageinfo"][0]
            meta = info.get("extmetadata", {})
            licence = meta.get("LicenseShortName", {}).get("value", "")
            free = licence.lower() in ("public domain", "cc0") or licence.upper().startswith("CC BY")
            if free and info.get("width", 0) >= width and 1.4 < info["width"] / info.get("height", 1) < 2.0:
                found.append({"title": page["title"], "licence": licence, "url": info["thumburl"],
                              "page": info["descriptionurl"]})
        time.sleep(2.5)
    return found


def caption(title):
    """'File:Lake Bled (Slovenia) 2019.jpg' -> 'Lake Bled' for the top-shelf title."""
    name = title.split(":", 1)[-1].rsplit(".", 1)[0].replace("_", " ")
    name = re.sub(r"\(.*?\)|\b(18|19|20)\d\d\b.*|\bDSC\w*|\bIMG\w*", "", name)
    name = re.split(r"\s+[-–,]\s+|,", name)[0].strip(" -–")
    return name[:40]


def screen_width(device):
    """Physical width of the TV panel (3840 on a 4K TV), capped to 3840."""
    out = device.shell("wm size")
    try:
        size = out.strip().splitlines()[-1].split(":")[-1].strip()
        return min(3840, max(int(n) for n in size.split("x")))
    except (ValueError, IndexError):
        return 1920


def vividness(path):
    """Mean colour saturation of a photo (0..1), as a cheap measure of how vivid it looks."""
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        with Image.open(path) as image:
            small = image.convert("RGB").resize((96, 54))
            hsv = small.convert("HSV")
            sat = list(hsv.getdata(1))
            val = list(hsv.getdata(2))
            # Weight saturation by brightness so dark, muddy photos do not count as colourful.
            score = sum(s * v for s, v in zip(sat, val)) / (255.0 * 255.0 * len(sat))
            return score
    except OSError:
        return None


def cmd_wallpapers(args):
    device = open_device(args.ip, args.backend)
    folder = DATA / "wallpapers"
    folder.mkdir(parents=True, exist_ok=True)
    existing = sorted(folder.glob("*.jpg"))
    if len(existing) < args.count:
        say("Caut fotografii cu natură în domeniul public pe Wikimedia Commons ...")
        pool = find_wallpapers(screen_width(device))
        random.shuffle(pool)
        credits = []
        captions = []
        # With --vivid we download more candidates than needed and keep the most colourful ones,
        # the way Apple's aerials are chosen: saturated skies, water and foliage.
        wanted = args.count - len(existing)
        candidates = pool[: wanted * 3] if args.vivid else pool[:wanted]
        scored = []
        for index, item in enumerate(candidates):
            name = "candidate-%02d.jpg" % index
            request = urllib.request.Request(item["url"], headers={"User-Agent": "tvtool/1.0"})
            try:
                with urllib.request.urlopen(request, timeout=60) as response:
                    (folder / name).write_bytes(response.read())
            except OSError as error:
                say("  %s: %s" % (item["title"], error))
                continue
            score = vividness(folder / name) if args.vivid else 0
            scored.append((score if score is not None else 0, name, item))
            say("  %s  %s%s" % (name, item["title"], "  (culoare %.2f)" % score if score else ""))
            time.sleep(1)
        scored.sort(key=lambda entry: -entry[0])
        for score, name, item in scored[:wanted]:
            final = "nature-%02d.jpg" % (len(existing) + len(credits) + 1)
            (folder / name).rename(folder / final)
            credits.append("%s  %s  (%s)  %s" % (final, item["title"], item["licence"], item["page"]))
            captions.append("%s=%s" % (final, caption(item["title"])))
        for score, name, item in scored[wanted:]:
            (folder / name).unlink(missing_ok=True)
        with open(folder / "CREDITS.txt", "a", encoding="utf-8") as handle:
            handle.write("\n".join(credits) + ("\n" if credits else ""))
        with open(folder / "captions.txt", "a", encoding="utf-8") as handle:
            handle.write("\n".join(captions) + ("\n" if captions else ""))
    # Opening TV Home makes Android create its files folder.
    device.shell("am start -n %s" % HOME_ACTIVITY)
    time.sleep(2)
    device.shell("mkdir -p %s" % WALLPAPER_DIR)
    pictures = sorted(p for p in folder.iterdir() if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"))
    for picture in pictures + [folder / "captions.txt"]:
        if picture.exists():
            device.push(str(picture), "%s/%s" % (WALLPAPER_DIR, picture.name))
    say("Am pus %d fundaluri pe televizor." % len(pictures))
    device.shell("am start -n %s --ei wallpaper_seconds %d --ez show_place %s --ez builtin_wallpapers %s"
                 % (HOME_ACTIVITY, args.seconds, "true" if args.show_place else "false",
                    "false" if args.only_mine else "true"))
    keyevent(device, "KEYCODE_HOME")
    time.sleep(4)
    screenshot(device, "fundaluri")


def cmd_dock(args):
    device = open_device(args.ip, args.backend)
    device.shell("am start -n %s --es dock '%s'" % (HOME_ACTIVITY, args.packages))
    time.sleep(3)
    screenshot(device, "dock")


def cmd_shot(args):
    device = open_device(args.ip, args.backend)
    screenshot(device, args.name)


def cmd_restore(args):
    device = open_device(args.ip, args.backend)
    changes = load_json(CHANGES_FILE, [])
    state = load_json(STATE_FILE, {})
    if not changes:
        say("Nu am nimic de anulat.")
        return
    say("Repornesc tot ce am dezactivat ...")
    for pkg in sorted({c["package"] for c in changes if c["action"] == "disabled"}):
        out = device.shell("pm enable %s" % pkg).strip()
        say("  %s: %s" % (pkg, out))
    original = state.get("original_home", "")
    if original and "/" in original and not original.startswith(HOME_PKG):
        say("Repun ecranul principal original: %s" % original)
        device.shell("cmd package set-home-activity %s" % original)
    previous = state.get("sound_effects_enabled")
    if previous in ("0", "1"):
        device.shell("settings put system sound_effects_enabled %s" % previous)
    if args.uninstall_home:
        say("Dezinstalez TV Home ...")
        say("  %s" % device.shell("pm uninstall %s" % HOME_PKG).strip())
    keyevent(device, "KEYCODE_HOME")
    time.sleep(4)
    screenshot(device, "dupa-restaurare")
    archive = DATA / ("changes-restored-%s.json" % now_stamp())
    CHANGES_FILE.rename(archive)
    say("Gata. Istoricul a fost mutat în %s" % archive)


def write_restore_scripts():
    """Standalone restore scripts for people who have the adb program."""
    changes = load_json(CHANGES_FILE, [])
    state = load_json(STATE_FILE, {})
    host = state.get("host", "IP_TELEVIZOR")
    pkgs = sorted({c["package"] for c in changes if c["action"] == "disabled"})
    original = state.get("original_home", "")
    lines = ["adb connect %s:%d" % (host, ADB_PORT)]
    lines += ["adb -s %s:%d shell pm enable %s" % (host, ADB_PORT, p) for p in pkgs]
    if original and "/" in original:
        lines.append("adb -s %s:%d shell cmd package set-home-activity %s" % (host, ADB_PORT, original))
    lines.append("adb -s %s:%d shell input keyevent KEYCODE_HOME" % (host, ADB_PORT))
    DATA.mkdir(exist_ok=True)
    (DATA / "restore.sh").write_text("#!/bin/sh\n# Undo tvtool changes (needs adb)\n" + "\n".join(lines) + "\n",
                                     encoding="utf-8")
    (DATA / "restore.bat").write_text("@echo off\r\nrem Undo tvtool changes (needs adb)\r\n"
                                      + "\r\n".join(lines) + "\r\npause\r\n", encoding="utf-8")
    try:
        os.chmod(DATA / "restore.sh", 0o755)
    except OSError:
        pass


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--ip", help="adresa IP a televizorului (implicit: cea salvată)")
    parser.add_argument("--backend", choices=["auto", "adb-shell", "adb"], default="auto")
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("find", help="caută televizorul în rețea")
    p = sub.add_parser("connect", help="se conectează la televizor")
    p.add_argument("ip", nargs="?")
    sub.add_parser("backup", help="salvează starea televizorului")
    sub.add_parser("scan", help="arată ce s-ar putea dezactiva (fără schimbări)")
    sub.add_parser("debloat", help="dezactivează reclame/telemetrie, întrebând")
    sub.add_parser("launcher", help="instalează și testează TV Home")
    p = sub.add_parser("wallpapers", help="pune fundaluri cu natură pe televizor")
    p.add_argument("--count", type=int, default=12)
    p.add_argument("--seconds", type=int, default=300, help="cât stă fiecare fundal")
    p.add_argument("--show-place", action="store_true", help="arată numele locului din fotografie, sus în stânga")
    p.add_argument("--vivid", action="store_true", help="alege fotografiile cele mai colorate (descarcă de 3x mai multe)")
    p.add_argument("--only-mine", action="store_true", help="nu mai folosi fundalurile încorporate în TV Home")
    p = sub.add_parser("dock", help="setează aplicațiile din dock")
    p.add_argument("packages", help="pachete separate prin virgulă")
    sub.add_parser("update", help="pune pe televizor un dist/tv-home.apk mai nou")
    p = sub.add_parser("folder", help="grupează aplicații într-un folder pe grilă")
    p.add_argument("name", help="numele folderului")
    p.add_argument("packages", nargs="?", help="pachete separate prin virgulă")
    p.add_argument("--remove", action="store_true", help="desface folderul")
    p = sub.add_parser("cameras", help="camere de supraveghere în Control Center")
    p.add_argument("action", choices=["list", "add", "remove", "push"])
    p.add_argument("name", nargs="?")
    p.add_argument("url", nargs="?", help="rtsp://… sau http://… (MJPEG/HLS)")
    p = sub.add_parser("icons", help="pictograme PNG pentru Control Center (ex. exportate din SF Symbols)")
    p.add_argument("source", nargs="?")
    p.add_argument("--reset", action="store_true", help="revine la pictogramele încorporate")
    p = sub.add_parser("sounds", help="sunete de navigare (folder sau zip Kodi ui-sounds)")
    p.add_argument("source", nargs="?")
    p.add_argument("--reset", action="store_true", help="revine la sunetele încorporate")
    p = sub.add_parser("profile", help="poza din cercul de lângă ceas și numele profilului")
    p.add_argument("image", nargs="?", help="fișier jpg/png/webp")
    p.add_argument("--name", help="numele profilului folosit acum")
    p = sub.add_parser("surse", help="ține pornit panoul Surse / cardul PS5 / comutatorul de aplicații")
    p.add_argument("--off", action="store_true", help="oprește serviciul și retrage permisiunea")
    p = sub.add_parser("notificari", help="notificările sistemului ca carduri Liquid Glass")
    p.add_argument("--off", action="store_true", help="retrage accesul la notificări")
    p = sub.add_parser("tmdb", help="cheia TMDB pentru Cinema (filme și unde le găsiți în România)")
    p.add_argument("--new", action="store_true", help="introduceți altă cheie")
    p = sub.add_parser("shot", help="captură de ecran")
    p.add_argument("name", nargs="?", default="ecran")
    p = sub.add_parser("restore", help="anulează toate schimbările")
    p.add_argument("--uninstall-home", action="store_true", help="dezinstalează și TV Home")
    args = parser.parse_args(argv)
    commands = {
        "find": cmd_find, "connect": cmd_connect, "backup": cmd_backup, "scan": cmd_scan,
        "debloat": cmd_debloat, "launcher": cmd_launcher, "wallpapers": cmd_wallpapers,
        "dock": cmd_dock, "shot": cmd_shot, "restore": cmd_restore,
        "update": cmd_update, "profile": cmd_profile, "folder": cmd_folder, "cameras": cmd_cameras,
        "icons": cmd_icons, "surse": cmd_surse, "notificari": cmd_notificari, "tmdb": cmd_tmdb,
        "sounds": lambda a: cmd_sounds_reset(open_device(a.ip, a.backend)) if a.reset else cmd_sounds(a),
    }
    commands[args.command](args)


if __name__ == "__main__":
    main()
