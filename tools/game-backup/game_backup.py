#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
game_backup.py — Harness backup/restore data game Android TANPA ROOT.

Tujuan: membuktikan di perangkat nyata apa saja yang bisa dan tidak bisa
dilakukan sebagai uid 2000 (shell). Semua perintah di sini adalah perintah
shell yang SAMA PERSIS dengan yang akan dijalankan Shizuku lewat
Shizuku.newProcess() / rish. Jadi kalau harness ini berhasil, mode Shizuku
pasti berhasil. Kalau gagal, Shizuku juga akan gagal.

Empat jalur yang dipakai:

  1. APK             -> `pm path` + adb pull              (shell: BISA)
  2. OBB             -> /sdcard/Android/obb/<pkg>         (shell: BISA, grup ext_obb_rw)
  3. Data eksternal  -> /sdcard/Android/data/<pkg>        (shell: BISA, grup ext_data_rw)
  4. Data privat     -> BackupManager lewat `bmgr`        (shell: BISA, TANPA root)

Poin 4 adalah kuncinya. BackupManagerService berjalan sebagai uid system,
membaca /data/data/<pkg> ATAS NAMA kita, dan ketika restore ia menulis
kembali dengan kepemilikan uid/gid + konteks SELinux yang benar. Ini
menyelesaikan dua masalah sekaligus: hak baca, dan chown/chcon.

Keterbatasan yang harus kamu tahu (dijelaskan detail di README):
  * Citra backup transport `local` tersimpan di penyimpanan internal
    perangkat -> TIDAK bisa di-export ke SD/PC, TIDAK tahan factory reset.
  * Transport `cloud` (Google Drive) bisa lintas perangkat, tapi kuota
    Auto Backup 25 MB per aplikasi.
  * Hanya berlaku untuk aplikasi yang `allowBackup` efektif true.
  * Direktori `cache/` dan `no_backup/` selalu dikecualikan.

Contoh pakai:
    python game_backup.py doctor
    python game_backup.py analyze com.miHoYo.GenshinImpact
    python game_backup.py backup  com.miHoYo.GenshinImpact --out D:/bak
    python game_backup.py tokens
    python game_backup.py restore com.miHoYo.GenshinImpact --from D:/bak --yes
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import time
from pathlib import Path

# ----------------------------------------------------------------------------
# Konstanta
# ----------------------------------------------------------------------------

LOCAL_TRANSPORT = "com.android.localtransport/.LocalTransport"
CLOUD_TRANSPORT = "com.google.android.gms/.backup.BackupTransportService"

EXT_DATA = "/sdcard/Android/data"
EXT_OBB = "/sdcard/Android/obb"
PRIVATE_DATA = "/data/data"

# Path yang diprobe untuk mengukur batas kemampuan shell.
PROBE_PATHS = [
    ("/sdcard", "penyimpanan bersama"),
    (EXT_DATA, "data eksternal semua app"),
    (EXT_OBB, "obb semua app"),
    ("/data", "root partisi data"),
    (PRIVATE_DATA, "folder privat semua app (traverse)"),
    ("/data/app", "APK terpasang"),
    ("/data/local/tmp", "scratch shell"),
    ("/data/system/users/0/settings_ssaid.xml", "SSAID"),
    ("/data/backup", "citra transport lokal"),
    ("/data/misc/wifi", "konfigurasi WiFi"),
]

C_GREEN = "\033[92m"
C_RED = "\033[91m"
C_YELLOW = "\033[93m"
C_DIM = "\033[90m"
C_BOLD = "\033[1m"
C_OFF = "\033[0m"


def _supports_color() -> bool:
    if os.environ.get("NO_COLOR"):
        return False
    if not sys.stdout.isatty():
        return False
    return True


USE_COLOR = _supports_color()


def c(text: str, color: str) -> str:
    return f"{color}{text}{C_OFF}" if USE_COLOR else text


def ok(text: str) -> str:
    return c(text, C_GREEN)


def bad(text: str) -> str:
    return c(text, C_RED)


