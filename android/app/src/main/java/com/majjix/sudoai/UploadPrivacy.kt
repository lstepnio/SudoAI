package com.majjix.sudoai

import java.io.File
import org.json.JSONObject

// The completion marker is committed only after every pre-upgrade pending manifest is held.
// Retrying a partially completed migration is safe and never removes queue payloads.
internal class UploadPrivacy(
    private val root: File,
    private val write: (File, String) -> Unit = ::atomic,
) {
    private val marker
        get() = File(root, ".upload-hold-v1.complete")

    private fun sessions(): List<File> =
        (root.listFiles() ?: error("Cannot inspect local upload queue"))
            .filter { it.isDirectory && File(it, "session.json").isFile }
            .sortedBy { it.name }

    @Synchronized
    fun migrate() {
        if (marker.exists()) {
            check(marker.readText() == "upload-hold-v1\n") { "Upload privacy marker is invalid" }
            return
        }
        for (dir in sessions()) {
            val file = File(dir, "session.json")
            val m = JSONObject(file.readText())
            if (!m.optBoolean("uploaded") && !m.optBoolean("deleted")) {
                if (!m.optBoolean("upload_held")) write(file, m.put("upload_held", true).toString())
            }
        }
        write(marker, "upload-hold-v1\n")
    }

    @Synchronized
    fun uploadableSessions(owner: String, server: String): List<File> {
        migrate()
        return sessions().filter { dir ->
            val m = JSONObject(File(dir, "session.json").readText())
            m.optString("owner") == owner &&
                m.optString("server") == server &&
                !m.optBoolean("uploaded") &&
                !m.optBoolean("deleted") &&
                !m.optBoolean("upload_held")
        }
    }

    @Synchronized
    fun hold(dir: File, owner: String, server: String) {
        migrate()
        require(dir.canonicalFile.parentFile == root.canonicalFile) { "Unknown local session" }
        val file = File(dir, "session.json")
        val m = JSONObject(file.readText())
        require(m.optString("owner") == owner && m.optString("server") == server) {
            "This session belongs to another sign-in or service"
        }
        require(m.optBoolean("closed") && !m.optBoolean("uploaded") && !m.optBoolean("deleted")) {
            "Only a closed pending session can be held"
        }
        write(file, m.put("upload_held", true).toString())
    }

    @Synchronized
    fun resume(dir: File, owner: String, server: String) {
        migrate()
        require(dir.canonicalFile.parentFile == root.canonicalFile) { "Unknown local session" }
        val file = File(dir, "session.json")
        val m = JSONObject(file.readText())
        require(m.optString("owner") == owner && m.optString("server") == server) {
            "This session belongs to another sign-in or service"
        }
        require(
            m.optBoolean("closed") &&
                m.optBoolean("upload_held") &&
                !m.optBoolean("uploaded") &&
                !m.optBoolean("deleted")
        ) {
            "Session cannot resume upload"
        }
        write(file, m.put("upload_held", false).toString())
    }
}
