# FORK.md — DataBackup, fork fokus game

Fork ini diturunkan dari [XayahSuSuSu/Android-DataBackup](https://github.com/XayahSuSuSu/Android-DataBackup)
v2.0.12 pada branch `fork-cleanup`.

## Tujuan

Membuat aplikasi yang **hanya** untuk backup dan restore **game**, berjalan
**tanpa root**, dengan lokasi backup di **kartu SD**.

Alasan di balik keputusan ini ada di `tools/game-backup/README.md` — ringkasnya:
data privat game (`/data/data/<pkg>`) tidak bisa dibaca `uid 2000` (shell) lewat
akses berkas, tetapi bisa ditangkap lewat `bmgr` (BackupManager) yang berjalan
sebagai `uid system`. Jalur itu juga menyelesaikan masalah `chown`/`chcon` saat
restore, karena sistem yang menulis kembali berkasnya.

---

## Perubahan yang sudah dilakukan

### 1. Fitur cloud dihapus

| Item | Tindakan |
|---|---|
| `feature/main/cloud/` (13 berkas) | Dihapus |
| Rute `Cloud`, `CloudAddAccount`, `FTPSetup`, `SFTPSetup`, `WebDAVSetup`, `SMBSetup` | Dihapus dari `core/ui/route/Routes.kt` |
| Composable cloud di `MainActivity` | Dihapus |
| Kartu "Cloud" di dashboard | Dihapus |
| Pemilih penyimpanan (Local/Cloud) di halaman restore | Dihapus, hanya lokal |
| Pemilih penyimpanan di setup backup | Dihapus, hanya lokal |
| `include(":feature:main:cloud")` + dependency di `app` | Dihapus |

### 2. Backup berkas/media dihapus

| Item | Tindakan |
|---|---|
| `feature/main/processing/.../medium/` | Dihapus |
| Rute `MediumBackupProcessing*`, `MediumRestoreProcessing*` | Dihapus |
| Composable media di `MainActivity` | Dihapus |
| Kartu "Backup Files" di dashboard | Dihapus |
| Tombol "Files" di halaman restore | Dihapus |
| Cabang `Target.Files` di `ListViewModel.toNextPage()` | Jadi no-op |

### 3. Hanya game yang ditampilkan

`core/data/.../AppsRepo.kt` — penyaringan dilakukan **di sumber data**, bukan di
UI, sehingga aplikasi non-game tidak pernah masuk database:

```kotlin
private fun PackageInfo.isGameApp(): Boolean {
    val appInfo = applicationInfo ?: return false
    if ((appInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0) return true
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        appInfo.category == ApplicationInfo.CATEGORY_GAME
    } else false
}
```

Dua penanda diperiksa karena keduanya dipakai di lapangan: `android:isGame="true"`
(menghasilkan `FLAG_IS_GAME`) dan `android:appCategory="game"` (menghasilkan
`category`).

Efek samping yang diinginkan: entri aplikasi non-game yang tersisa di database
dari versi sebelumnya akan otomatis dianggap "sudah tidak terpasang" dan dibuang.

### 4. Kartu SD jadi lokasi backup default

`core/data/.../DirectoryRepository.kt` — `resetDir()` sekarang memilih
penyimpanan eksternal kalau ada:

```kotlin
private suspend fun resetDir() {
    val external = directoryDao.queryActiveDirectories()
        .firstOrNull { it.storageType == StorageType.EXTERNAL && it.enabled }
    if (external != null) selectDir(path = external.path, id = external.id)
    else selectDir(path = ConstantUtil.DEFAULT_PATH, id = directoryDao.queryDefaultDirectoryId(StorageType.INTERNAL))
}
```

Kalau tidak ada kartu SD, kembali ke `/storage/emulated/0/DataBackup` seperti
semula. Pengguna tetap bisa menggantinya lewat pemilih folder.

### 5. Pembungkus `bmgr` — jalur data privat tanpa root

`core/util/.../command/BmgrUtil.kt` (baru) — membungkus seluruh perintah
BackupManager yang dibutuhkan:

| Fungsi | Perintah |
|---|---|
| `isEnabled()` / `setEnabled()` | `bmgr enabled` / `bmgr enable true` |
| `listTransports()` / `currentTransport()` / `selectTransport()` | `bmgr list transports` / `bmgr transport` |
| `backupNow(pkg)` | `bmgr backupnow <pkg>` |
| `restore(token, pkg)` | `bmgr restore <token> <pkg>` |
| `run()` | `bmgr run` |
| `wipe(transport, pkg)` | `bmgr wipe <transport> <pkg>` |
| `restoreToken()` | `dumpsys backup` → `Ancestral` + `Current` |

Konstanta transport yang disediakan: `TRANSPORT_LOCAL`, `TRANSPORT_CLOUD`,
`TRANSPORT_D2D`.

Unit test untuk pengurai token ada di
`core/util/src/test/kotlin/com/xayah/core/util/command/BmgrUtilTest.kt`.

### 6. Dukungan Shizuku — berjalan sebagai uid 2000 tanpa root

`core/util/.../command/ShizukuShell.kt` (baru) — backend eksekusi perintah
lewat Shizuku:

| Fungsi | Kegunaan |
|---|---|
| `isAvailable()` | Shizuku terpasang dan servernya hidup |
| `hasPermission()` | Binder diterima dan izin diberikan |
| `requestPermission()` | Meminta izin ke pengguna |
| `serverUid()` / `isShellMode()` | `2000` = shell, `0` = perangkat di-root |
| `exec(cmd, timeout)` | Menjalankan perintah sebagai shell |
| `stageBinaries(context)` | Menyinggahkan binary bawaan aplikasi |

Dependensi: `dev.rikka.shizuku:api:13.1.5` dan `:provider:13.1.5`.
`ShizukuProvider` didaftarkan di `app/src/main/AndroidManifest.xml`.

#### Kenapa binary perlu disinggahkan

Aplikasi membawa `busybox`, `tar`, dan `zstd` di `filesDir` privatnya.
Direktori privat aplikasi ber-mode `0700` milik uid aplikasi, sehingga
**shell tidak bisa menembusnya** — binary itu jadi tidak terjangkau, meskipun
berkasnya sendiri sudah world-executable.

Alurnya dua langkah:

1. Aplikasi mengekstrak `bin.zip` ke direktori eksternalnya sendiri
   (`/storage/emulated/0/Android/data/<pkg>/files/shell-bin`) — shell bisa
   membacanya lewat grup `ext_data_rw`
2. Shell menyalinnya ke `/data/local/tmp/databackup-bin` dan memberi bit
   eksekusi; direktori itu jadi bagian terdepan `PATH`

#### Cara mengaktifkan

`BaseUtil.initializeShizukuMode(context)` dipanggil setelah izin diberikan.
Sejak itu `BaseUtil.execute()` otomatis memakai `ShizukuShell`, bukan libsu.
Kalau root tersedia, jalur libsu tetap dipakai karena lebih lengkap.

Di halaman setup, tombol "Root or Shizuku" sekarang mencoba root lebih dulu
lalu jatuh ke Shizuku. `IndexViewModel` mendaftarkan listener hasil izin
sehingga pengguna cukup menekan sekali.

### 7. Path Android/data dan obb menyesuaikan mode

`core/util/.../PathUtil.kt` — akar direktori eksternal sekarang bergantung
pada mode eksekusi:

| Mode | Akar | Alasan |
|---|---|---|
| root | `/data/media/<id>/Android` | lebih langsung, tanpa lapisan FUSE |
| shell | `/storage/emulated/<id>/Android` | `/data/media` ber-mode `0770` milik `media_rw`, shell tidak ada di grup itu |

Shell hanya punya akses ke `Android/data` dan `Android/obb` lewat grup
`ext_data_rw` / `ext_obb_rw` pada jalur FUSE. Tanpa penyesuaian ini, backup
data eksternal game akan gagal walau Shizuku sudah aktif.

### 8. Daftar game dan info penyimpanan tanpa root

Sebelumnya seluruh query paket dan pembacaan penyimpanan lewat
`RemoteRootService` (libsu `RootService`). Tanpa root itu berarti daftar game
kosong dan tombol backup nonaktif.

Polanya: helper kecil di repository yang bercabang pada
`BaseUtil.isShizukuMode()`. Prinsipnya, **`rootService` tidak boleh disentuh
sama sekali saat mode Shizuku aktif** — kalau disentuh, libsu akan mencoba
membangun proses root berulang kali.

| Repository | Helper | Padanan tanpa root |
|---|---|---|
| `AppsRepo` | `users()` | hanya pengguna aktif |
| `AppsRepo` | `installedPackages()` | `PackageManager.getInstalledPackages` |
| `AppsRepo` | `packageInfo()` | `PackageManager.getPackageInfo` |
| `AppsRepo` | `userHandleOf()` | `Process.myUserHandle()` |
| `AppsRepo` | `permissionsOf()` | `requestedPermissions` + `requestedPermissionsFlags` |
| `AppsRepo` | `storageStats()` | kosong (butuh `PACKAGE_USAGE_STATS`) |
| `DirectoryRepository` | `listDirPaths()` | `File.listFiles()` |
| `DirectoryRepository` | `statFsOf()` | `android.os.StatFs` |
| `DirectoryRepository` | `sizeOf()` | `du -sk` lewat shell |
| `DirectoryRepository` | `externalAccessPath()` | `/mnt/media_rw/<uuid>` → `/storage/<uuid>` |
| `PackageUtil` | `hasKeystore()` | false (butuh root) |

`StatFs` dan `PackageManager` bekerja untuk aplikasi biasa, jadi keduanya tidak
butuh hak istimewa sama sekali.

### 9. Operasi berkas lewat shell

`core/rootservice/.../util/ShellFileOps.kt` (baru) — padanan seluruh operasi
berkas `RemoteRootService` memakai perintah shell biasa:

| Operasi | Perintah |
|---|---|
| `mkdirs` | `mkdir -p` |
| `exists` | `test -e` |
| `createNewFile` | `touch` |
| `deleteRecursively` | `rm -rf` |
| `renameTo` | `mv` |
| `copyTo` / `copyRecursively` | `cp` / `cp -R` (`-n` bila tidak menimpa) |
| `listFilePaths` | `ls -1 -p` (satu pemanggilan, garis miring menandai direktori) |
| `readText` / `readBytes` | `cat` / `base64` |
| `writeText` / `writeBytes` | tulis ke berkas sementara, lalu `cp` |
| `calculateSize` | `du -sk` |
| `calculateMD5` | `md5sum` |
| `walkFileTree` | `find -type f` |
| `clearEmptyDirectoriesRecursively` | `find -mindepth 1 -type d -empty -delete` |
| `setAllPermissions` | `chmod -R 777` |

Operasi paket yang bisa dijalankan shell juga dialihkan: `pm path`,
`pm grant`/`pm revoke`, `am force-stop`, `appops set`, `pm enable`/`disable`,
dan `settings get`/`put`.

Dua hal yang perlu diperhatikan:

- **`writeBytes` tidak bisa menulis lewat `stdin`.** Aplikasi menulis dulu ke
  direktori sementaranya sendiri, lalu shell menyalinnya. Direktori sementaranya
  harus yang **eksternal** (`externalCacheDir`), karena shell tidak bisa membaca
  `cacheDir` privat aplikasi.
- **`readBytes` memakai base64** supaya byte tidak rusak saat melewati shell.

`RemoteRootService` sekarang punya `shellMode()`, dan **seluruh 42 metodenya**
memiliki cabang shell. Invariannya: saat mode Shizuku aktif, libsu sama sekali
tidak disentuh — kalau disentuh, ia akan mencoba membangun proses root dan gagal
berulang kali. Terverifikasi: **0 pemanggilan `getService()` tanpa penjaga.**

Metode yang memang butuh uid 0 mengembalikan nilai kosong yang aman:
SSAID, `StorageStats`, pembacaan appops, dan permission flags.

### 10. Direktori kerja aplikasi ikut menyesuaikan mode

Pola yang muncul berulang: **apa pun di dalam `filesDir` privat aplikasi tidak
bisa dibaca maupun ditulis shell**, karena direktori itu ber-mode `0700` milik
uid aplikasi. Ini sempat membuat kompresi ikon gagal (sumber tidak terbaca) dan
restore ikon gagal (tujuan tidak bisa ditulis).

`PathUtil.appWorkDir()` menyelesaikannya:

| Mode | Direktori kerja |
|---|---|
| root | `filesDir` privat |
| shell | `getExternalFilesDir(null)` — terjangkau lewat grup `ext_data_rw` |

Yang memakainya: `iconDir()`, `tmpApksDir()`, tujuan dekompresi ikon di
`AppsRepo`, dan sumber kompresi ikon di `PackagesBackupUtil`.

`PathUtil.setFilesDirSELinux()` tidak melakukan apa pun pada mode Shizuku,
karena `chown` dan `chcon` butuh uid 0.

Di `PackagesRestoreUtil`, blok `chown`/`chcon` dibungkus penjaga mode Shizuku
dan **tidak dihitung sebagai kegagalan**. Berkas di penyimpanan eksternal
dimiliki lewat pemetaan FUSE dan konteksnya ditetapkan media provider, jadi
aplikasi tetap bisa membacanya. Tanpa ini, restore data eksternal akan
dilaporkan gagal padahal berhasil.

### 11. Opsi data privat dinonaktifkan pada mode Shizuku

Data privat (`user` dan `user_de`) berada di `/data/user/<id>` dan
`/data/user_de/<id>` yang ber-mode `0700` milik uid aplikasi. Sebelumnya opsi
itu tetap bisa dicentang, sehingga backup akan mencoba dan gagal tanpa pengguna
tahu sebabnya.

Dua lapis penjaga:

1. **`AppsRepo.selectDataItems`** memaksa `user` dan `user_de` menjadi
   `NotSelected` saat mode Shizuku, apa pun yang dikirim UI. Ini funnel-nya,
   jadi tidak ada jalur lain yang bisa lolos.
2. **`DataChips`** menonaktifkan chip `PACKAGE_USER` dan `PACKAGE_USER_DE`
   lewat parameter `enabled` yang sudah ada di `PackageDataChip`, dan tidak
   memanggil `onItemClick` untuk keduanya.

Jalur penggantinya adalah `Bmgr`, yang belum tersambung.

Yang diperiksa dan sudah aman tanpa perubahan: **SSAID** mengembalikan string
kosong saat backup pada mode Shizuku sehingga restore melewatinya, dan
**restore izin** memakai `pm grant`/`pm revoke` yang bisa dijalankan shell.

### 12. Data privat lewat BackupManager (`bmgr`)

Ini melengkapi bagian yang tidak bisa dijangkau shell sama sekali.

Data privat **tidak dibaca sendiri** oleh shell — memang tidak bisa. Kita
meminta `BackupManagerService`, yang berjalan sebagai uid system, membacanya
dan menyerahkannya ke sebuah *transport*. Saat restore, sistem pula yang
menulis kembali berkasnya, sehingga kepemilikan uid/gid dan konteks SELinux
benar **tanpa perlu `chown`** — masalah yang tadinya mustahil diselesaikan.

| Bagian | Perubahan |
|---|---|
| Model | `PackageExtraInfo.bmgrToken` (default kosong) |
| Database | `AppDatabase` versi 8 + `AutoMigration(7, 8)` |
| Perintah | `Pm.clear()` baru |
| Backup | `PackagesBackupUtil.backupPrivateBmgr()` |
| Restore | `PackagesRestoreUtil.restorePrivateBmgr()` |
| Orkestrasi | Dipanggil di `AbstractBackupService` dan `AbstractRestoreService` |

Migrasi database **tidak perlu ditulis manual**: Room memakai `autoMigrations`
dan kolomnya punya nilai default, jadi Room menyusun sendiri. Skema `8.json`
tergenerasi dengan `bmgrToken TEXT NOT NULL DEFAULT ''`.

**Urutan restore itu penting.** `restorePrivateBmgr` dijalankan **setelah APK
terpasang tetapi sebelum data eksternal**, karena di dalamnya ada `pm clear`
yang mengosongkan data aplikasi lebih dulu:

```
restore APK  ->  pm clear + bmgr restore + bmgr run  ->  restore Android/data, obb, media  ->  permissions, ssaid
```

#### Batasan yang harus diketahui

**Transport `local` menyimpan citranya di `getFilesDir()` aplikasi
`com.android.localtransport`** — yaitu direktori privat uid system, ber-mode
`0700`. Shell tidak bisa membacanya, dan aplikasi biasa juga tidak.

Artinya: **citra bmgr tidak ikut tersalin ke kartu SD, dan tidak selamat dari
factory reset.** Yang tersalin ke kartu SD hanyalah APK, OBB, dan `Android/data`.

Jadi bagian ini berguna untuk **pemulihan di perangkat yang sama** — rollback
setelah update bermasalah, atau menyelamatkan progres setelah salah hapus data.
Bukan untuk pindah ke HP baru.

Untuk membuatnya portabel, diperlukan *transport* sendiri yang menerima aliran
data dari BackupManager dan menuliskannya ke kartu SD. Itu proyek tersendiri,
dan belum dikerjakan.

#### Perilaku saat tidak bisa dijalankan

Game dengan `allowBackup="false"` tidak menghasilkan citra. Itu dicatat sebagai
peringatan, **bukan kegagalan**, karena APK, OBB, dan data eksternal tetap
terbackup utuh. Token dikosongkan sehingga restore melewati langkah ini.

### 13. Keluaran shell yang diurai: stderr ditutup, pengurai memindai

`ShizukuShell.exec` menggabungkan stdout dan stderr menjadi satu daftar baris,
sehingga pesan galat bisa terbaca sebagai data. Dampak nyatanya: baris
`ls: /nope: Permission denied` berubah menjadi jalur palsu
`/base/ls: /nope: Permission denied`, dan galat `find` menjadi berkas palsu
yang ikut diproses.

Perbaikannya dua lapis:

1. **Perintah yang keluarannya diurai menutup stderr** dengan `2>/dev/null`
   (`ls`, `du`, `md5sum`, `base64`, `find`, `cat`). Ini lapis utama, karena
   nama berkas boleh mengandung titik dua dan spasi — pengurai tidak boleh
   menebak dari isi baris.
2. **`ShellOutputParser`** memindai baris yang benar-benar cocok, bukan
   mengambil baris pertama: `parseDuSize` mencari baris berawalan angka,
   `parseMd5Sum` mencari 32 digit heksadesimal, `parseLsEntries` membedakan
   berkas dan direktori dari garis miring `ls -p` sekaligus membuang `.`
   dan `..`.

`DirectoryRepository.sizeOf()` tidak lagi mengurai `du` sendiri — ia memanggil
`RemoteRootService.calculateSize()`, sehingga kedua lapis itu berlaku di sana
juga dan tidak ada pengurai duplikat. Sebelumnya `du: Permission denied` di
baris pertama membuat ukuran terbaca 0 tanpa penjelasan.

Unit test: `core/rootservice/src/test/.../ShellOutputParserTest.kt` (17 kasus).

### 14. ADB mandiri - tanpa Shizuku

Shizuku bagus, tetapi ia aplikasi pihak ketiga yang harus dipasang dan
dinyalakan ulang setiap reboot. Untuk menghindarinya, fork ini membawa **klien
ADB sendiri**: aplikasi berbicara protokol ADB langsung ke `adbd` di perangkat
yang sama, persis seperti aplikasi LADB.

Tidak ada izin ADB yang bisa diberikan lewat `pm grant` untuk menjalankan
perintah shell — uid 2000 hanya bisa dipinjam lewat Shizuku, root, atau
koneksi ADB. Jalur ketiga inilah yang dipakai di sini.

Ada dua alur sesuai versi Android:

- **Android 11+ (Wireless debugging):** pairing sekali dengan kode 6 digit;
  port ditemukan lewat mDNS. Setelah itu tidak perlu PC lagi.
- **Android 10 (ADB over USB):** 1) sambungkan USB & aktifkan USB debugging,
  2) di PC jalankan `adb tcpip 5555`, 3) tekan OK di app lalu pilih
  **Izinkan** pada dialog debugging di HP. Kunci app tersimpan di `adb_keys`,
  jadi setelah reboot cukup mengulang `adb tcpip 5555` (2 detik) — dialog izin
  tidak muncul lagi.