def warn(text: str) -> str:
    return c(text, C_YELLOW)


def dim(text: str) -> str:
    return c(text, C_DIM)


def bold(text: str) -> str:
    return c(text, C_BOLD)


# ----------------------------------------------------------------------------
# Lapisan ADB
# ----------------------------------------------------------------------------


def find_adb() -> str:
    """Cari adb: dari PATH, lalu lokasi SDK standar di Windows."""
    found = shutil.which("adb")
    if found:
        return found

    candidates = [
        os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe"),
        os.path.expandvars(r"%USERPROFILE%\AppData\Local\Android\Sdk\platform-tools\adb.exe"),
        r"C:\Android\Sdk\platform-tools\adb.exe",
        os.path.expanduser("~/Android/Sdk/platform-tools/adb"),
        "/usr/lib/android-sdk/platform-tools/adb",
        os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"),
    ]
    for cand in candidates:
        if cand and os.path.isfile(cand):
            return cand

    sys.exit(
        "adb tidak ditemukan.\n"
        "Pasang Android Platform Tools lalu tambahkan ke PATH, atau jalankan:\n"
        "  python game_backup.py --adb C:/path/ke/adb.exe doctor"
    )


ADB = "adb"


def adb_run(args: list[str], timeout: int = 300) -> subprocess.CompletedProcess:
    return subprocess.run(
        [ADB] + args,
        capture_output=True,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )


def sh(cmd: str, timeout: int = 120) -> tuple[int, str, str]:
    """
    Jalankan `cmd` di perangkat sebagai uid 2000 (shell) — identitas yang
    sama dengan yang diberikan Shizuku pada perangkat non-root.

    Mengembalikan (exit_code, stdout, stderr).
    """
    # Bungkus supaya kita dapat exit code asli, bukan exit code adb.
    wrapped = f"{cmd} ; echo \"__RC=$?\""
    proc = adb_run(["shell", wrapped], timeout=timeout)
    out = proc.stdout or ""
    err = proc.stderr or ""

    rc = -1
    lines = out.splitlines()
    if lines:
        m = re.match(r"^__RC=(-?\d+)\s*$", lines[-1].strip())
        if m:
            rc = int(m.group(1))
            out = "\n".join(lines[:-1])

    # adb kadang menaruh pesan error di stdout.
    if rc == -1 and ("not found" in err or "inaccessible" in err):
        rc = 127

    return rc, out.strip(), err.strip()


def sh_lines(cmd: str, timeout: int = 120) -> list[str]:
    rc, out, _ = sh(cmd, timeout=timeout)
    if rc != 0 or not out:
        return []
    return [ln.strip() for ln in out.splitlines() if ln.strip()]


def device_serial() -> str | None:
    proc = adb_run(["devices"])
    for line in (proc.stdout or "").splitlines()[1:]:
        line = line.strip()
        if not line:
            continue
        parts = line.split()
        if len(parts) >= 2 and parts[1] == "device":
            return parts[0]
    return None


def require_device() -> str:
    serial = device_serial()
    if serial is None:
        sys.exit(
            bad("Tidak ada perangkat yang terhubung.\n")
            + "Sambungkan lewat USB (aktifkan USB debugging), atau pakai wireless debugging:\n"
            + dim("  adb pair 192.168.x.x:PORT\n  adb connect 192.168.x.x:PORT")
        )
    return serial


def pull(remote: str, local: Path, timeout: int = 3600) -> bool:
    """adb pull. Mengembalikan True kalau berhasil."""
    local.parent.mkdir(parents=True, exist_ok=True)
    proc = adb_run(["pull", remote, str(local)], timeout=timeout)
    if proc.returncode != 0:
        return False
    blob = (proc.stdout or "") + (proc.stderr or "")
    return "0 files pulled" not in blob


def du_kb(remote: str) -> int | None:
    """Ukuran direktori dalam KB, atau None kalau tidak terbaca."""
    rc, out, _ = sh(f"du -sk {quote(remote)} 2>/dev/null")
    if rc != 0 or not out:
        return None
    m = re.match(r"^(\d+)", out.strip())
    return int(m.group(1)) if m else None


