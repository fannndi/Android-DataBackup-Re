package com.xayah.core.util.command

import android.content.Context
import android.os.Build
import android.util.Log
import com.xayah.core.util.model.ShellResult
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import io.github.muntashirakon.adb.AdbPairingRequiredException
import io.github.muntashirakon.adb.AdbStream
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Klien ADB milik aplikasi.
 *
 * Menyimpan pasangan kunci RSA di [File] privat aplikasi dan memakainya untuk
 * pairing serta autentikasi ke `adbd` di perangkat yang sama. Kunci dibuat
 * sekali; pairing berikutnya tidak perlu kode lagi selama `adbd` masih
 * menyimpan kunci publiknya.
 */
internal class LocalAdbManager private constructor(context: Context) : AbsAdbConnectionManager() {
    companion object {
        private const val TAG = "LocalAdbManager"
        private const val KEY_DIR = "adb-key"
        private const val PRIVATE_KEY_FILE = "private.key"
        private const val CERTIFICATE_FILE = "cert.der"
        private const val KEY_SIZE = 2048
        private const val TEN_YEARS_MILLIS = 10L * 365L * 24L * 60L * 60L * 1000L

        @Volatile
        private var instance: LocalAdbManager? = null

        fun getInstance(context: Context): LocalAdbManager =
            instance ?: synchronized(this) {
                instance ?: LocalAdbManager(context.applicationContext).also { instance = it }
            }

        /** True kalau kunci sudah pernah dibuat, jadi pairing mungkin masih sah. */
        fun hasSavedKey(context: Context): Boolean =
            File(File(context.filesDir, KEY_DIR), PRIVATE_KEY_FILE).isFile
    }

    private val privateKey: PrivateKey
    private val certificate: Certificate

    init {
        setApi(Build.VERSION.SDK_INT)
        // Cukup longgar untuk menekan "Izinkan" pada dialog debugging di HP,
        // tetapi tidak menggantung selamanya kalau kunci ditolak.
        setTimeout(60, TimeUnit.SECONDS)
        val keys = loadOrGenerate(context)
        privateKey = keys.first
        certificate = keys.second
    }

    override fun getPrivateKey(): PrivateKey = privateKey

    override fun getCertificate(): Certificate = certificate

    override fun getDeviceName(): String = "DataBackup"

    /**
     * Membaca kunci dari berkas, atau membuat baru kalau belum ada / rusak.
     *
     * Sertifikat ditulis dalam bentuk DER; [CertificateFactory] membacanya
     * langsung tanpa perlu pembungkus PEM.
     */
    private fun loadOrGenerate(context: Context): Pair<PrivateKey, Certificate> {
        val dir = File(context.filesDir, KEY_DIR)
        val keyFile = File(dir, PRIVATE_KEY_FILE)
        val certFile = File(dir, CERTIFICATE_FILE)
        if (keyFile.isFile && certFile.isFile) {
            runCatching {
                val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                val cert = certFile.inputStream().use {
                    CertificateFactory.getInstance("X.509").generateCertificate(it)
                }
                return key to cert
            }.onFailure { Log.w(TAG, "Kunci ADB tidak terbaca, dibuat ulang", it) }
        }

        dir.mkdirs()
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_SIZE) }.generateKeyPair()
        val now = Date()
        val until = Date(now.time + TEN_YEARS_MILLIS)
        val subject = X500Name("CN=DataBackup")
        val builder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger.valueOf(now.time),
            now,
            until,
            subject,
            pair.public,
        )
        val signer = JcaContentSignerBuilder("SHA256withRSA").build(pair.private)
        val cert = JcaX509CertificateConverter().getCertificate(builder.build(signer))

        keyFile.writeBytes(pair.private.encoded)
        certFile.writeBytes(cert.encoded)
        return pair.private to cert
    }
}

/**
 * Backend eksekusi perintah lewat ADB mandiri — berjalan sebagai `uid 2000`
 * (shell) tanpa root dan **tanpa aplikasi Shizuku**.
 *
 * Aplikasi berbicara protokol ADB langsung ke `adbd` di perangkat yang sama
 * lewat Wireless debugging. Setelah pairing sekali, kunci di
 * [LocalAdbManager] dipakai lagi untuk koneksi berikutnya, jadi cukup
 * menyalakan Wireless debugging (dan mengulang pairing kalau kuncinya dihapus).
 *
 * Satu perintah = satu stream `shell:` yang ditutup setelah selesai. Exit code
 * diambil dengan penanda unik di akhir keluaran, karena stream ADB tidak
 * membawa exit code secara langsung.
 */