Alur Wireless debugging (Android 11+):

1. Setup → tombol **ADB (tanpa Shizuku)** → panduan menyalakan Wireless
   debugging dan membuka layar *Pair device with pairing code*.
2. Port pairing ditemukan lewat mDNS (`_adb-tls-pairing._tcp`) dan diisi
   otomatis; pengguna mengetik kode 6 digit.
3. Setelah pairing, kunci RSA disimpan di `filesDir/adb-key` sehingga
   penyambungan berikutnya tidak perlu kode lagi — termasuk setelah reboot,
   selama Wireless debugging masih menyala.

Yang ditambahkan:

| Bagian | Peran |
|---|---|
| `core/util/.../command/AdbShell.kt` | `pair`, `connect`, `discoverPairingPort`, `exec`, `stageBinaries`, plus `LocalAdbManager` (kunci + sertifikat) |
| `ShellBinStaging.kt` | penyinggahan `busybox`/`tar`/`zstd` yang dipakai bersama Shizuku dan ADB |
| `ShellModeInitializer.kt` | menyambung ulang backend saat aplikasi dibuka |
| `BaseUtil` | `ShellBackend` (SHIZUKU/ADB), `expectShellMode()`, `isShellMode()` |
| `MainActivity` | menandai mode shell lalu menyambung di latar belakang |
| Setup page one | tombol dan dialog pairing |