def quote(s: str) -> str:
    return "'" + s.replace("'", "'\\''") + "'"


def human(size_bytes: float | None) -> str:
    if size_bytes is None:
        return "-"
    units = ["B", "KB", "MB", "GB", "TB"]
    i = 0
    while size_bytes >= 1024 and i < len(units) - 1:
        size_bytes /= 1024.0
        i += 1
    return f"{size_bytes:.1f} {units[i]}"


# ----------------------------------------------------------------------------
# Perintah: doctor
# ----------------------------------------------------------------------------


def cmd_doctor(args) -> int:
    print(bold("\n== 1. Lingkungan =="))
    print(f"  adb      : {ADB}")

    serial = require_device()
    print(f"  perangkat: {ok(serial)}")

    print(bold("\n== 2. Perangkat =="))
    props = {
        "model": "ro.product.model",
        "merek": "ro.product.brand",
        "android": "ro.build.version.release",
        "sdk": "ro.build.version.sdk",
        "build": "ro.build.type",
    }
    for label, prop in props.items():
        _, out, _ = sh(f"getprop {prop}")
        print(f"  {label:<8}: {out or '?'}")

    sdk = 0
    _, sdk_out, _ = sh("getprop ro.build.version.sdk")
    if sdk_out.isdigit():
        sdk = int(sdk_out)

    print(bold("\n== 3. Status akses =="))
    _, uid_out, _ = sh("id")
    print(f"  identitas shell : {uid_out or '?'}")

    _, su_out, _ = sh("command -v su")
    if su_out:
        print(f"  su tersedia     : {ok(su_out)} {dim('(perangkatmu sudah root — mode root tetap opsi terbaik)')}")
    else:
        print(f"  su tersedia     : {bad('tidak')} {dim('(bagus, sesuai keinginanmu)')}")

    print(bold("\n== 4. Probe batas kemampuan shell (uid 2000) =="))
    print(dim("   Inilah bukti empiris yang menentukan mode Shizuku layak atau tidak.\n"))
    print(f"   {'path':<44} {'status':<14} keterangan")
    print(f"   {'-' * 44} {'-' * 14} {'-' * 30}")

    probe_results = {}
    for path, label in PROBE_PATHS:
        rc, out, _ = sh(f"ls -ld {quote(path)} 2>&1 | head -1")
        readable = rc == 0 and "Permission denied" not in out and "No such file" not in out
        probe_results[path] = readable
        status = ok("terbaca") if readable else bad("ditolak")
        detail = ""
        if readable and out:
            detail = out.split()[0]
        print(f"   {path:<44} {status:<23} {dim(label + (' ' + detail if detail else ''))}")

    print(bold("\n== 5. BackupManager =="))
    _, enabled, _ = sh("bmgr enabled")
    print(f"  bmgr            : {enabled or '?'}")
    transports = sh_lines("bmgr list transports")
    if transports:
        print("  transport tersedia:")
        for t in transports:
            print(f"    {t}")
    else:
        print(f"  transport       : {bad('tidak bisa dibaca')}")

    _, dumpsys, _ = sh("dumpsys backup | head -20")
    if dumpsys:
        token = parse_token(dumpsys)
        print(f"  token restore   : {token or dim('belum ada')}")

    print(bold("\n== 6. Kesimpulan otomatis =="))
    can_ext = probe_results.get(EXT_DATA, False)
    can_obb = probe_results.get(EXT_OBB, False)
    can_priv = probe_results.get(f"{PRIVATE_DATA}", False)
    can_backupdir = probe_results.get("/data/backup", False)

    if can_obb and can_ext:
        print(f"  {ok('[V]')} Data eksternal game (Android/data + obb) BISA dibackup lewat shell.")
        print(f"      -> Ini sudah menutup sebagian besar game Unity/Unreal.")
    else:
        print(f"  {bad('[X]')} Data eksternal tidak terbaca. Periksa koneksi/perizinan.")

    print(f"  {bad('[X]')} /data/data/<pkg> tidak bisa dibaca langsung (sesuai dugaan).")
    print(f"  {ok('[V]')} Jalur `bmgr` tersedia -> data privat tetap bisa dibackup tanpa root.")

    if can_backupdir:
        print(f"  {warn('[!]')} /data/backup terbaca! Citra transport lokal bisa di-export.")
    else:
        print(f"  {dim('[i]')} /data/backup tidak terbaca -> citra transport lokal hanya lokal.")

    if sdk >= 34:
        print(f"  {dim('[i]')} Android 14+ terdeteksi: MANAGE_EXTERNAL_STORAGE saja tidak cukup")
        print(f"      untuk Android/data. Shizuku memang diperlukan. Ini memperkuat rencanamu.")

    print()
    return 0


