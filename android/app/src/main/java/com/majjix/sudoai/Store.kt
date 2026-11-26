package com.majjix.sudoai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.system.Os
import android.system.OsConstants
import android.util.Base64
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

fun uuid() = UUID.randomUUID().toString()

fun sha(bytes: ByteArray) =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

fun b64(bytes: ByteArray) =
    Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

fun syncDirectory(directory: File) {
    val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
    try {
        Os.fsync(fd)
    } finally {
        Os.close(fd)
    }
}

fun atomic(file: File, text: String) {
    val tmp = File(file.path + ".tmp")
    FileOutputStream(tmp).use {
        it.write(text.toByteArray())
        it.fd.sync()
    }
    check(tmp.renameTo(file))
    syncDirectory(file.parentFile!!)
}

class ServerResponseException(val code: Int) :
    IllegalStateException(
        if (code == 401) "Sign in again. Pending uploads retained."
        else "Server HTTP $code. Pending uploads retained."
    )

typealias UploadRequest = (String, String, ByteArray?, String, String?) -> String

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("credentials", Context.MODE_PRIVATE)
    private val key: SecretKey
        get() {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            return (ks.getKey("sudoai-token", null) as? SecretKey)
                ?: KeyGenerator.getInstance("AES", "AndroidKeyStore").run {
                    init(
                        KeyGenParameterSpec.Builder(
                                "sudoai-token",
                                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                            )
                            .setBlockModes("GCM")
                            .setEncryptionPaddings("NoPadding")
                            .build()
                    )
                    generateKey()
                }
        }

    fun put(name: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        prefs
            .edit()
            .putString(
                name,
                Base64.encodeToString(
                    cipher.iv + cipher.doFinal(value.toByteArray()),
                    Base64.NO_WRAP,
                ),
            )
            .commit()
    }

    fun get(name: String): String? =
        try {
            prefs.getString(name, null)?.let {
                val bytes = Base64.decode(it, Base64.NO_WRAP)
                Cipher.getInstance("AES/GCM/NoPadding").run {
                    init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
                    String(doFinal(bytes.copyOfRange(12, bytes.size)))
                }
            }
        } catch (_: Exception) {
            null
        }

    fun clear() {
        prefs.edit().clear().commit()
    }
}

class Store private constructor(val context: Context) {
    val vault = Vault(context)
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    var base: String
        get() = prefs.getString("base", "https://sudoai.example.com")!!
        set(value) {
            val url = URI(value.trim().trimEnd('/'))
            require(
                url.scheme == "https" &&
                    url.host != null &&
                    url.userInfo == null &&
                    url.query == null &&
                    url.fragment == null
            ) {
                "Use an HTTPS server URL"
            }
            require(owner == null) { "Sign out before changing server" }
            prefs.edit().putString("base", url.toString().trimEnd('/')).commit()
        }

    val owner
        get() = vault.get("owner")

    val root = File(context.filesDir, "captures").apply { mkdirs() }
    val executor = Executors.newSingleThreadScheduledExecutor()
    @Volatile var status = "Offline queue ready"
    private var failures = 0
    private var retryAt = 0L

    init {
        executor.execute { runCatching { sweepAcknowledged() } }
        executor.scheduleWithFixedDelay(
            {
                if (System.currentTimeMillis() >= retryAt)
                    runCatching {
                            sync()
                            failures = 0
                        }
                        .onFailure {
                            failures = (failures + 1).coerceAtMost(7)
                            retryAt =
                                System.currentTimeMillis() +
                                    (30000L shl failures).coerceAtMost(3600000)
                            status =
                                (it.message ?: "Retry pending") +
                                    " Next automatic retry in ${(retryAt-System.currentTimeMillis())/1000}s"
                        }
            },
            10,
            30,
            TimeUnit.SECONDS,
        )
    }