Detail teknis:

- **Exit code** tidak dibawa stream ADB, jadi tiap perintah ditutup
  `echo <penanda>$?` di dalam subkulit; `AdbExecOutput` mengambil kodenya dan
  membuang penandanya. Ada unit test (7 kasus) untuk pengurai ini.
- **Pembacaan keluaran berhenti saat penanda terlihat, bukan menunggu EOF.**
  libadb 3.1.1 dapat menahan `read()` setelah peer menutup stream ketika data
  terakhir sudah terkirim (`mPendingClose` tidak dicek di loop tunggu), sehingga
  `readText()` menggantung sampai watchdog. Terbukti di perangkat Android 10:
  perintah berjalan (binary tersalin) tetapi hasilnya dianggap timeout.
  `readUntilMarker()` membaca per potongan dan berhenti begitu penanda muncul.
- **Timeout** perintah shell dinaikkan dari 30 detik menjadi 600 detik
  (`ShizukuShell` dan `AdbShell`). Kompresi `tar` untuk game besar bisa
  berjalan beberapa menit; nilai <= 0 berarti tanpa batas waktu.
- **`isShizukuMode()` diganti `isShellMode()`** di 27 titik. Semua percabangan
  itu sebenarnya berarti "berjalan sebagai uid 2000", bukan "memakai Shizuku",
  jadi sekarang berlaku juga untuk ADB mandiri.