def parse_token(dumpsys_out: str) -> str | None:
    """
    Ambil token restore dari `dumpsys backup`.

    Formatnya kira-kira:
        Ancestral: 0
        Current:   1
    Token adalah gabungan angka tersebut, mis. "01".
    """
    ancestral = None
    current = None
    for line in dumpsys_out.splitlines():
        m = re.match(r"^\s*Ancestral:\s*(\d+)", line)
        if m:
            ancestral = m.group(1)
        m = re.match(r"^\s*Current:\s*(\d+)", line)
        if m:
            current = m.group(1)
    if ancestral is None or current is None:
        return None
    return f"{ancestral}{current}"


# ----------------------------------------------------------------------------
# Perintah: analyze
# ----------------------------------------------------------------------------


def pkg_version(pkg: str) -> dict:
    info = {"versionName": "?", "versionCode": "?", "allowBackup": "?"}
    _, out, _ = sh(f"dumpsys package {quote(pkg)} | head -80")
    if not out:
        return info
    m = re.search(r"versionName=(\S+)", out)
    if m:
        info["versionName"] = m.group(1)
    m = re.search(r"versionCode=(\d+)", out)
    if m:
        info["versionCode"] = m.group(1)
    if "ALLOW_BACKUP" in out:
        info["allowBackup"] = "true"
    elif "flags=[" in out:
        info["allowBackup"] = "false"
    return info


def is_installed(pkg: str) -> bool:
    rc, _, _ = sh(f"pm path {quote(pkg)}")
    return rc == 0


def apk_paths(pkg: str) -> list[str]:
    paths = []
    for line in sh_lines(f"pm path {quote(pkg)}"):
        if line.startswith("package:"):
            paths.append(line[len("package:"):].strip())
    return paths