    fun authStart(): String {
        val random = SecureRandom()
        val verifier = ByteArray(48).also(random::nextBytes).let(::b64)
        val state = ByteArray(32).also(random::nextBytes).let(::b64)
        vault.put("verifier", verifier)
        vault.put("state", state)
        vault.put("pkce_time", System.currentTimeMillis().toString())
        vault.put("pkce_server", base)
        return "$base/auth/native/start?platform=android&challenge=${b64(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))}&state=$state"
    }

    fun exchange(code: String, state: String) {
        require(
            base == vault.get("pkce_server") &&
                state.isNotEmpty() &&
                state == vault.get("state") &&
                System.currentTimeMillis() - (vault.get("pkce_time")?.toLongOrNull() ?: 0) < 600000
        ) {
            "Expired or invalid sign-in state"
        }
        val data =
            request(
                "POST",
                "/api/v1/auth/exchange",
                JSONObject()
                    .put("code", code)
                    .put("verifier", vault.get("verifier"))
                    .toString()
                    .toByteArray(),
                "application/json",
                false,
            )
        val token = JSONObject(data).getString("access_token")
        vault.put("token", token)
        try {
            val me = JSONObject(request("GET", "/api/v1/me"))
            vault.put("owner", me.getString("id"))
            vault.put("name", me.optString("name"))
            vault.put("state", "")
            status = "Signed in"
        } catch (e: Exception) {
            vault.put("token", "")
            throw e
        }
    }

    fun revoke() {
        request("POST", "/api/v1/auth/revoke", "{}".toByteArray())
        vault.clear()
        status = "Token revoked. Local files retained."
    }

    @Synchronized
    fun sessions() =
        root
            .listFiles()
            ?.filter { it.isDirectory && File(it, "session.json").exists() }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun visibleSessions(): List<File> {
        val current = owner ?: return emptyList()
        return sessions().filter { dir ->
            runCatching {
                    val m = manifest(dir)
                    m.optString("owner") == current && m.optString("server") == base
                }
                .getOrDefault(false)
        }
    }

    fun meetingUrl(dir: File): String? {
        val m = manifest(dir)
        if (
            m.optString("owner") != owner ||
                m.optString("server") != base ||
                m.optBoolean("deleted")
        )
            return null
        val id =
            if (m.optBoolean("is_import")) m.optString("canonical_id").takeIf { it.isNotBlank() }
            else dir.name
        return id?.let { "$base/sessions/$it" }
    }

    fun manifest(dir: File) = JSONObject(File(dir, "session.json").readText())

    @Synchronized
    fun create(title: String, device: String = "android"): File {
        require(owner != null) { "Sign in to bind offline recordings to your account" }
        val dir = File(root, uuid()).apply { mkdirs() }
        atomic(
            File(dir, "session.json"),
            JSONObject()
                .put("id", dir.name)
                .put("owner", owner)
                .put("server", base)
                .put("title", title)
                .put("started_at", Instant.now().toString())
                .put("device", device)
                .put("duration_ms", 0)
                .put("closed", false)
                .put("uploaded", false)
                .toString(),
        )
        enqueue(
            dir,
            "PUT",
            "/api/v1/sessions/${dir.name}",
            JSONObject()
                .put("title", title)
                .put("started_at", manifest(dir).getString("started_at"))
                .put("device", device)
                .toString()
                .toByteArray(),
            "application/json",
            "000-create",
        )
        return dir
    }

    @Synchronized
    fun queueImport(dir: File, name: String, mime: String) {
        val currentOwner = owner ?: error("Sign in before importing")
        java.io.RandomAccessFile(File(dir, "source-import"), "rw").use { it.fd.sync() }
        val m =
            JSONObject()
                .put("owner", currentOwner)
                .put("server", base)
                .put("title", name)
                .put("started_at", Instant.now().toString())
                .put("closed", true)
                .put("uploaded", false)
                .put("is_import", true)
                .put("import_name", name.replace(Regex("[^a-zA-Z0-9._ -]"), "_"))
                .put("import_mime", mime)
                .put("import_boundary", "sudoai-${uuid()}")
        atomic(File(dir, "session.json"), m.toString())
        ensureImportQueued(dir)
    }

