#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
tvkit.py - Toolkit ADB para Android TV / Google TV.
Flujo: conectar -> backup -> debloat reversible -> launcher minimalista -> verificacion.
Uso:  python3 tvkit.py [--ip 192.168.1.50] [--serial X] <all|backup|debloat|launcher|verify|build|restore>
NUNCA desinstala paquetes del sistema: solo `pm disable-user --user 0`.
"""
import argparse, json, os, platform, shutil, subprocess, sys, time
from datetime import datetime
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BACKUPS = ROOT / "backups"
PKG_FILE = ROOT / "packages.json"
WALLS = ROOT / "wallpapers"
PROJ = ROOT.parent / "android-launcher"
APK = PROJ / "app/build/outputs/apk/debug/app-debug.apk"
L_PKG = "com.minimaltv.launcher"
L_ACT = f"{L_PKG}/.MainActivity"
APKS = ROOT / "apks"          # coloca aqui el APK ya compilado (FLauncher / Projectivy)
PROFILES = {
    # pkg = applicationId | glob = patron del archivo dentro de toolkit/apks/
    "flauncher":  {"pkg": "me.efesser.flauncher",    "glob": "*launcher*.apk",
                   "get": "https://gitlab.com/etienn01/flauncher/-/releases"},
    "projectivy": {"pkg": "com.spocky.projengmenu",  "glob": "*projectivy*.apk",
                   "get": "APK oficial del desarrollador (Spocky) / tienda de apps de la TV"},
    "custom":     {"pkg": L_PKG,                    "glob": "*minimaltv*.apk",
                   "get": "GitHub Actions: pestana Actions > Build MinimalTV APK > artifact minimaltv-apk"},
}
IS_WIN = os.name == "nt"


# ----------------------------------------------------------------- utilidades
def find_adb():
    p = shutil.which("adb")
    if p:
        return p
    home = Path.home()
    cands = [
        home / "Library/Android/sdk/platform-tools/adb",            # macOS Android Studio
        Path("/opt/homebrew/bin/adb"), Path("/usr/local/bin/adb"),  # macOS Homebrew
        Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
        home / "Android/Sdk/platform-tools/adb",
    ]
    for c in cands:
        if c.exists():
            return str(c)
    sys.exit("[!] adb no encontrado. Instala platform-tools (macOS: brew install android-platform-tools).")


ADB = find_adb()


def ask(q, default=False):
    d = "S/n" if default else "s/N"
    r = input(f"{q} [{d}]: ").strip().lower()
    return default if not r else r in ("s", "si", "sí", "y", "yes")


class Dev:
    def __init__(self, serial):
        self.serial = serial

    def cmd(self, *a, timeout=90):
        p = subprocess.run([ADB, "-s", self.serial, *a], capture_output=True, text=True,
                           errors="replace", timeout=timeout)
        return (p.stdout + p.stderr).strip()

    def sh(self, c, timeout=90):
        return self.cmd("shell", c, timeout=timeout)

    def screenshot(self, path):
        p = subprocess.run([ADB, "-s", self.serial, "exec-out", "screencap", "-p"],
                           capture_output=True, timeout=40)
        if len(p.stdout) > 1000:
            Path(path).write_bytes(p.stdout)
            print(f"   [captura] {path}")
        else:
            print("   [!] No se pudo capturar pantalla (¿app con DRM/FLAG_SECURE?)")

    def pkgs(self, flags):
        out = self.sh(f"pm list packages {flags}")
        return sorted(l.strip()[8:] for l in out.splitlines() if l.strip().startswith("package:"))

    def mem_avail_mb(self):
        for l in self.sh("cat /proc/meminfo").splitlines():
            if l.startswith("MemAvailable"):
                return int(l.split()[1]) // 1024
        return -1


def connect(args):
    if args.ip:
        target = args.ip if ":" in args.ip else f"{args.ip}:{args.port}"
        print(subprocess.run([ADB, "connect", target], capture_output=True, text=True).stdout.strip())
        serial = target
    elif args.serial:
        serial = args.serial
    else:
        out = subprocess.run([ADB, "devices"], capture_output=True, text=True).stdout.splitlines()[1:]
        ok = [l.split()[0] for l in out if l.strip().endswith("device")]
        if not ok:
            sys.exit("[!] Sin dispositivos. Activa 'Depuracion de red/USB' en la TV y usa --ip <IP_TV>.")
        serial = ok[0]
    d = Dev(serial)
    subprocess.run([ADB, "-s", serial, "wait-for-device"], timeout=30)
    model = d.sh("getprop ro.product.model")
    if "unauthorized" in model.lower() or not model:
        sys.exit("[!] Acepta el dialogo 'Permitir depuracion' en la TV y reintenta.")
    print(f"[OK] Conectado: {serial} | {model} | Android {d.sh('getprop ro.build.version.release')} "
          f"| RAM libre {d.mem_avail_mb()} MB")
    return d


def latest_backup():
    if not BACKUPS.exists():
        return None
    ds = sorted(p for p in BACKUPS.iterdir() if p.is_dir())
    return ds[-1] if ds else None


# ----------------------------------------------------------------- restore scripts
SH = r'''#!/usr/bin/env bash
# AUTOGENERADO por tvkit.py - revierte TODO lo hecho por el toolkit (un clic).
ADB="${ADB:-adb}"; DEV="__DEV__"; BK="__BK__"
$ADB connect "$DEV" >/dev/null 2>&1; $ADB -s "$DEV" wait-for-device
echo "[*] Rehabilitando paquetes deshabilitados por el toolkit..."
[ -f "$BK/disabled_by_toolkit.txt" ] && while IFS= read -r p; do
  [ -n "$p" ] && $ADB -s "$DEV" shell pm enable --user 0 "$p" >/dev/null 2>&1 && echo "  + $p"
done < "$BK/disabled_by_toolkit.txt"
echo "[*] Restaurando ajustes..."
[ -f "$BK/settings_changes.txt" ] && while read -r ns key val; do
  if [ "$val" = "null" ]; then $ADB -s "$DEV" shell settings delete "$ns" "$key"
  else $ADB -s "$DEV" shell settings put "$ns" "$key" "$val"; fi
done < "$BK/settings_changes.txt"
if [ -f "$BK/restore_home.txt" ] && [ -s "$BK/restore_home.txt" ]; then
  $ADB -s "$DEV" shell cmd package set-home-activity "$(cat "$BK/restore_home.txt")"
fi
echo "[*] Quitando launcher minimalista..."
[ -s "$BK/launcher_pkg.txt" ] && $ADB -s "$DEV" uninstall "$(cat "$BK/launcher_pkg.txt")" >/dev/null 2>&1
$ADB -s "$DEV" shell input keyevent KEYCODE_HOME
echo "[OK] Restauracion completa."
'''

BAT = r'''@echo off
REM AUTOGENERADO por tvkit.py - revierte TODO lo hecho por el toolkit (un clic).
set ADB=adb
set DEV=__DEV__
set BK=__BK__
%ADB% connect %DEV% >nul 2>&1
%ADB% -s %DEV% wait-for-device
echo [*] Rehabilitando paquetes...
if exist "%BK%\disabled_by_toolkit.txt" for /f "usebackq delims=" %%P in ("%BK%\disabled_by_toolkit.txt") do %ADB% -s %DEV% shell pm enable --user 0 %%P
echo [*] Restaurando ajustes...
if exist "%BK%\settings_changes.txt" for /f "usebackq tokens=1,2,* delims= " %%a in ("%BK%\settings_changes.txt") do (
  if "%%c"=="null" (%ADB% -s %DEV% shell settings delete %%a %%b) else (%ADB% -s %DEV% shell settings put %%a %%b %%c)
)
if exist "%BK%\restore_home.txt" for /f "usebackq delims=" %%H in ("%BK%\restore_home.txt") do %ADB% -s %DEV% shell cmd package set-home-activity %%H
echo [*] Quitando launcher minimalista...
if exist "%BK%\launcher_pkg.txt" for /f "usebackq delims=" %%L in ("%BK%\launcher_pkg.txt") do %ADB% -s %DEV% uninstall %%L >nul 2>&1
%ADB% -s %DEV% shell input keyevent KEYCODE_HOME
echo [OK] Restauracion completa.
pause
'''


def write_restore(d, bk):
    for tpl, name in ((SH, "restore.sh"), (BAT, "restore.bat")):
        txt = tpl.replace("__DEV__", d.serial).replace("__BK__", str(bk))
        if name.endswith(".bat"):
            txt = txt.replace("\n", "\r\n")
        for dest in (bk / name, ROOT / name):
            dest.write_text(txt, encoding="utf-8", newline="")
            if name.endswith(".sh"):
                dest.chmod(0o755)
    print(f"   [restore] {ROOT/'restore.sh'}  |  {ROOT/'restore.bat'}")


# ----------------------------------------------------------------- 1. backup
def backup(d):
    print("\n=== 1. RESPALDO ===")
    bk = BACKUPS / datetime.now().strftime("%Y%m%d_%H%M%S")
    bk.mkdir(parents=True)
    (bk / "packages_enabled.txt").write_text("\n".join(d.pkgs("-e")), encoding="utf-8")
    (bk / "packages_disabled.txt").write_text("\n".join(d.pkgs("-d")), encoding="utf-8")
    (bk / "packages_third_party.txt").write_text(d.sh("pm list packages -f -3"), encoding="utf-8")
    for ns in ("system", "secure", "global"):
        (bk / f"settings_{ns}.txt").write_text(d.sh(f"settings list {ns}"), encoding="utf-8")
    (bk / "getprop.txt").write_text(d.sh("getprop"), encoding="utf-8")
    home = d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN "
                "-c android.intent.category.HOME").splitlines()
    comp = next((l.strip() for l in reversed(home) if "/" in l), "")
    (bk / "restore_home.txt").write_text(comp, encoding="utf-8")
    for f in ("disabled_by_toolkit.txt", "settings_changes.txt"):
        (bk / f).write_text("", encoding="utf-8")
    d.screenshot(bk / "screenshot_before.png")
    write_restore(d, bk)
    print(f"[OK] Backup en {bk} (home original: {comp or 'desconocido'})")
    return bk


# ----------------------------------------------------------------- 2. debloat
def load_cfg():
    return json.loads(PKG_FILE.read_text(encoding="utf-8"))


def is_protected(cfg, pkg):
    return any(pkg == p or pkg.startswith(p + ".") or (p.endswith(".") and pkg.startswith(p))
               for p in cfg["protected_prefixes"])


def disable(d, bk, pkg):
    out = d.sh(f"pm disable-user --user 0 {pkg}")
    if "disabled" in out.lower():
        d.sh(f"am force-stop {pkg}")
        with open(bk / "disabled_by_toolkit.txt", "a", encoding="utf-8") as f:
            f.write(pkg + "\n")
        print(f"   [-] {pkg}")
        return True
    print(f"   [!] No se pudo deshabilitar {pkg}: {out[:90]}")
    return False


def set_setting(d, bk, ns, key, val):
    old = d.sh(f"settings get {ns} {key}").strip() or "null"
    with open(bk / "settings_changes.txt", "a", encoding="utf-8") as f:
        f.write(f"{ns} {key} {old}\n")
    d.sh(f"settings put {ns} {key} {val}")


def debloat(d, bk, stock_launchers=False):
    print("\n=== 2. DEBLOAT REVERSIBLE ===")
    cfg = load_cfg()
    enabled = set(d.pkgs("-e"))
    ram0 = d.mem_avail_mb()
    known, count = set(), 0
    for g in cfg["groups"]:
        known |= set(g["packages"])
        if g.get("stock_launchers") != stock_launchers:
            continue
        found = {p: t for p, t in g["packages"].items() if p in enabled and
                 (g.get("stock_launchers") or not is_protected(cfg, p))}
        if not found:
            continue
        print(f"\n--- {g['name']} ---\n{g['desc']}")
        if g["mode"] == "group":
            for p, t in found.items():
                print(f"   * {p}  ({t})")
            if ask("¿Deshabilitar este grupo?", True):
                count += sum(disable(d, bk, p) for p in found)
        else:
            for p, t in found.items():
                if ask(f"   ¿Deshabilitar {p}  [{t}]?"):
                    count += disable(d, bk, p)
    if not stock_launchers:
        sysp = d.pkgs("-s -e")
        sus = [p for p in sysp if p not in known and not is_protected(cfg, p)
               and any(k in p.lower() for k in cfg["heuristic_keywords"])]
        if sus:
            print("\n--- Sospechosos (heuristico, riesgo medio: revisa cada uno) ---")
            for p in sus:
                if ask(f"   ¿Deshabilitar {p}?"):
                    count += disable(d, bk, p)
        if ask("\n¿Acelerar animaciones del sistema (0.5x)? (revertible)", True):
            for k in ("window_animation_scale", "transition_animation_scale", "animator_duration_scale"):
                set_setting(d, bk, "global", k, "0.5")
        d.sh("pm trim-caches 999G")
        d.sh("am kill-all")
        time.sleep(2)
        print(f"\n[OK] {count} paquetes deshabilitados | RAM libre {ram0} MB -> {d.mem_avail_mb()} MB")
        d.screenshot(bk / "screenshot_after_debloat.png")
    return count


# ----------------------------------------------------------------- 3. launcher
def build():
    print("\n=== Compilando launcher ===")
    gw = PROJ / ("gradlew.bat" if IS_WIN else "gradlew")
    if gw.exists():
        cmd = [str(gw)]
    elif shutil.which("gradle"):
        cmd = ["gradle"]
    else:
        print("[!] Sin gradle/gradlew. Abre android-launcher/ en Android Studio -> Build > Build APK(s), "
              "o instala gradle (brew install gradle / choco install gradle) y repite 'build'.")
        return False
    r = subprocess.run(cmd + ["assembleDebug"], cwd=PROJ, shell=IS_WIN)
    return r.returncode == 0 and APK.exists()


def find_apk(profile, apk_arg, apk_url):
    """Localiza el APK precompilado: --apk > --apk-url (descarga) > toolkit/apks/<patron>."""
    if apk_arg:
        return Path(apk_arg).expanduser()
    if apk_url:
        import urllib.request
        APKS.mkdir(exist_ok=True)
        dest = APKS / f"{profile}_downloaded.apk"
        print(f"   Descargando {apk_url} ...")
        urllib.request.urlretrieve(apk_url, dest)
        return dest
    pat = PROFILES[profile]["glob"]
    hits = sorted(APKS.glob(pat)) if pat else []
    if not hits and profile != "custom":   # cualquier otro .apk de la carpeta
        hits = sorted(h for h in APKS.glob("*.apk") if "minimaltv" not in h.name.lower())
    return hits[-1] if len(hits) >= 1 else None


def resolve_home_component(d, pkg):
    """Obtiene 'paquete/Clase' de la actividad HOME del launcher sin asumir nombres."""
    for cat in ("android.intent.category.HOME", "android.intent.category.LEANBACK_LAUNCHER",
                "android.intent.category.LAUNCHER"):
        out = d.sh(f"cmd package resolve-activity --brief -p {pkg} -a android.intent.action.MAIN -c {cat}")
        comp = next((l.strip() for l in reversed(out.splitlines()) if l.strip().startswith(pkg + "/")), "")
        if comp:
            return comp
    return ""


def launcher(d, bk, profile="flauncher", apk_arg=None, apk_url=None):
    print(f"\n=== 3. LAUNCHER: {profile} ===")
    pkg = PROFILES[profile]["pkg"]
    apk = find_apk(profile, apk_arg, apk_url)
    if profile == "custom" and (not apk or not Path(apk).exists()) and APK.exists():
        apk = APK
    if not apk or not Path(apk).exists():
        sys.exit(f"[!] No hay APK. Descargalo desde: {PROFILES[profile]['get']}\n"
                 f"    y copialo en {APKS}/ (o usa --apk RUTA / --apk-url URL).")
    already = pkg in d.pkgs("")
    print(d.cmd("install", "-r", "-g", str(apk), timeout=240))
    if pkg not in d.pkgs(""):
        sys.exit("[!] La instalacion fallo (¿APK de otra arquitectura/firmado distinto?).")
    if not already:   # solo se desinstalara en restore si lo instalo el toolkit
        (bk / "launcher_pkg.txt").write_text(pkg, encoding="utf-8")
    if profile == "custom":
        wdir = f"/sdcard/Android/data/{L_PKG}/files/wallpapers"
        d.sh(f"mkdir -p {wdir}")
        for p in WALLS.glob("*"):
            if p.suffix.lower() in (".jpg", ".jpeg", ".png", ".webp"):
                d.cmd("push", str(p), f"{wdir}/{p.name}")
    comp = resolve_home_component(d, pkg)
    if not comp:
        print("[!] No pude resolver la actividad principal; abre el launcher a mano una vez y repite.")
        return False, ""
    d.sh(f"am start -n {comp}")
    time.sleep(2)
    d.sh(f"cmd package set-home-activity {comp}")
    chk = d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME")
    ok = pkg in chk
    print("[OK] Launcher predeterminado." if ok else
          "[!] No quedo como predeterminado: continua con el paso siguiente (deshabilitar launcher de fabrica).")
    return ok, comp


def stock_step(d, bk, ok, comp):
    print("\n=== Mapeo del boton Home ===")
    print("Nota: en Chromecast con Google TV, al deshabilitar el launcher de fabrica el boton YouTube del control deja de funcionar.")
    d.sh("input keyevent KEYCODE_HOME")
    time.sleep(2)
    if ask("¿Deshabilitar los launchers de fabrica para que Home SIEMPRE abra el nuevo?", ok):
        debloat(d, bk, stock_launchers=True)
        if comp:
            d.sh(f"cmd package set-home-activity {comp}")
            d.sh(f"am start -n {comp}")


# ----------------------------------------------------------------- 4. verificacion
def verify(d, bk=None):
    print("\n=== 4. COMPROBACION ===")
    bk = bk or latest_backup()
    d.sh("input keyevent KEYCODE_HOME")
    time.sleep(3)
    d.screenshot(bk / "screenshot_final.png")
    print(d.sh("cmd package resolve-activity --brief -a android.intent.action.MAIN -c android.intent.category.HOME"))
    print(f"Deshabilitados por el toolkit: {len((bk/'disabled_by_toolkit.txt').read_text().split())}")
    print(f"RAM libre: {d.mem_avail_mb()} MB | Capturas en: {bk}")


def restore(d):
    bk = latest_backup()
    if not bk:
        sys.exit("[!] No hay backups.")
    script = bk / ("restore.bat" if IS_WIN else "restore.sh")
    subprocess.run([str(script)] if IS_WIN else ["bash", str(script)])


# ----------------------------------------------------------------- main
def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ip"); ap.add_argument("--port", default="5555"); ap.add_argument("--serial")
    ap.add_argument("--launcher", default="flauncher", choices=list(PROFILES))
    ap.add_argument("--apk", help="ruta a un APK de launcher ya compilado")
    ap.add_argument("--apk-url", help="URL directa del APK (se descarga en el PC)")
    ap.add_argument("action", nargs="?", default="all",
                    choices=["all", "backup", "debloat", "launcher", "verify", "build", "restore"])
    a = ap.parse_args()
    if a.action == "build" and a.launcher != "custom":
        sys.exit("[i] 'build' solo aplica a --launcher custom; con FLauncher/Projectivy usa un APK precompilado.")
    if a.action == "build":
        sys.exit(0 if build() else 1)
    d = connect(a)
    if a.action == "all":
        bk = backup(d)
        debloat(d, bk)
        ok, comp = launcher(d, bk, a.launcher, a.apk, a.apk_url)
        stock_step(d, bk, ok, comp)
        verify(d, bk)
    elif a.action == "backup":
        backup(d)
    elif a.action == "debloat":
        debloat(d, latest_backup() or backup(d))
    elif a.action == "launcher":
        bk = latest_backup() or backup(d)
        ok, comp = launcher(d, bk, a.launcher, a.apk, a.apk_url)
        stock_step(d, bk, ok, comp)
    elif a.action == "verify":
        verify(d)
    elif a.action == "restore":
        restore(d)


if __name__ == "__main__":
    main()