- **`Tar.compressInCur` diperbaiki.** Sebelumnya ia menjalankan `cd` sebagai
  perintah terpisah, yang hanya bekerja pada sesi libsu persisten — pada mode
  shell (perintah = proses terpisah) `cd` tidak berpengaruh sehingga backup APK
  gagal. Sekarang `cd ... && tar ...` dijalankan sebagai satu perintah.
- **`expectShellMode()`** di startup mencegah perintah jatuh ke libsu sebelum
  backend siap. Tanpa ini, di perangkat tanpa root perintah dijalankan sebagai
  uid aplikasi dan gagal tanpa sebab yang jelas.
- Dependensi: `com.github.MuntashirAkon:libadb-android:3.1.1` (GPL-3/Apache
  dual) + `bcpkix-jdk18on`; `bcprov-jdk15to18` bawaan libadb dibuang supaya
  tidak bentrok dengan `bcprov-jdk18on` milik aplikasi.

Batasan yang perlu diuji di perangkat:

- Wireless debugging MIUI kadang mematikan diri atau mengganti port; auto
  connect lewat mDNS sudah disiapkan, tetapi pairing ulang mungkin perlu kalau
  kunci dihapus.
- Android 10: `adb tcpip 5555` harus diulang setiap reboot (adbd kembali ke
  mode USB), jadi jalur ini tetap butuh koneksi PC singkat per boot.
- **Sudah diuji di perangkat**: POCO X3 NFC, MIUI 12 / Android 10. Alur
  `adb tcpip 5555` + izinkan kunci → tombol ADB hijau, binary tersalin ke
  `/data/local/tmp/databackup-bin`, Validasi ABI hijau, Continue aktif.
  Alur Wireless debugging (Android 11+) belum diuji perangkat.

### 15. Hasil uji perangkat Android 10