    @Synchronized
    private fun ensureImportQueued(dir: File) {
        val m = manifest(dir)
        if (!m.optBoolean("is_import") || m.optString("canonical_id").isNotBlank()) return
        val source = File(dir, "source-import")
        if (File(dir, "000-import.job").exists()) {
            source.delete()
            return
        }
        if (File(dir, "000-import.ack").exists()) return
        require(source.exists() && m.has("import_boundary")) {
            "Import was interrupted before its queue committed. Reimport the source file."
        }
        val boundary = m.getString("import_boundary")
        val mime = m.getString("import_mime")
        val name = m.getString("import_name")
        val header =
            "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: $mime\r\n\r\n"
                .toByteArray()
        enqueue(
            dir,
            "POST",
            "/api/v1/import",
            header + source.readBytes() + "\r\n--$boundary--\r\n".toByteArray(),
            "multipart/form-data; boundary=$boundary",
            "000-import",
        )
        source.delete()
    }

    @Synchronized
    fun enqueue(
        dir: File,
        method: String,
        path: String,
        bytes: ByteArray,
        mime: String = "application/json",
        key: String = "500-${uuid()}",
    ) {
        if (manifest(dir).optBoolean("deleted")) return
        val body = File(dir, "$key.bin")
        FileOutputStream(body).use {
            it.write(bytes)
            it.fd.sync()
        }
        atomic(
            File(dir, "$key.job"),
            JSONObject()
                .put("method", method)
                .put("path", path)
                .put("mime", mime)
                .put("body", body.name)
                .put("digest", sha(bytes))
                .put("end_ms", eventEnd(path, bytes))
                .toString(),
        )
    }

    @Synchronized
    fun nextSequence(dir: File, track: String): Int =
        dir.listFiles()
            ?.mapNotNull { file ->
                if (file.name.startsWith("200-$track-") && file.extension in listOf("job", "ack"))
                    file.nameWithoutExtension.substringAfterLast('-').toIntOrNull()
                else null
            }
            ?.maxOrNull()
            ?.plus(1) ?: 0

    @Synchronized
    fun chunk(
        dir: File,
        track: String,
        start: Long,
        end: Long,
        wav: ByteArray,
        seq: Int = nextSequence(dir, track),
    ) {
        val key = "200-$track-${seq.toString().padStart(8,'0')}"
        val ack = File(dir, "$key.ack")
        if (ack.exists())
            require(JSONObject(ack.readText()).getString("digest") == sha(wav)) {
                "Conflicting recovered audio"
            }
        else
            enqueue(
                dir,
                "PUT",
                "/api/v1/sessions/${dir.name}/tracks/$track/chunks/$seq?start_ms=$start&end_ms=$end",
                wav,
                "audio/wav",
                key,
            )
        val m = manifest(dir)
        m.put("count_$track", maxOf(m.optInt("count_$track"), seq + 1))
        atomic(File(dir, "session.json"), m.toString())
    }

    fun gap(dir: File, track: String, start: Long, end: Long, reason: String) {
        enqueue(
            dir,
            "PUT",
            "/api/v1/sessions/${dir.name}/gaps/${uuid()}",
            JSONObject()
                .put("track", track)
                .put("start_ms", start)
                .put("end_ms", end.coerceAtLeast(start))
                .put("reason", reason)
                .toString()
                .toByteArray(),
        )
    }

    fun moment(dir: File, at: Long, text: String, bookmark: Boolean, jpeg: ByteArray? = null) {
        val j = JSONObject().put("at_ms", at).put("text", text).put("bookmarked", bookmark)
        if (jpeg != null && jpeg.size <= 2 * 1024 * 1024)
            j.put("image_base64", Base64.encodeToString(jpeg, Base64.NO_WRAP))
        enqueue(
            dir,
            "PUT",
            "/api/v1/sessions/${dir.name}/moments/${uuid()}",
            j.toString().toByteArray(),
        )
    }