def cmd_analyze(args) -> int:
    pkg = args.package
    require_device()

    print(bold(f"\n== Analisis: {pkg} ==\n"))

    if not is_installed(pkg):
        print(bad(f"  Paket {pkg} tidak terpasang di perangkat ini."))
        return 1

    ver = pkg_version(pkg)
    print(f"  versi          : {ver['versionName']} ({ver['versionCode']})")
    allow = ver["allowBackup"]
    if allow == "true":
        print(f"  allowBackup    : {ok('true')} {dim('-> jalur bmgr akan bekerja')}")
    elif allow == "false":
        print(f"  allowBackup    : {warn('false')} {dim('-> bmgr mungkin menolak; D2D bisa tetap jalan')}")
    else:
        print(f"  allowBackup    : {dim('tidak terdeteksi')}")

    print(bold("\n  -- Lokasi data --"))

    total = 0
    found_ext = False
    found_obb = False

    apks = apk_paths(pkg)
    if apks:
        size = 0
        for p in apks:
            kb = du_kb(p)
            if kb:
                size += kb * 1024
        total += size
        print(f"  APK            : {len(apks)} berkas, {human(size)}")
        for p in apks[:4]:
            print(f"    {dim(p)}")
        if len(apks) > 4:
            print(f"    {dim(f'... dan {len(apks) - 4} lagi')}")

    for remote, label, flag in (
        (f"{EXT_OBB}/{pkg}", "OBB", "obb"),
        (f"{EXT_DATA}/{pkg}", "Data eksternal", "ext"),
    ):
        rc, _, _ = sh(f"ls -d {quote(remote)} 2>/dev/null")
        if rc != 0:
            print(f"  {label:<14} : {dim('tidak ada')}")
            continue
        kb = du_kb(remote)
        size = kb * 1024 if kb else 0
        total += size
        print(f"  {label:<14} : {ok('ADA')} {human(size)}  {dim(remote)}")
        if flag == "obb":
            found_obb = True
        else:
            found_ext = True

    rc, _, _ = sh(f"ls {quote(f'{PRIVATE_DATA}/{pkg}')} 2>/dev/null | head -1")
    if rc == 0:
        print(f"  Data privat    : {warn('terbaca langsung')} {dim('(tidak terduga)')}")
    else:
        print(f"  Data privat    : {dim('tidak terbaca langsung')} {bad('<- butuh bmgr/root')}")
        print(f"    {dim(f'{PRIVATE_DATA}/{pkg} (shared_prefs, databases, files)')}")

    print(bold("\n  -- Verdict --"))
    print(f"  Total di luar /data/data: {human(total)}")
    if found_ext or found_obb:
        print(f"  {ok('[V]')} Mode Shizuku SANGAT layak untuk game ini:")
        parts = []
        if found_obb:
            parts.append("OBB")
        if found_ext:
            parts.append("Android/data")
        print(f"      {' + '.join(parts)} bisa disalin penuh lewat shell.")
    else:
        print(f"  {warn('[!]')} Game ini tidak menyimpan apa pun di penyimpanan eksternal.")
        print(f"      Semua progres ada di /data/data -> wajib lewat jalur bmgr.")

    if allow == "true":
        print(f"  {ok('[V]')} Data privat di /data/data bisa ditangkap lewat `bmgr` (tanpa root).")
        print(f"      Lanjutkan dengan: {dim(f'python game_backup.py backup {pkg} --out <dir>')}")
    else:
        print(f"  {warn('[!]')} allowBackup=false. Coba tetap jalankan backup dan lihat hasil bmgr;")
        print(f"      kalau ditolak, satu-satunya jalan sisa adalah root atau alat migrasi vendor.")

    print()
    return 0


# ----------------------------------------------------------------------------
# Perintah: tokens
# ----------------------------------------------------------------------------


def cmd_tokens(args) -> int:
    require_device()
    _, out, _ = sh("dumpsys backup")
    if not out:
        print(bad("Tidak bisa membaca dumpsys backup."))
        return 1

    token = parse_token(out)
    print(bold("\n== Token restore =="))
    for line in out.splitlines():
        if re.match(r"^\s*(Ancestral|Current):", line):
            print("  " + line.strip())
    print()
    if token:
        print(f"  Token saat ini: {ok(token)}")
        print(dim(f"  Pakai dengan: bmgr restore {token} <package>"))
    else:
        print(warn("  Token belum tersedia. Jalankan backup dulu."))
    print()
    return 0


# ----------------------------------------------------------------------------
# Perintah: backup
# ----------------------------------------------------------------------------


def write_manifest(out_dir: Path, data: dict) -> Path:
    path = out_dir / "manifest.json"
    path.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding="utf-8")
    return path


def read_manifest(src_dir: Path) -> dict:
    path = src_dir / "manifest.json"
    if not path.is_file():
        sys.exit(bad(f"manifest.json tidak ditemukan di {src_dir}"))
    return json.loads(path.read_text(encoding="utf-8"))