Uji end-to-end dilakukan di POCO X3 NFC (MIUI 12 / Android 10) memakai
**dummy game** yang dibuat khusus: paket `com.databackup.testgame` dengan
`isGame`/`appCategory=game`, `allowBackup=true`, dan data lengkap — akun login
di `shared_prefs`, save + config di `files/`, database SQLite, aset unduhan di
`Android/data`, dua berkas OBB, dan satu berkas media. Semua jenis data
diverifikasi pulih identik setelah wipe (akun `player_5415`, 3 item DB, OBB
4 MB + 1 MB, video 512 KB).

Sepuluh bug nyata ditemukan dan diperbaiki selama uji ini:

| # | Gejala | Penyebab | Perbaikan |
|---|---|---|---|
| 1 | `zstd: inaccessible or not found`, `tar: Unknown option totals` | `AdbShell` tidak menyetel `PATH`; `tar`/`zstd` jatuh ke versi sistem | `ShellEnv.wrap()` dipakai bersama Shizuku & ADB |
| 2 | Perintah berhasil tapi dianggap timeout | libadb 3.1.1 menahan `read()` setelah peer menutup stream | `readUntilMarker()` berhenti saat penanda exit code terlihat |
| 3 | Daftar game kosong walau DB berisi | `UsersRepo.getUsers(BACKUP)` mengembalikan daftar kosong di mode shell, filter user menyingkirkan semua | Bangun pengguna aktif dari uid aplikasi |
| 4 | Restore APK gagal, `avc: denied ... sdcardfs` | system_server tidak bisa membaca APK dari `/sdcard` | `Pm.prepareSource()` menyalin APK ke `/data/local/tmp` |
| 5 | `INSTALL_FAILED_VERSION_DOWNGRADE` | APK backup lebih tua dari yang terpasang | Tambah flag `-d` pada `pm install` |
| 6 | `Failed to get uid of <pkg>` | `getPackageUid` mengembalikan `-1` di mode shell | Baca uid lewat `PackageManager` aplikasi |
| 7 | `user.tar.zst` tidak ada dianggap kegagalan restore | Data privat di mode shell ditangani `bmgr`, bukan tar | USER/USER_DE di-skip (bukan error) di backup **dan** restore |
| 8 | `bmgr` menolak: "Backup is not allowed" | `am force-stop` membuat paket berstatus *stopped* | Ambil data privat lewat `bmgr` **sebelum** app di-kill |
| 9 | Backup privat dianggap sukses padahal gagal | `with result: Success` cocok dengan pseudo-paket `@pm@` | Deteksi per paket: `Package <pkg> with result: Success` |
| 10 | BackupManager nonaktif di MIUI | `bmgr backupnow` gagal tanpa penjelasan | `Bmgr.setEnabled(true)` otomatis sebelum backup/restore |

Catatan operasional:

- **Instalasi APK di MIUI**: `adb install` sering ditolak
  `INSTALL_FAILED_USER_RESTRICTED`; tembus dengan
  `adb shell pm install -i com.android.vending <apk>` dari `/data/local/tmp`.
- **`adb tcpip 5555` harus diulang setiap reboot** (adbd kembali ke mode USB);
  kunci app tetap tersimpan sehingga tidak ada dialog izin lagi.
- Citra `bmgr` transport `local` tetap hanya bisa dipulihkan di perangkat yang
  sama (lihat bagian 12).
- Bug #8 dan #9 sempat membuat data privat tampak "berhasil di-backup" padahal
  kosong; keduanya wajib ada agar jalur privat bisa diandalkan.

### 16. Paritas non-root: `run-as`, AppOps, dan ukuran paket

Tiga celah terakhir antara mode shell dan mode root ditutup di sini. Tujuannya
satu: perangkat tanpa root tidak perlu kehilangan fitur selain yang memang
mustahil (lihat tabel di bawah).

**1. Data privat paket debuggable lewat `run-as` (baru).**

Selama ini data privat hanya bisa ditangkap `bmgr`, dan citranya terikat
perangkat — tidak ikut ke kartu SD dan tidak selamat dari factory reset.
Sekarang paket dengan `android:debuggable="true"` dibaca langsung:

- `run-as <pkg> /system/bin/tar -cpf - -C /data/data/<pkg> . | zstd > user.tar.zst`
- hasilnya arsip biasa `user.tar.zst`/`user_de.tar.zst` yang **portabel**;
- restore mengekstrak sebagai uid aplikasi, jadi kepemilikan berkas benar
  tanpa `chown`/`chcon`;
- tetap bekerja untuk `allowBackup="false"` — justru kasus game sideload.

Detail yang wajib diingat: proses `run-as` berjalan dalam domain aplikasi,
jadi **harus** memakai `/system/bin/tar`, `/system/bin/du`, dan
`/system/bin/sh`; binary singgahan aplikasi tidak bisa dieksekusi. Pipe-nya
dibungkus `set -o pipefail` supaya kegagalan `tar` tidak tertutup suksesnya
`zstd` (tanpa itu arsip kosong dianggap sukses). Opsi `--exclude` diprobe
sekali karena tidak semua ROM punya.

Agar tidak dobel, `backupPrivateBmgr` melewati `bmgr` kalau `run-as` tersedia,
dan `restorePrivateBmgr` melewati citra kalau arsipnya ada. Arsip mode root
(berawalan `<pkg>/`) dibedakan dari arsip `run-as` (isi direktori apa adanya)
lewat entri pertama tar; kalau tertukar, restore menolak dengan pesan jelas
alih-alih menaruh berkas di tempat yang salah.

**2. Izin runtime + AppOps dipulihkan tanpa root (baru).**

Mode root membaca op lewat `AppOpsManager.getOpsForPackage` (butuh
`GET_APP_OPS_STATS`). Shell sama-sama punya hak itu lewat `cmd appops get`,
dan sekarang hasilnya ikut tersimpan: `permissionsOf()` memetakan nama izin
ke nama op (`AppOpsManager.permissionToOp`) lalu mencocokkannya dengan
keluaran `cmd appops get` (format Android 9-10 `OP: allow`, Android 11+
`OP: mode=allow`). Restore memakai `pm grant/revoke` + `appops reset/set`
yang sudah tersedia. Kalau API tersembunyinya diblokir di ROM baru, kolom op
dilewati dan restore hanya mengembalikan grant/revoke — tidak gagal.

**3. Ukuran paket tanpa root (baru).**

