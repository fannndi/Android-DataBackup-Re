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

---

## Yang belum dikerjakan

Ini penting supaya ekspektasinya jelas. Perubahan di atas menghapus fitur dari
**UI dan modulnya**, tetapi lapisan data masih menyimpan kode mati:

1. **`core/network`** (SMB/SFTP/FTP/WebDAV) masih ada. Masih dipakai
   `CloudRepository`, yang masih di-inject ke `AppsRepo`, `PackageRepository`,
   dan service backup/restore. Menghapusnya menyentuh ~15 berkas di `core/data`
   dan `core/service`.
2. **`core/service/medium/`** dan `MediumBackupUtil`/`MediumRestoreUtil` masih
   ada, sekarang tanpa pemakai dari UI.
3. **`Target.Files`** masih ada di enum, dan masih ditangani di `ListActions`,
   `ListItems`, `ListItemsViewModel`, `ListBottomSheetViewModel`,
   `ListActionsViewModel`, `ListDataRepo`, `DetailsViewModel`. Sudah tidak bisa
   dijangkau dari UI.
4. **`Bmgr` belum terhubung ke alur backup/restore.** Kelasnya sudah ada dan
   teruji, tetapi belum dipanggil dari service mana pun. Ini pekerjaan
   berikutnya: menjadikannya sumber `DataType` baru (mis. `PACKAGE_PRIVATE_BMGR`)
   dengan migrasi Room, tombol di layar detail game, dan alur restore
   `pm clear` → `bmgr restore` → `bmgr run`.
5. **libsu masih dipakai** dan masih butuh root. Penggantian ke Shizuku
   (`Shizuku.newProcess`, `Shizuku.bindUserService`) belum dilakukan. Pemetaan
   lengkapnya ada di `tools/game-backup/README.md`.

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
