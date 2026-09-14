# Backup game Android tanpa root — harness uji kelayakan

Folder ini berisi alat untuk **membuktikan di perangkat nyata** seberapa jauh
data sebuah game bisa dibackup sebagai `uid 2000` (shell) — identitas yang sama
persis dengan yang diberikan Shizuku pada perangkat non-root.

Semua perintah yang dijalankan harness ini adalah perintah shell yang sama yang
akan dijalankan Shizuku. **Kalau harness ini berhasil, mode Shizuku pasti
berhasil. Kalau gagal, Shizuku juga akan gagal.** Jadi kita menguji kelayakan
tanpa harus menulis satu baris Kotlin pun.

---

## Ringkasan jawaban

Analisis pertama saya ("tidak bisa") benar untuk aplikasi pada umumnya, tapi
**terlalu cepat untuk kasus game**. Ada dua alasan:

1. **Banyak game tidak menyimpan progres di `/data/data`.** Game Unity dan
   Unreal menyimpan save di penyimpanan eksternal, yang bisa dibaca shell.
2. **`/data/data` ternyata tetap bisa dibackup tanpa root** — bukan lewat akses
   berkas, tapi lewat **BackupManager** (`bmgr`). Ini jalur yang terlewat di
   analisis pertama.

---

## Di mana game menyimpan progresnya

| Jenis game | Lokasi save | Shell bisa? |
|---|---|---|
| Unity (mayoritas) | `/sdcard/Android/data/<pkg>/files/` | Ya |
| Unity `PlayerPrefs` | `/data/data/<pkg>/shared_prefs/` | Tidak (tapi lihat jalur bmgr) |
| Unreal Engine | `/sdcard/Android/data/<pkg>/files/UnrealGame/.../SaveGames/` | Ya |
| Game ber-OBB | `/sdcard/Android/obb/<pkg>/` | Ya |
| Game native/Java | `/data/data/<pkg>/{files,databases,shared_prefs}` | Tidak (tapi lihat jalur bmgr) |

Jadi untuk **sebagian besar game**, 2 dari 3 bagian (OBB + Android/data) sudah
bisa ditangani penuh lewat Shizuku saja.

---

## Empat jalur yang dipakai harness

| # | Bagian | Perintah | Shell (uid 2000) |
|---|---|---|---|
| 1 | APK | `pm path` + tarik berkas | Bisa |
| 2 | OBB | `/sdcard/Android/obb/<pkg>` | Bisa (grup `ext_obb_rw`) |
| 3 | Data eksternal | `/sdcard/Android/data/<pkg>` | Bisa (grup `ext_data_rw`) |
| 4 | **Data privat** | **`bmgr` (BackupManager)** | **Bisa** |

### Kenapa jalur 4 bekerja

`/data/data/<pkg>` memang tidak bisa dibaca shell — itu fakta dan tidak berubah.
Tapi kita tidak perlu membacanya sendiri.

`bmgr` adalah perkakas shell yang berbicara dengan **BackupManagerService**.
Service itu berjalan sebagai `uid system`. Ketika kita memerintahkan
`bmgr backupnow <pkg>`, **sistem** yang membaca `/data/data/<pkg>` dan
menyerahkan datanya ke *transport*. Dan saat restore, **sistem** yang menulis
kembali berkasnya — dengan kepemilikan uid/gid dan konteks SELinux yang benar.

Ini menyelesaikan dua masalah sekaligus:

- hak baca (dilakukan oleh `uid system`, bukan oleh kita)
- `chown`/`chcon` saat restore (yang mustahil dilakukan shell)

Rangkaian perintahnya:

```bash
adb shell bmgr enabled
adb shell bmgr transport com.android.localtransport/.LocalTransport
adb shell bmgr backupnow <pkg>        # -> "Package <pkg> with result: Success"
adb shell dumpsys backup              # -> ambil token restore
adb shell bmgr restore <token> <pkg>  # nanti, saat mau memulihkan
adb shell bmgr run                    # paksa operasi terjadwal berjalan sekarang
```

Semuanya berjalan sebagai shell. Tidak ada root di mana pun.

---

## Batasan yang harus kamu tahu (jujur)

1. **Citra transport `local` tersimpan di penyimpanan internal perangkat.**
   Shell tidak bisa membacanya, jadi backup ini **tidak bisa di-export** ke SD
   card / PC / cloud, dan **tidak selamat dari factory reset**.
   Cocok untuk: rollback setelah update rusak, memulihkan setelah salah
   hapus data, menyelamatkan progres sebelum eksperimen berisiko.
   Tidak cocok untuk: pindah ke HP baru, atau backup sebelum factory reset.

