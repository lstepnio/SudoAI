package com.majjix.sudoai

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import java.io.File
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private lateinit var store: Store
    private var title by mutableStateOf("Meeting")
    private var mic by mutableStateOf(true)
    private var omi by mutableStateOf(false)
    private var playback by mutableStateOf(false)
    private var screen by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var refresh by mutableStateOf(0)
    private var pendingImport: Uri? = null
    private var exportFile: File? = null
    private var pendingStart by mutableStateOf(false)
    private val startRequest = CaptureStartRequest()
    private var pendingStop by mutableStateOf(false)
    private var pendingPause by mutableStateOf<Boolean?>(null)
    private val permissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            if (!startRequest.pending) return@registerForActivityResult
            if (
                results
                    .filterKeys { it != Manifest.permission.POST_NOTIFICATIONS }
                    .values
                    .all { it }
            )
                consentProjection()
            else {
                pendingStart = false
                startRequest.cancel()
                message =
                    "Required permissions denied. Choose different sources or grant permissions in Settings."
            }
        }
    private val project =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (!startRequest.pending) return@registerForActivityResult
            if (result.resultCode == Activity.RESULT_OK && result.data != null)
                runCatching { startCapture(result.data) }
                    .onFailure {
                        pendingStart = false
                        startRequest.cancel()
                        showError(it)
                    }
            else {
                pendingStart = false
                startRequest.cancel()
                message = "Screen sharing declined. No session started."
            }
        }
    private val pick =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let(::importFile)
        }
    private val export =
        registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
            val file = exportFile
            if (uri != null && file != null)
                store.executor.execute {
                    runCatching {
                            require(
                                file.parentFile?.let { it in store.visibleSessions() } == true
                            ) {
                                "This recording belongs to another sign-in"
                            }
                            contentResolver.openOutputStream(uri)!!.use { output ->
                                file.inputStream().use { it.copyTo(output) }
                            }
                        }
                        .onSuccess { runOnUiThread { message = "Local audio exported" } }
                        .onFailure { showError(it) }
                }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingStop = savedInstanceState?.getBoolean("pending_stop") ?: false
        savedInstanceState?.let {
            title = it.getString("capture_title", "Meeting")
            mic = it.getBoolean("capture_mic", true)
            omi = it.getBoolean("capture_omi", false)
            playback = it.getBoolean("capture_playback", false)
            screen = it.getBoolean("capture_screen", false)
        }
        store = Store.get(this)
        scheduleUploads(this)
        store.executor.execute { runCatching { recoverInterrupted() }.onFailure { showError(it) } }
        handleIntent(intent)
        setContent {
            MaterialTheme(
                colorScheme =
                    darkColorScheme(
                        primary = Color(0xFF89D8BF),
                        secondary = Color(0xFFB5C6F0),
                        background = Color(0xFF10191D),
                        surface = Color(0xFF19272D),
                    )
            ) {
                var tick by remember { mutableIntStateOf(0) }
                var server by remember { mutableStateOf(store.base) }
                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        if (CaptureService.active) pendingStart = false
                        if (!CaptureService.active) {
                            pendingStop = false
                            pendingPause = null
                            if (CaptureService.snapshot.startsWith("Capture could not start"))
                                pendingStart = false
                        }
                        pendingPause?.let { expectedPaused ->
                            if (
                                CaptureService.snapshot.startsWith(
                                    if (expectedPaused) "Paused" else "Recording"
                                )
                            )
                                pendingPause = null
                        }
                        tick++
                    }
                }
                val active = CaptureService.active || pendingStart || pendingStop
                val status = CaptureService.snapshot
                val sessions = remember(tick, refresh) { store.visibleSessions() }
                Surface(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.statusBarsPadding()
                            .navigationBarsPadding()
                            .verticalScroll(rememberScrollState())
                            .padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        Text("SudoAI", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            "Your meeting memory",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            "Pixel pilot · Android 13+",
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Card {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text(
                                    if (store.owner == null) "Sign in to capture"
                                    else
                                        "Signed in: ${store.vault.get("name").orEmpty().ifBlank { store.owner.orEmpty() }}",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                if (store.owner == null) {
                                    OutlinedTextField(
                                        server,
                                        { server = it },
                                        label = { Text("HTTPS server") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth(),
                                    )
                                    Button(
                                        onClick = {
                                            runCatching {
                                                    store.base = server
                                                    browser(store.authStart())
                                                }
                                                .onFailure { message = it.message.orEmpty() }
                                        }
                                    ) {
                                        Text("Sign in with browser")
                                    }
                                } else
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { browser(store.base) }) {
                                            Text("Open library")
                                        }
                                        TextButton(
                                            enabled = !active,
                                            onClick = {
                                                store.vault.clear()
                                                refresh++
                                                message =
                                                    "Sign-in forgotten locally. Remote token was not revoked."
                                            },
                                        ) {
                                            Text("Forget local sign-in")
                                        }
                                        OutlinedButton(
                                            enabled = !active,
                                            onClick = { browser(store.authStart()) },
                                        ) {
                                            Text("Sign in again")
                                        }
                                        OutlinedButton(
                                            enabled = !active,
                                            onClick = {
                                                store.executor.execute {
                                                    runCatching { store.revoke() }
                                                        .onSuccess {
                                                            runOnUiThread {
                                                                refresh++
                                                                message = store.status
                                                            }
                                                        }
                                                        .onFailure(::showError)
                                                }
                                            },
                                        ) {
                                            Text("Revoke sign-in")
                                        }
                                    }
                                Text(store.status, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Card {
                            Column(
                                Modifier.padding(16.dp),
                                verticalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                Text("Capture sources", style = MaterialTheme.typography.titleLarge)
                                OutlinedTextField(
                                    title,
                                    { title = it },
                                    label = { Text("Meeting title") },
                                    enabled = !active,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Source(
                                    "Phone microphone",
                                    "Room audio, including speakerphone when audible. Android can silence it during calls.",
                                    mic,
                                    !active,
                                ) {
                                    mic = it
                                }
                                Source(
                                    "Omi via Bluetooth",
                                    "Connect explicitly after Start. DevKit and CV1 Opus are negotiated.",
                                    omi,
                                    !active,
                                ) {
                                    omi = it
                                }
                                Source(
                                    "Eligible app playback",
                                    "Media and games only when the source app permits it. Calls and Teams remote audio are not available.",
                                    playback,
                                    !active,
                                ) {
                                    playback = it
                                }
                                Source(
                                    "Screen moments + local OCR",
                                    "System consent each session. Saves changed screen text and JPEG every 15 seconds. Protected screens may be blank.",
                                    screen,
                                    !active,
                                ) {
                                    screen = it
                                }
                                Text(
                                    "Record only with the participants' consent. The app never places calls.",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                Button(
                                    enabled =
                                        !active &&
                                            store.owner != null &&
                                            (mic || omi || playback || screen),
                                    onClick = ::requestStart,
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Text("Start session")
                                }
                                // Keep these controls in distinct, permanent slots. A stale Stop
                                // tap
                                // must never land on a Start button after state changes.
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(
                                        enabled =
                                            CaptureService.active &&
                                                !pendingStop &&
                                                pendingPause == null,
                                        onClick = { command("pause") },
                                    ) {
                                        Text(if (status.startsWith("Paused")) "Resume" else "Pause")
                                    }
                                    OutlinedButton(
                                        enabled = CaptureService.active && !pendingStop,
                                        onClick = { command("bookmark") },
                                    ) {
                                        Text("Bookmark")
                                    }
                                    Button(
                                        enabled = CaptureService.active && !pendingStop,
                                        onClick = { command("stop") },
                                    ) {
                                        Text(if (pendingStop) "Stopping" else "Stop")
                                    }
                                }
                                Text(if (pendingStart) "Starting session" else status)
                                if (omi)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(onClick = { command("connect") }) {
                                            Text("Connect Omi")
                                        }
                                        OutlinedButton(onClick = { command("disconnect") }) {
                                            Text("Disconnect")
                                        }
                                    }
                            }
                        }
                        if (message.isNotEmpty())
                            Text(message, color = MaterialTheme.colorScheme.primary)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                enabled = store.owner != null,
                                onClick = {
                                    pick.launch(
                                        arrayOf(
                                            "audio/*",
                                            "text/vtt",
                                            "text/plain",
                                            "application/octet-stream",
                                        )
                                    )
                                },
                            ) {
                                Text("Import audio / VTT")
                            }
                            OutlinedButton(
                                onClick = {
                                    store.executor.execute {
                                        runCatching { store.sync() }.onFailure(::showError)
                                        runOnUiThread { refresh++ }
                                    }
                                }
                            ) {
                                Text("Sync now")
                            }
                        }
                        Text("On this phone", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "Held sessions stay on this phone until you choose Resume upload. New sessions upload automatically. Acknowledged payloads are removed. History is scoped to this account and server.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (sessions.isEmpty()) Text("No local sessions yet")
                        sessions.forEach { dir ->
                            val m = runCatching { store.manifest(dir) }.getOrNull()
                            if (m != null)
                                Card {
                                    Column(
                                        Modifier.padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp),
                                    ) {
                                        Text(
                                            m.optString("title"),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Text(
                                            m.optString("started_at"),
                                            style = MaterialTheme.typography.bodySmall,
                                        )
                                        val bound =
                                            m.optString("owner") == store.owner &&
                                                m.optString("server") == store.base
                                        val jobs =
                                            dir.listFiles()?.count { it.extension == "job" } ?: 0
                                        Text(
                                            if (!bound) "Held for original account / server"
                                            else if (m.optBoolean("deleted")) "Deleted at server"
                                            else if (m.optBoolean("uploaded"))
                                                "Uploaded · local payloads removed"
                                            else if (m.optBoolean("upload_held"))
                                                "Upload held on this phone · $jobs items retained"
                                            else if (CaptureService.activeId == dir.name)
                                                "Recording · $jobs uploads pending"
                                            else
                                                "$jobs uploads pending · ${m.optLong("duration_ms")/1000}s"
                                        )
                                        if (m.optBoolean("upload_held")) {
                                            Text(
                                                "Session: ${dir.name}",
                                                style = MaterialTheme.typography.bodySmall,
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            if (
                                                bound &&
                                                    m.optBoolean("upload_held") &&
                                                    m.optBoolean("closed") &&
                                                    !m.optBoolean("deleted") &&
                                                    !m.optBoolean("uploaded")
                                            )
                                                TextButton(
                                                    onClick = {
                                                        store.executor.execute {
                                                            runCatching {
                                                                    store.resumeUpload(dir)
                                                                    store.sync()
                                                                }
                                                                .onFailure(::showError)
                                                            runOnUiThread { refresh++ }
                                                        }
                                                    }
                                                ) {
                                                    Text("Resume upload")
                                                }
                                            store.meetingUrl(dir)?.let { url ->
                                                TextButton(onClick = { browser(url) }) {
                                                    Text("Open meeting")
                                                }
                                            }
                                            val wav =
                                                dir.listFiles()
                                                    ?.filter {
                                                        it.name.startsWith("200-") &&
                                                            it.extension == "bin"
                                                    }
                                                    ?.sortedBy { it.name }
                                                    ?.firstOrNull()
                                            if (wav != null)
                                                TextButton(
                                                    onClick = {
                                                        exportFile = wav
                                                        export.launch(
                                                            "${m.optString("title")}-${wav.nameWithoutExtension}.wav"
                                                        )
                                                    }
                                                ) {
                                                    Text("Export first chunk")
                                                }
                                        }
                                    }
                                }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                }
            }
        }
    }

    @Composable
    private fun Source(
        name: String,
        description: String,
        checked: Boolean,
        enabled: Boolean,
        onChange: (Boolean) -> Unit,
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Checkbox(checked, onChange, enabled = enabled)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(description, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    private fun requestStart() {
        if (CaptureService.active || pendingStart || pendingStop) return
        if (!startRequest.begin()) return
        pendingStart = true
        runCatching { requestStartPermissions() }
            .onFailure {
                pendingStart = false
                startRequest.cancel()
                showError(it)
            }
    }

    private fun requestStartPermissions() {
        val needed = mutableListOf<String>()
        if (mic || playback) needed += Manifest.permission.RECORD_AUDIO
        if (omi)
            needed +=
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
        // Notifications are optional for capture; Android still surfaces the foreground service.
        if (
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        )
            needed += Manifest.permission.POST_NOTIFICATIONS
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) consentProjection() else permissions.launch(missing.toTypedArray())
    }

    private fun consentProjection() {
        if (!startRequest.pending || CaptureService.active || pendingStop) return
        runCatching {
                if (screen || playback)
                    project.launch(
                        getSystemService(MediaProjectionManager::class.java)
                            .createScreenCaptureIntent()
                    )
                else startCapture(null)
            }
            .onFailure {
                pendingStart = false
                startRequest.cancel()
                showError(it)
            }
    }

    private fun startCapture(data: Intent?) {
        if (!startRequest.consume(canStart = !pendingStop && !CaptureService.active)) return
        val i =
            Intent(this, CaptureService::class.java)
                .setAction("start")
                .putExtra("title", title)
                .putExtra("mic", mic)
                .putExtra("omi", omi)
                .putExtra("playback", playback)
                .putExtra("screen", screen)
        if (data != null) i.putExtra("projection", data)
        try {
            startForegroundService(i)
        } catch (e: Exception) {
            pendingStart = false
            throw e
        }
        message = ""
    }

    private fun command(action: String) {
        if (!CaptureService.active || pendingStop) return
        if (action == "stop") {
            pendingStop = true
            pendingStart = false
            startRequest.cancel()
        }
        if (action == "pause") {
            if (pendingPause != null) return
            pendingPause = !CaptureService.snapshot.startsWith("Paused")
        }
        runCatching { startService(Intent(this, CaptureService::class.java).setAction(action)) }
            .onFailure {
                if (action == "stop") pendingStop = false
                if (action == "pause") pendingPause = null
                showError(it)
            }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("pending_stop", pendingStop)
        outState.putString("capture_title", title)
        outState.putBoolean("capture_mic", mic)
        outState.putBoolean("capture_omi", omi)
        outState.putBoolean("capture_playback", playback)
        outState.putBoolean("capture_screen", screen)
        super.onSaveInstanceState(outState)
    }

    private fun browser(url: String) {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }

    private fun showError(e: Throwable) {
        runOnUiThread {
            message = e.message ?: "Operation failed; local data retained"
            refresh++
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val uri = intent.data
        if (uri?.scheme == "sudoai" && uri.host == "auth" && uri.path == "/callback") {
            val code = uri.getQueryParameter("code")
            val state = uri.getQueryParameter("state")
            if (code != null && state != null)
                store.executor.execute {
                    runCatching {
                            store.exchange(code, state)
                            store.sync()
                        }
                        .onSuccess {
                            runOnUiThread {
                                message = "Signed in"
                                refresh++
                                pendingImport?.let {
                                    pendingImport = null
                                    importFile(it)
                                }
                            }
                        }
                        .onFailure(::showError)
                }
            else message = "Sign-in did not return a code"
        }
        if (intent.action == Intent.ACTION_SEND)
            intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)?.let(::importFile)
    }

    private fun importFile(uri: Uri) {
        if (store.owner == null) {
            pendingImport = uri
            message = "Sign in to import this file"
            return
        }
        store.executor.execute {
            var importDirectory: File? = null
            runCatching {
                    val name =
                        contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { if (it.moveToFirst()) it.getString(0) else null }
                            ?: "Imported recording"
                    val mime =
                        contentResolver.getType(uri)
                            ?: if (name.endsWith(".vtt", true)) "text/vtt"
                            else "application/octet-stream"
                    require(mime.startsWith("audio/") || name.endsWith(".vtt", true)) {
                        "Choose an audio file or .vtt transcript"
                    }
                    val dir = File(store.root, uuid()).apply { mkdirs() }
                    importDirectory = dir
                    val local = File(dir, "source-import")
                    contentResolver.openInputStream(uri)!!.use { input ->
                        local.outputStream().use { output ->
                            val buffer = ByteArray(65536)
                            var total = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                require(total <= 64 * 1024 * 1024) {
                                    "Pilot import limit is 64 MiB; split larger files first"
                                }
                                output.write(buffer, 0, n)
                            }
                        }
                    }
                    store.queueImport(dir, name, mime)
                    runOnUiThread { message = "Import saved locally; upload queued" }
                    scheduleUploads(this)
                    store.sync()
                }
                .onSuccess {
                    runOnUiThread {
                        refresh++
                        message = "Import uploaded. Open its meeting from history."
                    }
                }
                .onFailure { error ->
                    importDirectory
                        ?.takeIf { !File(it, "session.json").exists() }
                        ?.deleteRecursively()
                    showError(error)
                }
        }
    }

    private fun recoverInterrupted() {
        store.sweepAcknowledged()
        if (CaptureService.active) return
        store.sessions().forEach { store.recoverInterrupted(it) }
    }
}