`StorageStatsManager` hanya butuh appop `GET_USAGE_STATS`, dan shell boleh
memberikannya: begitu backend shell siap, aplikasi menjalankan
`appops set <pkg> GET_USAGE_STATS allow` untuk dirinya sendiri. Setelah itu
`queryStatsForPackage` berjalan di aplikasi dan kolom ukuran terisi seperti
di mode root. Dua catatan: izin `PACKAGE_USAGE_STATS` dideklarasikan di
manifest (appop ini tidak berbahaya, hanya membaca statistik), dan kegagalan
memberikannya tidak fatal — hanya kolom ukuran yang kosong.

**4. Status aktif/nonaktif paket (perbaikan wajib).**

`getApplicationEnabledSetting` dulu mengembalikan `null` di mode shell,
sehingga pemanggil yang membandingkan dengan `COMPONENT_ENABLED_STATE_ENABLED`
menandai **semua** game sebagai nonaktif. Sekarang dibaca lewat
`PackageManager` aplikasi, dan state `DEFAULT` diterjemahkan ke enabled/disabled
efektif.

**5. Perbaikan kecil pada pemeriksaan "data tidak berubah".**

Pemeriksaan skip untuk APK/data/OBB/media dulu hanya melihat "arsipnya ada",
sehingga arsip yang terhapus atau berukuran 0 byte masih bisa dianggap "tidak
berubah" dan tidak dibuat ulang. Sekarang arsip harus ada **dan** berukuran
lebih dari nol sebelum skip dipakai — inilah sebabnya backup ulang setelah
kartu SD dibersihkan kembali menghasilkan arsip lengkap.

Ringkasan paritas:

| Kemampuan | Root | Shell (Shizuku/ADB) |
|---|---|---|
| APK, data eksternal, OBB, media | Berkas langsung | Berkas lewat shell |
| Data privat paket debuggable | Berkas langsung | `run-as` — portabel |
| Data privat paket lain | Berkas langsung | `bmgr` — terikat perangkat |
| Izin runtime + AppOps | API sistem | `pm` + `cmd appops` |
| Ukuran paket | StorageStats daemon | StorageStats + appop sendiri |
| Status aktif/nonaktif | PMS langsung | PackageManager |
| `ssaid` | Ditulis lewat PMS | Tidak tersedia (root-only) |
| Keystore per-uid | Dicek | Tidak tersedia (root-only) |
| `chown`/`chcon` | Dipakai | Tidak perlu: `bmgr`/`run-as` yang menulis |

**Hasil uji perangkat (POCO X3 NFC, MIUI 12 / Android 10).** Semua jalur di
atas dijalankan sungguhan lewat UI, memakai dummy game yang bisa diperiksa
sampai isi berkasnya:

- `run-as` backup: log mencatat `run-as "com.databackup.testgame"
  /system/bin/tar --exclude=... | zstd ... > user.tar.zst`; isi `user.tar.zst`
  persis `shared_prefs/account.xml`, `databases/game.db`, `files/*` — cache dan
  `no_backup` tidak ikut. `bmgrToken` di konfigurasi backup berisi `""`.
- `run-as` restore: setelah `pm clear` + hapus data eksternal, restore
  menghasilkan akun yang sama (`player_4198` + token), `game.db`, aset
  eksternal, OBB 4 MB + 1 MB, dan video 512 KB; hasil tugas
  "1 apps restored, 6/6". Ekstraksi berjalan lewat
  `zstd -d -c user.tar.zst | run-as ... tar -xpf - -C /data/data/<pkg>`.
- AppOps: dengan `appops set com.databackup.testgame CAMERA ignore`, baris
  paket di database berisi `CAMERA isGranted=false op=26 mode=1` — mode dan
  kode op terbaca benar (izin storage tidak muncul karena op-nya sudah usang
  di Android 10, dan itu memang dibiarkan kosong).
- Ukuran paket: `storageStats` terisi (app 5,3 MB / data 2,7 MB) setelah appop
  `GET_USAGE_STATS` diberikan otomatis oleh shell; sebelumnya selalu 0.
- Dua catatan lapangan: toybox MIUI 12 **mendukung** `--exclude` walau tidak
  tercantum di `tar --help` (karena itu probnya fungsional, bukan membaca
  teks bantuan), dan `cmd appops get` di MIUI menulis `OP: mode` tanpa
  indentasi plus entri `MIUIOP(...)` yang harus diabaikan pengurai.

---

### 17. Arsip privat kosong tidak menimpa arsip yang berisi

Temuan lapangan: pernah menekan backup setelah data aplikasi dikosongkan,
sehingga arsip privat lama yang berisi tertimpa arsip baru yang isinya hanya
`./`. Arsip lama yang berisi data lebih berharga daripada arsip kosong.

`RunAs.entryCount()` menghitung entri arsip lewat
`zstd -d -c <arsip> | tar -tf - | wc -l` (atau `cat` bila tidak terkompresi).

`PackagesBackupUtil.backupData` untuk `PACKAGE_USER` sekarang:

1. Mengompresi ke berkas sementara `<dst>.part` — arsip lama belum tersentuh
2. Menguji arsip sementara; kalau gagal, keduanya dibuang dan arsip lama utuh
3. Kalau arsip baru hanya berisi <= 1 entri sedangkan arsip lama > 1 entri,
   arsip baru dibuang, arsip lama dipertahankan, dan langkah ditandai **SKIP**
   (bukan ERROR)
4. Kalau lolos, arsip sementara di-rename menimpa yang lama

**Diverifikasi di perangkat** (POCO X3 NFC, Android 10):

| Langkah | Hasil |
|---|---|
| Arsip bagus dibuat (data uji via `run-as`) | 263 byte, 7 entri |
| `pm clear` lalu backup lagi | arsip **tetap 263 byte**, isi utuh |
| Log | `Data privat kosong (...); arsip lama dipertahankan.` |
| Hasil UI | "Backup completed, 6/6" — SKIP bukan ERROR |
| Berkas `.part` | tidak ada yang tertinggal |

Sebelum perbaikan, skenario yang sama menghasilkan arsip 92 byte berisi hanya
`./` — bug-nya sempat tereproduksi lebih dulu dengan APK lama.

---

### 18. Jalur Shizuku diverifikasi perangkat

Ini verifikasi perangkat **pertama** untuk jalur Shizuku — sebelumnya seluruh
lapisan ini hanya lolos kompilasi.

