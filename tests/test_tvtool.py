"""Tests with a fake TV, so the logic can be checked without a real device."""

import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

import catalog  # noqa: E402
import tvtool  # noqa: E402

PNG = b"\x89PNG\r\n\x1a\nfake"


class FakeTV(tvtool.Device):
    serial = "192.168.1.50:5555"

    def __init__(self, installed):
        self.enabled = set(installed)
        self.disabled = set()
        self.home = "com.google.android.apps.tv.launcherx/.home.HomeActivity"
        self.commands = []
        self.top = ""

    def shell(self, command, timeout=30):
        self.commands.append(command)
        if command.startswith("pm list packages"):
            if "-d" in command:
                pkgs = self.disabled
            elif "-e" in command:
                pkgs = self.enabled
            else:
                pkgs = self.enabled | self.disabled
            return "".join("package:%s\n" % p for p in sorted(pkgs))
        if command.startswith("pm disable-user"):
            pkg = command.split()[-1]
            self.enabled.discard(pkg)
            self.disabled.add(pkg)
            return "Package %s new state: disabled-user" % pkg
        if command.startswith("pm enable"):
            pkg = command.split()[-1]
            self.disabled.discard(pkg)
            self.enabled.add(pkg)
            return "Package %s new state: enabled" % pkg
        if command.startswith("cmd package resolve-activity"):
            return "priority=0\n%s\n" % self.home
        if command.startswith("cmd package set-home-activity"):
            self.home = command.split()[-1]
            return "Success"
        if command.startswith("pm install"):
            self.enabled.add(tvtool.HOME_PKG)
            return "Success"
        if command.startswith("cmd package query-activities"):
            return "".join("%s/.Main\n" % p for p in sorted(self.enabled) if not p.startswith("com.tcl.bi"))
        if command == "input keyevent KEYCODE_HOME":
            self.top = self.home
        if command.startswith("monkey"):
            self.top = command.split()[2] + "/.Main"
        if command.startswith("dumpsys activity activities"):
            return "  mResumedActivity: ActivityRecord{1 u0 %s t1}" % self.top
        return ""

    def screencap(self):
        return PNG

    def push(self, local, remote):
        self.commands.append("push %s %s" % (local, remote))


INSTALLED = {
    "android", "com.android.systemui", "com.google.android.gms", "com.google.android.apps.mediashell",
    "com.google.android.apps.tv.launcherx", "com.google.android.tungsten.setupwraith",
    "com.google.android.tvrecommendations", "com.google.android.katniss", "com.netflix.ninja",
    "com.google.android.youtube.tv", "com.wbd.stream", "com.tcl.esticker", "com.tcl.bi",
    "com.tcl.tvinput", "com.tcl.factory.view", "com.tcl.mysterybox", "com.tcl.tv",
}


@pytest.fixture
def tv(tmp_path, monkeypatch):
    monkeypatch.setattr(tvtool, "DATA", tmp_path)
    monkeypatch.setattr(tvtool, "STATE_FILE", tmp_path / "state.json")
    monkeypatch.setattr(tvtool, "CHANGES_FILE", tmp_path / "changes.json")
    monkeypatch.setattr(tvtool.time, "sleep", lambda s: None)
    fake = FakeTV(INSTALLED)
    monkeypatch.setattr(tvtool, "open_device", lambda host=None, backend="auto": fake)
    return fake


def run(*argv):
    tvtool.main(list(argv))


def test_candidates_never_include_protected_packages():
    names = [c[0] for c in catalog.candidates(INSTALLED, keep=["com.netflix.ninja"])]
    for protected in ["android", "com.google.android.apps.mediashell", "com.tcl.tvinput",
                      "com.tcl.factory.view", "com.google.android.apps.tv.launcherx",
                      catalog.SETUPWRAITH, "com.netflix.ninja", "com.tcl.tv"]:
        assert protected not in names
    assert "com.tcl.esticker" in names
    assert "com.tcl.mysterybox" in names  # unknown TCL app: asked about, default no
    assert dict((c[0], c[3]) for c in catalog.candidates(INSTALLED))["com.tcl.mysterybox"] is False


def test_debloat_requires_backup(tv):
    with pytest.raises(SystemExit):
        run("debloat")


def test_backup_debloat_restore_round_trip(tv, monkeypatch):
    run("backup")
    monkeypatch.setattr("builtins.input", lambda prompt="": "")  # accept every recommendation
    run("debloat")
    assert "com.tcl.esticker" in tv.disabled
    assert "com.google.android.tvrecommendations" in tv.disabled
    assert "com.google.android.katniss" not in tv.disabled  # default is to keep it
    assert not any(c.startswith("pm uninstall") for c in tv.commands)
    restore_sh = (tvtool.DATA / "restore.sh").read_text()
    assert "pm enable com.tcl.esticker" in restore_sh
    run("restore")
    assert tv.disabled == set()
    assert any((tvtool.DATA / "screenshots").glob("*dupa-restaurare.png"))


def test_launcher_sets_home_and_passes_home_button_test(tv, monkeypatch, tmp_path):
    apk = tmp_path / "tv-home.apk"
    apk.write_bytes(b"apk")
    monkeypatch.setattr(tvtool, "APK", apk)
    run("backup")
    monkeypatch.setattr("builtins.input", lambda prompt="": "")
    run("launcher")
    assert tv.home == tvtool.HOME_ACTIVITY
    assert "com.google.android.tungsten.setupwraith" in tv.disabled
    assert "com.google.android.apps.tv.launcherx" in tv.disabled
    dock_cmd = next(c for c in tv.commands if "--es dock" in c)
    assert "com.google.android.youtube.tv,com.netflix.ninja,com.wbd.stream" in dock_cmd
    run("restore")
    assert tv.home.startswith("com.google.android.apps.tv.launcherx")
    assert "com.google.android.apps.tv.launcherx" in tv.enabled


def test_launcher_rolls_back_when_home_button_fails(tv, monkeypatch, tmp_path):
    apk = tmp_path / "tv-home.apk"
    apk.write_bytes(b"apk")
    monkeypatch.setattr(tvtool, "APK", apk)
    monkeypatch.setattr(tvtool, "resumed_activity", lambda device: "com.google.android.apps.tv.launcherx/.Home")
    run("backup")
    monkeypatch.setattr("builtins.input", lambda prompt="": "")
    run("launcher")
    assert "com.google.android.apps.tv.launcherx" in tv.enabled
    assert tv.home.startswith("com.google.android.apps.tv.launcherx")


def test_caption_cleans_commons_titles():
    assert tvtool.caption("File:Lake Bled (Slovenia) 2019.jpg") == "Lake Bled"
    assert tvtool.caption("File:Moraine_Lake_-_Banff_National_Park.jpg") == "Moraine Lake"
    assert tvtool.caption("File:Ciucaș Mountains, Romania.jpg") == "Ciucaș Mountains"