object AdbShell {
    private const val TAG = "AdbShell"

    /**
     * Batas waktu bawaan.
     *
     * Satu perintah `tar | zstd` untuk backup game besar bisa berjalan sangat
     * lama: 11 GB dibaca dari penyimpanan internal lalu ditulis ke kartu SD.
     * Pada kartu kelas 10 (sekitar 10 MB/detik) itu saja sudah lebih dari 18
     * menit, sehingga batas 10 menit sebelumnya akan membuang pekerjaan yang
     * sebenarnya sedang berjalan normal.
     *
     * Saat batas waktu terlampaui kita hanya berhenti menunggu; perintah di
     * sisi perangkat bisa terus berjalan. Karena itu membiarkan pekerjaan sah
     * selesai lebih baik daripada memutusnya lebih awal. Nilai <= 0 berarti
     * tanpa batas.
     */
    private const val DEFAULT_TIMEOUT_SECONDS = 3600L

    private const val CONNECT_TIMEOUT_MILLIS = 5_000L
    private const val DISCOVERY_TIMEOUT_MILLIS = 15_000L

    /** Port ADB klasik, dipakai kalau mDNS tidak menemukan port dinamis. */
    private const val LEGACY_ADB_PORT = 5555

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var staged = false

    private val connectMutex = Mutex()

    /** Dipanggil sekali saat aplikasi memilih mode ADB. */
    fun configure(context: Context) {
        appContext = context.applicationContext
    }

    suspend fun isConnected(): Boolean = withContext(Dispatchers.IO) {
        manager()?.isConnected == true
    }

    /**
     * True kalau kunci ADB sudah pernah dibuat.
     *
     * Dipakai saat startup untuk menebak bahwa mode ADB masih relevan, tanpa
     * menunggu koneksi yang bisa lambat.
     */
    fun hasSavedKey(context: Context): Boolean = LocalAdbManager.hasSavedKey(context)

    /** Pairing dengan kode 6 digit dari layar Wireless debugging. */
    suspend fun pair(host: String, port: Int, pairingCode: String): Boolean = withContext(Dispatchers.IO) {
        val manager = manager() ?: return@withContext false
        runCatching { manager.pair(host, port, pairingCode) }
            .onFailure { Log.e(TAG, "Pairing gagal", it) }
            .getOrDefault(false)
    }

    /**
     * Mencari port pairing lewat mDNS (`_adb-tls-pairing._tcp`).
     *
     * Dipakai untuk mengisi dialog supaya pengguna tidak perlu mengetik port;
     * kode 6 digit tetap harus dibaca dari layar Wireless debugging.
     */
    suspend fun discoverPairingPort(): Int = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext -1
        val port = AtomicInteger(-1)
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(context, AdbMdns.SERVICE_TYPE_TLS_PAIRING) { host, found ->
            if (host != null && found > 0) {
                port.set(found)
                latch.countDown()
            }
        }
        mdns.start()
        try {
            if (latch.await(DISCOVERY_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) port.get() else -1
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            -1
        } finally {
            mdns.stop()
        }
    }