**Cara memulai Shizuku di Android 10 tanpa root:** buka aplikasi Shizuku →
"Start via Wireless debugging" → **Start**. Starter-nya memakai ADB pada port
5555, jadi `adb tcpip 5555` harus sudah aktif lebih dulu. Kalau gagal, tekan
Start sekali lagi — percobaan pertama di sesi ini mati setelah server sempat
start, percobaan kedua bertahan.

**Hasil uji:**

| Uji | Hasil |
|---|---|
| Aplikasi memakai Shizuku | Terlihat dari tidak adanya koneksi TCP ke port 5555 |
| Backup penuh | "1 apps backed up, 6/6" |
| Isi arsip privat | `savegame.dat` berisi `player_shizuku`, persis data uji |
| Restore | "1 apps restored, 6/6" |
| Data setelah restore | Pulih identik |
| Log | Bersih, tanpa error |

**Uji andal — apa yang terjadi kalau Shizuku mati:**

1. `shizuku_server` dimatikan paksa
2. Aplikasi dijalankan ulang
3. Aplikasi **otomatis memakai ADB** (terlihat dari koneksi TCP ke
   `127.0.0.1:5555`) dan perintahnya sukses tanpa error
4. Server Shizuku dihidupkan lagi, aplikasi dijalankan ulang → kembali ke
   Shizuku

Jadi aplikasi tidak menjadi tidak bisa dipakai ketika Shizuku berhenti —
misalnya setelah HP di-restart, karena Shizuku memang tidak otomatis jalan.

**Dua celah andal yang ditemukan dan diperbaiki:**

1. `ShellModeInitializer` mengembalikan hasil Shizuku apa adanya. Kalau Shizuku
   punya izin tetapi gagal disiapkan (mis. binary tidak bisa disinggahkan),
   jalur ADB tidak pernah dicoba — padahal kuncinya masih tersimpan. Sekarang
   kegagalan Shizuku dicatat dan ADB tetap dicoba.
2. Batas waktu perintah 600 detik terlalu ketat untuk game besar. Satu perintah
   `tar | zstd` untuk 11 GB yang ditulis ke kartu SD kelas 10 sudah lebih dari
   18 menit. Dinaikkan menjadi 3600 detik pada kedua backend. Catatan penting:
   saat batas waktu terlampaui kita hanya berhenti menunggu, proses di sisi
   shell bisa terus berjalan — jadi membiarkannya selesai lebih baik.

---

### 19. Backup game asli: Azur Lane

Uji pertama pada game asli (bukan dummy), sekaligus membuat backup nyata untuk
pengguna.

**Cara melewati data 11 GB.** Chip jenis data di layar **Details** (ketuk baris
game, bukan kotak centang) tersimpan ke database dan dihormati saat backup:
`backupApk` dan `backupData` memeriksa `p.getDataSelected(dataType)` lalu
menandai SKIP. Urutannya:

1. Buka Details Azur Lane, matikan chip **DATA** (11,02 GB)
2. **Verifikasi di database sebelum menjalankan backup** — kalau pilihannya
   tidak tersimpan, backup akan mencoba 11 GB dan memenuhi kartu SD:
   ```
   select dataStates_dataState from PackageEntity
    where indexInfo_packageName='com.YoStarEN.AzurLane'   -- harus NotSelected
   ```
3. Kembali, pilih lewat kotak centang — indikatornya berubah **kuning** sebagai
   tanda tidak semua jenis data dipilih
4. Continue tiga kali

**Hasil:**

| Bagian | Hasil |
|---|---|
| DATA 11,02 GB | **SKIP** |
| APK 224 MB | `apk.tar.zst` 68 MB, 212 MiB/detik |
| OBB 1,45 GB | `obb.tar.zst` 1,28 GB, 60 detik (25 MiB/detik) |
| MEDIA | tidak ada, dilewati |
| USER / USER_DE | lewat bmgr |
| Hasil UI | "1 apps backed up, 6/6" |

Kartu SD: 12 GB → 10 GB tersisa.

**Temuan penting — bmgr ditolak untuk Azur Lane:**

```
bmgr backupnow com.YoStarEN.AzurLane
Package com.YoStarEN.AzurLane with result: Backup is not allowed
```

Dua percobaan, lalu menyerah dengan pesan jelas. Artinya **data privat Azur Lane
tidak bisa di-backup** — game ini memakai `allowBackup="false"` atau diblokir
MIUI. Aplikasi menanganinya dengan benar: peringatan, bukan kegagalan, dan APK
serta OBB tetap utuh. Untuk game online seperti Azur Lane ini tidak fatal:
progres ada di server, sedangkan bagian yang mahal (1,7 GB aset) justru
terbackup.

**Catatan timeout.** OBB 1,45 GB selesai dalam 60 detik. Untuk 11 GB itu sekitar
7,5 menit — masih di bawah batas lama 600 detik tetapi hanya terpaut sedikit,
jadi kenaikan batas ke 3600 detik tetap masuk akal untuk kartu yang lebih
lambat.

---

## Yang belum dikerjakan

1. **`core/network`** (SMB/SFTP/FTP/WebDAV) masih ada. Modul itu juga berisi
   pemeriksaan versi rilis dari GitHub yang masih dipakai dashboard, jadi yang
   bisa dihapus hanya bagian klien cloud-nya. `CloudRepository` masih di-inject
   ke `AppsRepo`, `PackageRepository`, dan service backup/restore. Menghapusnya
   menyentuh ~15 berkas di `core/data` dan `core/service`.
2. **`core/data/repository/FilesRepo.kt`** dan **`MediaRepository.kt`** masih
   ada, beserta `FilesLoadWorker`/`FilesUpdateWorker` dan cabang `Target.Files`
   di `ListActions`, `ListItems`, `ListItemsViewModel`,
   `ListBottomSheetViewModel`, `ListActionsViewModel`, `ListDataRepo`,
   `DetailsViewModel`. Sudah tidak bisa dijangkau dari UI. (Langkah startup-nya
   sudah dihentikan di `WorkManagerInitializer`.)
3. **`Target.Files`** masih ada di enum, dan `MediaEntity` masih ada di skema
   database. Menghapus entitasnya butuh migrasi dan menyentuh lebih banyak
   berkas.
4. **`ssaid` dan keystore per-uid tetap hanya bisa dibaca mode root.**
   `settings_ssaid.xml` (`/data/system/users/<id>/`) dan `keystore_cli_v2`
   berada di luar jangkauan uid 2000; pada mode shell keduanya dilewati tanpa
   menggagalkan restore (bagian 16).