    @Synchronized
    fun close(dir: File, duration: Long) {
        val m = manifest(dir)
        if (m.optBoolean("closed") || m.optBoolean("deleted")) return
        for (track in listOf("mic", "omi", "playback", "import")) m.put(
            "count_$track",
            nextSequence(dir, track),
        )
        m.put("closed", true).put("duration_ms", maxOf(duration, latestEventEnd(dir)))
        atomic(File(dir, "session.json"), m.toString())
    }

    fun request(
        method: String,
        path: String,
        bytes: ByteArray? = null,
        mime: String = "application/json",
        auth: Boolean = true,
        digest: String? = null,
    ): String {
        val conn = URI(base + path).toURL().openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.requestMethod = method
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        if (auth) conn.setRequestProperty("Authorization", "Bearer ${vault.get("token") ?: ""}")
        conn.setRequestProperty("Content-Type", mime)
        if (digest != null) conn.setRequestProperty("X-Content-SHA256", digest)
        try {
            if (bytes != null) {
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(bytes.size)
                conn.outputStream.use { it.write(bytes) }
            }
            val code = conn.responseCode
            if (code !in 200..299) throw ServerResponseException(code)
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun eventEnd(path: String, bytes: ByteArray?): Long {
        val query = URI(path).rawQuery
        query
            ?.split("&")
            ?.firstOrNull { it.startsWith("end_ms=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?.let {
                return it
            }
        if (bytes != null && ("/moments/" in path || "/gaps/" in path)) {
            val data = JSONObject(String(bytes))
            return maxOf(data.optLong("at_ms"), data.optLong("end_ms"))
        }
        return 0
    }

    @Synchronized
    fun latestEventEnd(dir: File): Long =
        dir.listFiles()
            ?.filter { it.extension in listOf("job", "ack") }
            ?.maxOfOrNull { file ->
                val j = JSONObject(file.readText())
                if (j.has("end_ms")) j.getLong("end_ms")
                else {
                    val body = File(dir, j.getString("body"))
                    eventEnd(j.getString("path"), body.takeIf { it.exists() }?.readBytes())
                }
            } ?: 0

    @Synchronized
    fun acknowledge(dir: File, job: File) {
        val j = JSONObject(job.readText())
        if (!j.has("end_ms")) {
            val body = File(dir, j.getString("body"))
            j.put("end_ms", eventEnd(j.getString("path"), body.takeIf { it.exists() }?.readBytes()))
            atomic(job, j.toString())
        }
        check(job.renameTo(File(dir, job.nameWithoutExtension + ".ack")))
        syncDirectory(dir)
        File(dir, j.getString("body")).delete()
        if (j.getString("path") == "/api/v1/import") File(dir, "source-import").delete()
    }

    @Synchronized
    fun sweepAcknowledged() {
        for (dir in sessions()) for (ack in dir.listFiles()!!.filter { it.extension == "ack" }) {
            val j = JSONObject(ack.readText())
            val body = File(dir, j.getString("body"))
            // Upgrade old acknowledgments before removing the only copy of their event bounds.
            if (!j.has("end_ms")) {
                j.put(
                    "end_ms",
                    eventEnd(j.getString("path"), body.takeIf { it.exists() }?.readBytes()),
                )
                atomic(ack, j.toString())
            }
            body.delete()
            if (j.getString("path") == "/api/v1/import") File(dir, "source-import").delete()
        }
    }

    @Synchronized
    fun recoverInterrupted(dir: File) {
        val m = manifest(dir)
        if (m.optBoolean("closed") || m.optBoolean("deleted")) return
        for (track in listOf("mic", "omi", "playback")) {
            val partial = File(dir, "partial-$track.pcm")
            val meta = File(dir, "partial-$track.json")
            if (partial.exists() && meta.exists()) {
                val j = JSONObject(meta.readText())
                val at = j.getLong("start_ms")
                val raw = partial.readBytes()
                if (raw.isNotEmpty())
                    chunk(
                        dir,
                        track,
                        at,
                        at + raw.size * 1000L / 32000,
                        AudioBytes.wav(raw),
                        j.optInt("seq", nextSequence(dir, track)),
                    )
                partial.delete()
                meta.delete()
            }
        }
        val end = latestEventEnd(dir)
        gap(
            dir,
            "screen",
            end,
            end,
            "App process interrupted; capture did not restart automatically",
        )
        close(dir, end)
    }

    @Synchronized
    private fun deletedAtServer(dir: File) {
        val m =
            manifest(dir).put("deleted", true).put("closed", true).put("title", "Deleted meeting")
        atomic(File(dir, "session.json"), m.toString())
        dir.listFiles()?.filter { it.name != "session.json" }?.forEach { it.deleteRecursively() }
        syncDirectory(dir)
    }

    fun sync(
        send: UploadRequest = { method, path, bytes, mime, digest ->
            request(method, path, bytes, mime, digest = digest)
        }
    ) {
        sweepAcknowledged()
        if (owner == null || vault.get("token").isNullOrEmpty()) {
            status = "Sign in to sync"
            return
        }
        val errors = mutableListOf<String>()
        for (dir in sessions()) {
            val m = manifest(dir)
            if (
                m.getString("owner") != owner ||
                    m.getString("server") != base ||
                    m.optBoolean("uploaded") ||
                    m.optBoolean("deleted")
            )
                continue
            try {
                if (m.optBoolean("is_import")) ensureImportQueued(dir)
                for (job in
                    dir.listFiles()!!.filter { it.extension == "job" }.sortedBy { it.name }) {
                    val j = JSONObject(job.readText())
                    val body = File(dir, j.getString("body"))
                    val bytes = body.readBytes()
                    require(sha(bytes) == j.getString("digest")) { "Local integrity check failed" }
                    val response =
                        send(
                            j.getString("method"),
                            j.getString("path"),
                            bytes,
                            j.getString("mime"),
                            j.getString("digest"),
                        )
                    if (j.getString("path") == "/api/v1/import") {
                        val canonical = JSONObject(response).getString("id")
                        UUID.fromString(canonical)
                        m.put("canonical_id", canonical)
                        atomic(File(dir, "session.json"), m.toString())
                    }
                    acknowledge(dir, job)
                }
                if (m.optBoolean("is_import")) {
                    require(m.optString("canonical_id").isNotBlank()) {
                        "Import needs its canonical meeting response; open the server library or reimport the source"
                    }
                    m.put("uploaded", true)
                    atomic(File(dir, "session.json"), m.toString())
                }
                if (m.optBoolean("closed") && !m.optBoolean("is_import")) {
                    m.put("duration_ms", maxOf(m.optLong("duration_ms"), latestEventEnd(dir)))
                    atomic(File(dir, "session.json"), m.toString())
                    val tracks = JSONObject()
                    for (track in listOf("mic", "omi", "playback", "import")) if (
                        m.optInt("count_$track") > 0
                    )
                        tracks.put(track, m.optInt("count_$track"))
                    send(
                        "POST",
                        "/api/v1/sessions/${dir.name}/finalize",
                        JSONObject()
                            .put("duration_ms", m.getLong("duration_ms"))
                            .put("tracks", tracks)
                            .toString()
                            .toByteArray(),
                        "application/json",
                        null,
                    )
                    m.put("uploaded", true)
                    atomic(File(dir, "session.json"), m.toString())
                }
            } catch (e: ServerResponseException) {
                if (e.code == 410) {
                    deletedAtServer(dir)
                    continue
                }
                if (e.code == 401) throw e
                errors += e.message.orEmpty()
            } catch (e: Exception) {
                errors += e.message ?: "Upload interrupted"
            }
        }
        if (errors.isNotEmpty())
            throw IllegalStateException("${errors.size} session(s) pending: ${errors.first()}")
        status = "Sync complete"
    }

    companion object {
        @Volatile private var instance: Store? = null

        fun get(c: Context) =
            instance
                ?: synchronized(this) {
                    instance ?: Store(c.applicationContext).also { instance = it }
                }
    }
}