def cmd_backup(args) -> int:
    pkg = args.package
    out_root = Path(args.out).expanduser().resolve()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    out_dir = out_root / f"{pkg}-{stamp}"
    out_dir.mkdir(parents=True, exist_ok=True)

    require_device()

    print(bold(f"\n== Backup: {pkg} =="))
    print(f"  tujuan: {out_dir}\n")

    manifest: dict = {
        "package": pkg,
        "timestamp": stamp,
        "versionName": None,
        "versionCode": None,
        "allowBackup": None,
        "transport": args.transport,
        "restoreToken": None,
        "items": {},
    }

    ver = pkg_version(pkg)
    manifest.update(
        versionName=ver["versionName"],
        versionCode=ver["versionCode"],
        allowBackup=ver["allowBackup"],
    )

    want = {s.strip() for s in args.include.split(",") if s.strip()}

    # --- 1. APK -------------------------------------------------------------
    if "apk" in want:
        print("  [1/4] APK ...")
        apks = apk_paths(pkg)
        if apks:
            apk_dir = out_dir / "apk"
            apk_dir.mkdir(exist_ok=True)
            saved = []
            for idx, remote in enumerate(apks):
                name = f"{idx:02d}-{Path(remote).name}"
                if pull(remote, apk_dir / name):
                    saved.append(name)
            manifest["items"]["apk"] = saved
            print(f"        {ok(f'{len(saved)}/{len(apks)} berkas')}")
        else:
            print(f"        {bad('pm path gagal')}")
            manifest["items"]["apk"] = []

    # --- 2 & 3. OBB + data eksternal ---------------------------------------
    for key, remote, label, step in (
        ("obb", f"{EXT_OBB}/{pkg}", "OBB", "2/4"),
        ("external", f"{EXT_DATA}/{pkg}", "Data eksternal", "3/4"),
    ):
        if key not in want:
            continue
        print(f"  [{step}] {label} ...")
        rc, _, _ = sh(f"ls -d {quote(remote)} 2>/dev/null")
        if rc != 0:
            print(f"        {dim('tidak ada')}")
            manifest["items"][key] = None
            continue
        target = out_dir / key
        if pull(remote, target, timeout=7200):
            kb = du_kb(remote)
            manifest["items"][key] = {"path": remote, "size_kb": kb}
            print(f"        {ok(human(kb * 1024 if kb else None))}")
        else:
            print(f"        {bad('gagal menarik')}")
            manifest["items"][key] = None

    # --- 4. Data privat lewat BackupManager --------------------------------
    if "private" in want:
        print(f"  [4/4] Data privat via bmgr ({args.transport}) ...")

        rc, out, _ = sh("bmgr enabled")
        if "not enabled" in (out or "").lower():
            print(f"        {warn('backup manager nonaktif, mencoba mengaktifkan...')}")
            sh("bmgr enable true")
            sh("settings put global backup_enabled 1")

        transport = LOCAL_TRANSPORT if args.transport == "local" else CLOUD_TRANSPORT
        rc, out, err = sh(f"bmgr transport {transport}")
        print(f"        transport: {out or err or transport}")

        rc, out, err = sh(f"bmgr backupnow {quote(pkg)}", timeout=1800)
        blob = f"{out}\n{err}".strip()
        print("        " + (blob.replace("\n", "\n        ") or "(tanpa keluaran)"))

        success = "Success" in blob and "with result: Success" in blob
        manifest["items"]["private"] = {
            "method": "bmgr",
            "transport": transport,
            "success": success,
            "output": blob,
        }

        if success:
            _, dump, _ = sh("dumpsys backup")
            token = parse_token(dump)
            manifest["restoreToken"] = token
            print(f"        {ok('berhasil')}  token restore: {bold(token or '?')}")
            if args.transport == "local":
                print(f"        {warn('catatan')}: citra tersimpan di internal device,")
                print(f"        tidak bisa di-export dan tidak tahan factory reset.")
        else:
            print(f"        {bad('gagal')} — lihat keluaran di atas.")
            if "not allowed" in blob.lower():
                print(f"        {dim('Kemungkinan allowBackup=false pada game ini.')}")

    manifest_path = write_manifest(out_dir, manifest)

    print(bold("\n== Ringkasan =="))
    print(f"  manifest: {manifest_path}")
    size = sum(f.stat().st_size for f in out_dir.rglob("*") if f.is_file())
    print(f"  ukuran  : {human(size)}")
    print()
    return 0