5. **`setDisplayPowerMode` menjadi no-op.** Memaksa layar mati hanya bisa
   dilakukan sistem.
6. **Citra `bmgr` tetap terikat perangkat untuk paket non-debuggable.**
   Paket debuggable sudah portabel lewat `run-as` (bagian 16); untuk yang
   lain Transport tetap `local` dan tidak ikut tersalin ke kartu SD
   (lihat batasan di bagian 12).
7. **Jalur Shizuku sudah diuji perangkat** (bagian 18). Yang belum:
   - Alur Wireless debugging di Android 11+ (perangkat uji Android 10, jadi
     Shizuku dimulai lewat "Start via Wireless debugging" dengan
     `adb tcpip 5555`).
   - Pemulihan izin/appops lewat tombol restore izin (perintahnya sudah
     terbukti jalan di jalur lain).
8. **Data privat game yang memakai `allowBackup="false"` tidak bisa diambil.**
   Azur Lane termasuk: `bmgr` menolak dengan "Backup is not allowed" (bagian
   19). Aplikasi menanganinya sebagai peringatan, bukan kegagalan. Untuk game
   online ini tidak fatal karena progres ada di server, tetapi untuk game
   offline yang menolak backup, data privatnya memang tidak bisa diselamatkan
   tanpa root.
9. **Catatan uji: kotak centang TIDAK bermasalah.** Sempat diduga area sentuhnya
   tidak stabil, ternyata itu keliru — penyebabnya daftar **tersusun ulang**
   antar-tap (item terpilih naik ke atas), sehingga koordinat dari tangkapan
   layar lama mengenai baris yang salah.

   Hierarki UI membuktikan kotak centangnya sehat: `android.widget.CheckBox`
   berukuran 132x132 px (48dp), `clickable=true`, `checkable=true`, dan tinggi
   baris seragam 220 px. Tap di titik pusatnya selalu bekerja.

   Cara mengukur yang benar — jangan menebak dari tangkapan layar:
   ```
   adb shell uiautomator dump /data/local/tmp/ui.xml
   adb pull /data/local/tmp/ui.xml
   ```
   Lalu baca atribut `bounds`. Untuk daftar ini pusatnya:
   baris 1 `(971, 711)`, baris 2 `(971, 931)`, baris 3 `(971, 1151)` — dan
   **ukur ulang setiap kali daftar berubah susunan**.

---

## Ringkasan permukaan yang sudah bersih

| Permukaan | Status |
|---|---|
| Tab dan kartu Cloud di dashboard | Dihapus |
| Modul `feature/main/cloud` (13 berkas) | Dihapus |
| Rute Cloud, FTP, SFTP, SMB, WebDAV | Dihapus |
| Kartu "Backup Files" dan tombol Files di restore | Dihapus |
| Paket `processing/medium` dan layanan medianya | Dihapus |
| Opsi Cloud dan Files di halaman Configurations | Dihapus |
| Pemilih penyimpanan Local/Cloud | Dihapus, hanya lokal |
| Daftar aplikasi non-game | Disaring di sumber data |
| Opsi data privat pada mode Shizuku | Dinonaktifkan, dialihkan ke bmgr |
| Langkah startup untuk daftar berkas | Dihentikan |








---

## Cara build

Butuh JDK 17–21, Android SDK (platform 35, build-tools 35), NDK 25.2.9519653,
dan CMake 3.22.1.

JDK 21 sudah tersedia di mesin ini sebagai bawaan Android Studio:
`C:\Program Files\Android\openjdk\jdk-21.0.8`

```bash
cd source
export JAVA_HOME="C:/Program Files/Android/openjdk/jdk-21.0.8"
export ANDROID_HOME="C:/Users/FANNNDI/AppData/Local/Android/Sdk"
./gradlew :app:assembleArm64-v8aFossDebug
```

Nama variannya `Arm64-v8aFossDebug` (urutan dimensi: `abi` lalu `feature`).

Submodule `source/native/src/main/jni/external/tar/tar` harus sudah terisi:

```bash
git submodule update --init --recursive source/native/src/main/jni/external/tar/tar
```

### Kalau build gagal dengan `AccessDeniedException` pada cache Kotlin/KSP

Gejalanya seperti ini, dan **bukan** kesalahan kode:

```
e: java.nio.file.AccessDeniedException:
   .../build/kotlin/compileDebugKotlin/cacheable/caches-jvm/jvm/kotlin/supertypes.tab.len
```

Penyebabnya kompilasi inkremental Kotlin/KSP memakai file memory-mapped yang
bisa terkunci. Solusinya matikan inkremental dan jalankan kompilator di dalam
proses Gradle:

```bash
./gradlew :app:assembleArm64-v8aFossDebug \
  -Pkotlin.incremental=false \
  -Pksp.incremental=false \
  -Pkotlin.compiler.execution.strategy=in-process
```

Kalau cache-nya sudah terlanjur rusak, bersihkan dulu:

```bash
find . -path "*/build/kotlin" -type d -prune -exec rm -rf {} +
find . -path "*/build/kspCaches" -type d -prune -exec rm -rf {} +
```

### Catatan lingkungan lain

- NDK 25.2.9519653 tidak ada di mesin ini pada awalnya; Gradle mengunduhnya
  otomatis saat konfigurasi (~1,4 GB). NDK 28.2 dan 29.0 yang sudah terpasang
  tidak dipakai karena `source/native/build.gradle.kts` meminta versi 25.2.
- `~/.gradle` tidak bisa dipakai sebagai Gradle home di lingkungan ini
  (`journal-1.lock: Access is denied`). Sementara diarahkan ke
  `.workbuddy-ai/gradle-home` lewat `GRADLE_USER_HOME`. Direktori itu besar
  (~900 MB) dan sudah masuk `.gitignore`, aman dihapus kalau tidak dipakai lagi.
- Jangan jalankan perintah `git` secara paralel di repo ini. Pernah terjadi
  `git rm -r` pada satu direktori menghapus seluruh `source/feature/`;
  semuanya pulih dengan `git checkout HEAD -- source/feature`.

---

## Untuk kembali ke upstream

Branch `main` tetap bersih dan tidak tersentuh. Semua perubahan fork ada di
branch `fork-cleanup`.

```bash
git checkout main          # kembali ke versi upstream
git diff main..fork-cleanup --stat   # lihat ringkasan perubahan
```
