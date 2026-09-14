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

---

## Yang belum dikerjakan

1. **`core/network`** (SMB/SFTP/FTP/WebDAV) masih ada. Masih dipakai
   `CloudRepository`, yang masih di-inject ke `AppsRepo`, `PackageRepository`,
   dan service backup/restore. Menghapusnya menyentuh ~15 berkas di `core/data`
   dan `core/service`.
2. **`core/data/repository/FilesRepo.kt`** dan **`MediaRepository.kt`** masih
   ada, beserta `FilesLoadWorker`/`FilesUpdateWorker` dan cabang `Target.Files`
   di `ListActions`, `ListItems`, `ListItemsViewModel`,
   `ListBottomSheetViewModel`, `ListActionsViewModel`, `ListDataRepo`,
   `DetailsViewModel`. Sudah tidak bisa dijangkau dari UI. (Langkah startup-nya
   sudah dihentikan di `WorkManagerInitializer`.)
3. **`Target.Files`** masih ada di enum, dan `MediaEntity` masih ada di skema
   database. Menghapus entitasnya butuh migrasi dan menyentuh lebih banyak
   berkas.
4. **Ukuran penyimpanan tampil kosong pada mode Shizuku.** Bisa diperbaiki
   dengan meminta izin `PACKAGE_USAGE_STATS` (Usage access) ke pengguna.
5. **`setDisplayPowerMode` menjadi no-op.** Memaksa layar mati hanya bisa
   dilakukan sistem.
6. **Transport portabel untuk citra bmgr.** Lihat batasan di atas. Ini yang
   akan membuat data privat ikut tersalin ke kartu SD.
7. **Belum diuji di perangkat.** Seluruh kode Shizuku terverifikasi kompilasi,
   belum pernah dijalankan di HP sungguhan. Lima hal yang paling perlu diuji
   lebih dulu:
   - apakah `/data/local/tmp/databackup-bin` bisa dieksekusi shell
   - apakah shell bisa menulis ke kartu SD lewat `/storage/<uuid>`
   - apakah `bmgr` diterima untuk game dengan `allowBackup="false"`
   - apakah `find -empty -delete` dan `ls -p` tersedia di toybox perangkat
   - apakah migrasi database 7 ke 8 berjalan mulus saat aplikasi dibuka







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
