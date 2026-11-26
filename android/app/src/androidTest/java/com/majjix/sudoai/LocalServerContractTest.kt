package com.majjix.sudoai

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayInputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.CertificateFactory
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Opt-in integration with qa/android_contract.py's disposable HTTPS FastAPI server. */
@RunWith(AndroidJUnit4::class)
class LocalServerContractTest {
    @Test
    fun actualClientUploadsAndOpensCanonicalImportedMeeting() {
        val args = InstrumentationRegistry.getArguments()
        val endpoint = args.getString("server_url")
        assumeTrue("Run through qa/android_contract.py", endpoint != null)
        require(endpoint!!.startsWith("https://localhost:"))
        val cert =
            CertificateFactory.getInstance("X.509")
                .generateCertificate(
                    ByteArrayInputStream(Base64.decode(args.getString("server_ca"), Base64.DEFAULT))
                )
        val trust =
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null)
                setCertificateEntry("temporary-test-server", cert)
            }
        val managers =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply {
                init(trust)
            }
        val ssl = SSLContext.getInstance("TLS").apply { init(null, managers.trustManagers, null) }
        val originalSsl = HttpsURLConnection.getDefaultSSLSocketFactory()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = Store.get(context)
        val dirs = mutableListOf<File>()
        store.vault.clear()
        store.base = endpoint
        store.vault.put("owner", "synthetic-android-contract")
        store.vault.put("token", requireNotNull(args.getString("synthetic_token")))
        try {
            // Trust only this launcher's temporary certificate; normal hostname verification
            // remains enabled.
            // This override exists solely in the instrumentation process and is restored below.
            HttpsURLConnection.setDefaultSSLSocketFactory(ssl.socketFactory)
            assertEquals(
                "synthetic-android-contract",
                JSONObject(store.request("GET", "/api/v1/me")).getString("id"),
            )
            val capture = store.create("Invented Android HTTPS contract capture").also(dirs::add)
            store.chunk(capture, "mic", 0, 100, AudioBytes.wav(ByteArray(3200)))
            store.moment(capture, 80, "The invented reserve code is QUARTZ-62.", true)
            store.gap(capture, "omi", 20, 30, "Synthetic disconnected interval")
            store.close(capture, 100)
            store.sync()
            assertTrue(store.manifest(capture).getBoolean("uploaded"))
            assertFalse(capture.listFiles()!!.any { it.extension in listOf("job", "bin") })
            val meeting = JSONObject(store.request("GET", "/api/v1/sessions/${capture.name}"))
            assertEquals(100, meeting.getLong("duration_ms"))
            assertEquals(1, meeting.getJSONArray("gaps").length())
            val screen = meeting.getJSONArray("evidence").getJSONObject(0)
            assertEquals("screen", screen.getString("kind"))
            assertTrue(screen.getString("text").contains("QUARTZ-62"))
            val hits =
                JSONObject(
                        store.request("GET", "/api/v1/search?q=quartz&session_id=${capture.name}")
                    )
                    .getJSONArray("results")
            assertEquals(screen.getString("id"), hits.getJSONObject(0).getString("id"))
            val vtt =
                "WEBVTT\n\n00:00:00.000 --> 00:00:01.000\nThe invented pilot uses 11 panels.\n"
            fun import(): File {
                val dir = File(store.root, uuid()).apply { mkdirs() }.also(dirs::add)
                File(dir, "source-import").writeText(vtt)
                store.queueImport(dir, "synthetic.vtt", "text/vtt")
                store.sync()
                assertTrue(store.manifest(dir).getBoolean("uploaded"))
                assertFalse(
                    dir.listFiles()!!.any { it.extension == "bin" || it.name == "source-import" }
                )
                return dir
            }
            val first = import()
            val duplicate = import()
            val canonical = store.manifest(first).getString("canonical_id")
            assertEquals(canonical, store.manifest(duplicate).getString("canonical_id"))
            assertEquals("$endpoint/sessions/$canonical", store.meetingUrl(duplicate))
            val imported = JSONObject(store.request("GET", "/api/v1/sessions/$canonical"))
            assertTrue(
                imported
                    .getJSONArray("evidence")
                    .getJSONObject(0)
                    .getString("text")
                    .contains("11 panels")
            )
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(originalSsl)
            dirs.forEach { it.deleteRecursively() }
            store.vault.clear()
        }
    }
}