2. **Untuk lintas perangkat**, pakai transport `cloud`
   (`--transport cloud`, yaitu Google Drive lewat Auto Backup). Bisa dipulihkan
   di HP baru dengan akun Google yang sama, **tapi kuota Auto Backup 25 MB per
   aplikasi**. Untuk save game yang kecil ini cukup; untuk yang besar tidak.

3. **Hanya berlaku untuk aplikasi yang `allowBackup` efektif `true`.**
   Perintah `analyze` melaporkan status ini. Kalau `false`, `bmgr` kemungkinan
   menolak. Kabar baiknya: sejak Android 12, `allowBackup="false"` **tidak**
   mematikan transfer D2D, hanya backup cloud — jadi masih ada peluang, dan
   harness ini akan menunjukkannya secara empiris.

4. **`cache/` dan `no_backup/` selalu dikecualikan** oleh BackupManager.
   Umumnya tidak masalah (keduanya memang data sementara).

5. **Android 14+ memblokir `Android/data` untuk aplikasi pihak ketiga**,
   termasuk yang punya `MANAGE_EXTERNAL_STORAGE`. Ini justru **memperkuat**
   rencana Shizuku: di Android 14+, Shizuku adalah satu-satunya jalan tanpa
   root untuk membaca `Android/data`.

---

## Cara pakai

Butuh Python 3 dan Android Platform Tools (`adb`). Harness mendeteksi `adb`
otomatis dari lokasi SDK standar.

```bash
# 1. Periksa perangkat + lihat batas kemampuan shell secara empiris
python game_backup.py doctor

# 2. Bedah di mana sebuah game menyimpan datanya + verdict kelayakan
python game_backup.py analyze com.nama.game

# 3. Backup (APK + OBB + Android/data + data privat)
python game_backup.py backup com.nama.game --out D:/backup-game

# 4. Lihat token restore yang tersedia di perangkat
python game_backup.py tokens

# 5. Pulihkan
python game_backup.py restore com.nama.game --from D:/backup-game --yes
```

Perintah `restore` bersifat menimpa dan **butuh `--yes`**. Tanpa flag itu ia
hanya menampilkan peringatan lalu berhenti — tidak ada yang diubah.

### Perintah `doctor` adalah yang paling penting

Ia mencoba membaca sepuluh path sebagai shell dan melaporkan mana yang terbuka
dan mana yang tertutup. Itu bukti empiris di perangkatmu sendiri, bukan teori
dari saya. Kirimkan keluarannya ke saya dan kita bisa lanjut ke langkah
berikutnya dengan data nyata.

---

## Rencana port ke Shizuku (kalau uji berhasil)

Pemetaan dari kode libsu yang sekarang ada di repo ke Shizuku:

| Sekarang (libsu, butuh root) | Diganti dengan (Shizuku) |
|---|---|
| `Shell.cmd(...)` di `core/util/.../BaseUtil.kt` | `Shizuku.newProcess(arrayOf("sh","-c",cmd), null, null)` |
| `Shell.getShell().isRoot` | `Shizuku.getUid()` — `0` = root, `2000` = shell |
| `RootService.bind(...)` di `core/rootservice` | `Shizuku.bindUserService(...)` |
| `IRemoteRootService` AIDL | Shizuku `UserService` + `ShizukuBinderWrapper` |
| `nsenter --mount=/proc/1/ns/mnt sh` | dihapus — tidak tersedia untuk shell |
| `FLAG_MOUNT_MASTER` | dihapus |
| `chown` / `chcon` saat restore | dihapus — digantikan oleh restore BackupManager |
| `rootService.getInstalledPackagesAsUser()` | `ShizukuBinderWrapper` + `SystemServiceHelper.getSystemService("package")` |

Dependensi yang ditambahkan ke `app/build.gradle.kts`:

```kotlin
implementation("dev.rikka.shizuku:api:<versi>")
implementation("dev.rikka.shizuku:provider:<versi>")
```

Dan provider di `AndroidManifest.xml`:

```xml
<provider
    android:name="rikka.shizuku.ShizukuProvider"
    android:authorities="${applicationId}.shizuku"
    android:exported="true"
    android:permission="android.permission.INTERACT_ACROSS_USERS_FULL" />
```

Sumber referensi sudah ada di mesinmu: `C:\Users\FANNNDI\Documents\Shizuku\Shizuku-API`.

---

## Yang tidak akan pernah bisa, dengan atau tanpa Shizuku

Supaya ekspektasinya jelas:

- Menyalin `/data/data/<pkg>` **sebagai berkas** ke SD card / PC.
- `chown` / `chcon` manual saat restore.
- Backup multi-user (user 10 ke atas).
- SSAID (`settings_ssaid.xml`).
- Mount namespace global (`nsenter`).

Semua itu tetap butuh root. Tapi untuk kebutuhan **"backup dan restore game"**,
kombinasi keempat jalur di atas sudah menutup hampir semua kasus nyata.