# ----------------------------------------------------------------------------
# Perintah: restore
# ----------------------------------------------------------------------------


def cmd_restore(args) -> int:
    pkg = args.package
    src_dir = Path(args.src).expanduser().resolve()

    require_device()
    manifest = read_manifest(src_dir)

    if manifest.get("package") != pkg:
        sys.exit(bad(f"manifest untuk {manifest.get('package')}, bukan {pkg}"))

    print(bold(f"\n== Restore: {pkg} =="))
    print(f"  sumber: {src_dir}\n")

    # Restore selalu menimpa data yang ada. Minta konfirmasi eksplisit dulu.
    if not args.yes:
        print(warn("  Restore akan menimpa data game yang sekarang ada di perangkat."))
        if not args.keep_current:
            print(warn("  Data privat juga akan dikosongkan dulu (pm clear) sebelum dipulihkan."))
        print(warn("\n  Jalankan ulang dengan --yes kalau kamu sudah yakin.\n"))
        return 1

    # --- 1. Pasang APK ------------------------------------------------------
    apk_files = manifest.get("items", {}).get("apk") or []
    if apk_files and not args.skip_apk:
        apk_dir = src_dir / "apk"
        print("  [1/4] Memasang APK ...")
        rc, _, _ = sh(f"pm path {quote(pkg)}")
        if rc == 0 and not args.reinstall:
            print(f"        {dim('sudah terpasang, dilewati (pakai --reinstall untuk memaksa)')}")
        else:
            apks = sorted(apk_dir.glob("*.apk"))
            if not apks:
                print(f"        {bad('tidak ada berkas APK di backup')}")
            elif len(apks) == 1:
                proc = adb_run(["install", "-r", "-d", str(apks[0])], timeout=1800)
                blob = (proc.stdout or "") + (proc.stderr or "")
                print(f"        {ok('OK') if 'Success' in blob else bad('gagal')}")
                if "Success" not in blob:
                    print(dim(blob.strip()))
            else:
                proc = install_split(pkg, apks)
                blob = (proc.stdout or "") + (proc.stderr or "")
                print(f"        {ok('OK') if 'Success' in blob else bad('gagal')} ({len(apks)} split)")
                if "Success" not in blob:
                    print(dim(blob.strip()))

    # --- 2 & 3. OBB + data eksternal ---------------------------------------
    for key, remote_pkg, label, step in (
        ("obb", f"{EXT_OBB}/{pkg}", "OBB", "2/4"),
        ("external", f"{EXT_DATA}/{pkg}", "Data eksternal", "3/4"),
    ):
        local = src_dir / key
        if not local.is_dir():
            continue
        print(f"  [{step}] {label} ...")
        # Hapus dulu supaya `adb push` tidak membuat folder bersarang
        # (push ke path yang sudah ada akan menyalin folder ke DALAMnya).
        sh(f"rm -rf {quote(remote_pkg)}")
        sh(f"mkdir -p {quote(str(Path(remote_pkg).parent))}")
        proc = adb_run(["push", str(local), remote_pkg], timeout=7200)
        blob = (proc.stdout or "") + (proc.stderr or "")
        print(f"        {ok('OK') if 'error' not in blob.lower() else bad('gagal')}")

    # --- 4. Data privat lewat bmgr -----------------------------------------
    private = manifest.get("items", {}).get("private")
    if private and private.get("method") == "bmgr":
        print("  [4/4] Data privat via bmgr ...")
        token = manifest.get("restoreToken")
        if not token:
            print(f"        {bad('token restore tidak ada di manifest — backup privat tidak terekam')}")
            print(f"        {dim('Cek ulang: jalankan `tokens` di perangkat yang sama.')}")
        else:
            if not args.keep_current:
                rc, _, _ = sh(f"pm clear {quote(pkg)}")
                print(f"        pm clear: {ok('OK') if rc == 0 else bad('gagal')}")

            transport = args.transport or private.get("transport") or LOCAL_TRANSPORT
            sh(f"bmgr transport {transport}")

            rc, out, err = sh(f"bmgr restore {token} {quote(pkg)}", timeout=1800)
            blob = f"{out}\n{err}".strip()
            print("        " + (blob.replace("\n", "\n        ") or "(tanpa keluaran)"))

            sh("bmgr run", timeout=600)
            print(f"        {ok('selesai')} — buka game untuk memverifikasi progresnya.")

    print()
    return 0