    /** Menyambung ke `adbd` lokal; true kalau sudah terhubung. */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        connectMutex.withLock { connectInternal() }
    }

    private fun connectInternal(): Boolean {
        val context = appContext ?: return false
        val manager = manager() ?: return false
        if (manager.isConnected) return true

        try {
            if (manager.autoConnect(context, CONNECT_TIMEOUT_MILLIS)) return true
        } catch (_: AdbPairingRequiredException) {
            // Kunci belum dikenal adbd; pengguna harus pairing dulu.
            return false
        } catch (e: Throwable) {
            Log.w(TAG, "Auto-connect gagal, mencoba port klasik", e)
        }

        return runCatching { manager.connect("127.0.0.1", LEGACY_ADB_PORT) }.getOrDefault(false)
    }

    /**
     * Menjalankan [command] dan mengembalikan hasilnya.
     *
     * Keluaran diakhiri `echo <penanda>$?` di dalam subkulit supaya exit code
     * perintah asli tetap terbaca, lalu baris penanda itu dibuang dari hasil.
     */
    suspend fun exec(command: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): ShellResult =
        withContext(Dispatchers.IO) {
            val result = ShellResult(code = -1, input = listOf(command), out = listOf())
            val manager = manager() ?: return@withContext result
            if (!manager.isConnected && !connectInternal()) {
                Log.e(TAG, "ADB belum terhubung")
                return@withContext result
            }

            val marker = "__DBX_${System.nanoTime().toString(16)}__"
            val wrapped = ShellEnv.wrap("($command); echo $marker$?")
            val stream = runCatching { manager.openStream("shell:$wrapped") }
                .onFailure { Log.e(TAG, "Gagal membuka stream shell", it) }
                .getOrNull() ?: return@withContext result

            try {
                val watchdog = if (timeoutSeconds > 0) {
                    launch {
                        delay(timeoutSeconds * 1000)
                        Log.e(TAG, "Perintah melewati batas waktu: $command")
                        runCatching { stream.close() }
                    }
                } else {
                    null
                }

                val raw = readUntilMarker(stream, marker)
                watchdog?.cancel()

                val parsed = AdbExecOutput.parse(raw, marker)
                result.code = parsed.first
                result.out = parsed.second
            } finally {
                runCatching { stream.close() }
            }

            result
        }

    /**
     * Membaca keluaran sampai penanda exit code terlihat.
     *
     * Sengaja **tidak** menunggu EOF: libadb dapat menahan `read()` setelah
     * peer menutup stream ketika data terakhir sudah dikirim, sehingga membaca
     * sampai habis bisa menggantung sampai watchdog menutup stream. Penanda
     * selalu menjadi keluaran terakhir, jadi begitu terlihat pembacaan
     * dihentikan dan stream ditutup sendiri.
     */
    private fun readUntilMarker(stream: AdbStream, marker: String): String {
        val input = stream.openInputStream()
        val buffer = ByteArrayOutputStream()
        val chunk = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = runCatching { input.read(chunk) }.getOrDefault(-1)
            if (read <= 0) break
            buffer.write(chunk, 0, read)
            if (buffer.toString(Charsets.UTF_8.name()).contains(marker)) break
        }
        return buffer.toString(Charsets.UTF_8.name())
    }

    /**
     * Menyalin binary bawaan aplikasi supaya bisa dijalankan shell.
     *
     * Pekerjaan sebenarnya di [ShellBinStaging]; status "sudah" di-cache di
     * sini karena penyinggahan tidak perlu diulang dalam satu sesi.
     */
    suspend fun stageBinaries(context: Context): Boolean {
        if (staged) return true

        val ready = ShellBinStaging.stage(context) { command, timeout ->
            exec(command, timeout)
        }
        if (ready) staged = true
        return ready
    }

    private fun manager(): LocalAdbManager? = appContext?.let { LocalAdbManager.getInstance(it) }
}

/**
 * Mengurai keluaran [AdbShell.exec]: cari penanda terakhir, ambil exit code di
 * belakangnya, dan buang penanda dari keluaran.
 *
 * Penanda bisa menempel langsung pada keluaran terakhir kalau perintah tidak
 * mengakhiri barisnya, jadi pemisahan dilakukan per indeks penanda, bukan per
 * baris. Dipisah supaya bisa diuji tanpa perangkat.
 */
internal object AdbExecOutput {
    fun parse(raw: String, marker: String): Pair<Int, List<String>> {
        val markerIndex = raw.lastIndexOf(marker)
        if (markerIndex < 0) return -1 to splitLines(raw)

        val code = raw.substring(markerIndex + marker.length)
            .trimStart()
            .takeWhile { it.isDigit() }
            .toIntOrNull() ?: -1

        return code to splitLines(raw.substring(0, markerIndex))
    }

    private fun splitLines(raw: String): List<String> =
        raw.split('\n').map { it.trimEnd('\r') }.filter { it.isNotEmpty() }
}