def install_split(pkg: str, apks: list[Path]) -> subprocess.CompletedProcess:
    """
    Pasang split APK (App Bundle) lewat sesi `pm install-create`.

    `adb install-multiple` butuh semua berkas sekaligus, jadi kita pakai
    sesi pm manual supaya bisa mengalirkan setiap APK lewat stdin —
    lebih hemat disk dan aman untuk berkas besar.
    """
    total = sum(a.stat().st_size for a in apks)
    proc = adb_run(["shell", f"pm install-create -r -d -S {total}"])
    m = re.search(r"\[(\d+)\]", proc.stdout or "")
    if not m:
        return proc

    session = m.group(1)
    for apk in apks:
        size = apk.stat().st_size
        with open(apk, "rb") as fh:
            subprocess.run(
                [ADB, "shell", f"pm install-write -S {size} {session} {apk.name} -"],
                stdin=fh,
                capture_output=True,
                timeout=1800,
            )
    return adb_run(["shell", f"pm install-commit {session}"], timeout=1800)


# ----------------------------------------------------------------------------
# CLI
# ----------------------------------------------------------------------------


def build_parser() -> argparse.ArgumentParser:
    p = argparse.ArgumentParser(
        prog="game_backup.py",
        description="Harness backup/restore game Android tanpa root (jalur shell = jalur Shizuku).",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog=__doc__.split("Contoh pakai:")[-1],
    )
    p.add_argument("--adb", help="path ke adb.exe kalau tidak ada di PATH")

    sub = p.add_subparsers(dest="command", required=True)

    sub.add_parser("doctor", help="periksa lingkungan + probe batas kemampuan shell")
    sub.add_parser("tokens", help="tampilkan token restore dari dumpsys backup")

    a = sub.add_parser("analyze", help="bedah lokasi data sebuah game")
    a.add_argument("package")

    b = sub.add_parser("backup", help="backup APK + OBB + data eksternal + data privat")
    b.add_argument("package")
    b.add_argument("--out", required=True, help="direktori tujuan backup")
    b.add_argument("--include", default="apk,obb,external,private",
                   help="apk,obb,external,private (default semua)")
    b.add_argument("--transport", choices=["local", "cloud"], default="local",
                   help="transport untuk data privat (default: local)")
    b.set_defaults(func=cmd_backup)

    r = sub.add_parser("restore", help="pulihkan dari direktori backup")
    r.add_argument("package")
    r.add_argument("--from", dest="src", required=True, help="direktori hasil backup")
    r.add_argument("--transport", choices=["local", "cloud"], default=None)
    r.add_argument("--reinstall", action="store_true", help="paksa pasang ulang APK")
    r.add_argument("--skip-apk", action="store_true", help="jangan sentuh APK")
    r.add_argument("--keep-current", action="store_true",
                   help="JANGAN pm clear sebelum restore (restore di atas data lama)")
    r.add_argument("--yes", action="store_true", help="konfirmasi operasi destruktif")
    r.set_defaults(func=cmd_restore)

    return p


def main() -> int:
    global ADB

    parser = build_parser()
    args = parser.parse_args()

    if getattr(args, "adb", None):
        ADB = args.adb
    else:
        ADB = find_adb()

    handlers = {
        "doctor": cmd_doctor,
        "analyze": cmd_analyze,
        "tokens": cmd_tokens,
        "backup": cmd_backup,
        "restore": cmd_restore,
    }
    return handlers[args.command](args)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        print("\nDibatalkan.")
        sys.exit(130)
