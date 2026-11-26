package com.majjix.sudoai

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.*
import android.media.projection.*
import android.os.*
import androidx.core.app.NotificationCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

@SuppressLint("MissingPermission")
class CaptureService : Service() {
    private lateinit var store: Store
    private var dir: File? = null
    private var started = 0L
    @Volatile private var running = false
    @Volatile private var stopping = false
    private var finalized = false
    private var lastCommandId = 0
    @Volatile private var paused = false
    private var pauseAt = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val workers = Executors.newCachedThreadPool()
    private val recorders = mutableListOf<AudioRecord>()
    private val chunks = ConcurrentHashMap<String, PcmChunks>()
    private val gaps = ConcurrentHashMap<String, Pair<Long, String>>()
    private val statuses = ConcurrentHashMap<String, String>()
    private val silenced = ConcurrentHashMap.newKeySet<String>()
    private var projection: MediaProjection? = null
    @Volatile private var projectionLive = false
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var omi: OmiBle? = null
    private var recognizer: com.google.mlkit.vision.text.TextRecognizer? = null
    private var lastScreen = 0L
    private var lastHash = ""
    private var ocrBusy = false
    private var bookmark = false
    private var screenEnabled = false
    private var playbackEnabled = false
    private var micEnabled = true
    private var omiEnabled = false
    private var locked = false
    private var wake: PowerManager.WakeLock? = null
    private val screenReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                locked =
                    intent.action == Intent.ACTION_SCREEN_OFF ||
                        getSystemService(KeyguardManager::class.java).isKeyguardLocked
                if (screenEnabled) {
                    val reason = projectionInterruption(projectionLive, locked, paused)
                    if (reason != null) projectionGap("screen", reason)
                    else if (gaps["screen"]?.second == "Screen locked; no screen moments")
                        gapEnd("screen")
                }
                publish()
            }
        }

    private fun now() = (SystemClock.elapsedRealtime() - started).coerceAtLeast(0)

    override fun onBind(intent: Intent?) = null

    override fun onCreate() {
        super.onCreate()
        store = Store.get(this)
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(
                NotificationChannel("capture", "Active capture", NotificationManager.IMPORTANCE_LOW)
            )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        lastCommandId = startId
        when (intent?.action) {
            "stop" -> stopCapture()
            "pause" -> togglePause()
            "bookmark" -> addBookmark()
            "connect" ->
                if (running && omiEnabled)
                    runCatching { omi?.connect() }
                        .onFailure { gapStart("omi", it.message ?: "Omi failed") }
            "disconnect" -> omi?.disconnect()
            "start" -> {
                val authorized =
                    CaptureStartAuthorization.process.consume(
                        intent.getStringExtra(CaptureStartAuthorization.EXTRA),
                        canStart = !running && !stopping,
                    )
                android.util.Log.i(
                    "SudoAICapture",
                    "start_${if (authorized) "accepted" else "rejected"} instance=${System.identityHashCode(this)} command=$startId",
                )
                if (!authorized) rejectedStarts++
                if (authorized)
                    runCatching { startCapture(intent) }
                        .onFailure { error ->
                            if (!running) nonRecordingForeground()
                            snapshot = "Capture could not start: ${error.message}"
                            stopCapture()
                        }
                else if (!running) {
                    // startForegroundService still imposes a promotion obligation on a
                    // rejected request. Fulfill it without opening any capture source.
                    nonRecordingForeground()
                    if (!stopping) {
                        snapshot =
                            "Capture could not start: request expired or was already used. Confirm Start again."
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelfResult(startId)
                    }
                }
            }
        }
        if (stopping && finalized) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(startId)
        }
        return START_NOT_STICKY
    }

    private fun nonRecordingForeground() {
        val notification =
            NotificationCompat.Builder(this, "capture")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setContentTitle("SudoAI recording not started")
                .setContentText("The Start request was rejected. No capture source was opened.")
                .setOnlyAlertOnce(true)
                .build()
        val type =
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE
            else 0
        startForeground(10, notification, type)
    }

    private fun notification(): Notification {
        fun action(label: String, value: String, request: Int) =
            NotificationCompat.Action.Builder(
                    0,
                    label,
                    PendingIntent.getService(
                        this,
                        request,
                        Intent(this, CaptureService::class.java).setAction(value),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                .build()
        return NotificationCompat.Builder(this, "capture")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle(if (paused) "SudoAI paused" else "SudoAI recording")
            .setContentText(
                "${now()/1000}s • ${statuses.entries.joinToString { "${it.key}: ${it.value}" }}"
            )
            .setContentIntent(
                PendingIntent.getActivity(
                    this,
                    0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE,
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(action(if (paused) "Resume" else "Pause", "pause", 1))
            .addAction(action("Bookmark", "bookmark", 2))
            .addAction(action("Stop", "stop", 3))
            .build()
    }

    private fun startCapture(intent: Intent) {
        require(store.owner != null) { "Sign in first" }
        micEnabled = intent.getBooleanExtra("mic", true)
        omiEnabled = intent.getBooleanExtra("omi", false)
        screenEnabled = intent.getBooleanExtra("screen", false)
        playbackEnabled = intent.getBooleanExtra("playback", false)
        require(micEnabled || omiEnabled || screenEnabled || playbackEnabled) {
            "Choose a capture source"
        }
        var type = 0
        if (micEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (omiEnabled) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (screenEnabled || playbackEnabled)
            type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        locked = getSystemService(KeyguardManager::class.java).isKeyguardLocked
        started = SystemClock.elapsedRealtime()
        startForeground(10, notification(), type)
        dir = store.create(intent.getStringExtra("title")?.ifBlank { "Meeting" } ?: "Meeting")
        activeId = dir!!.name
        running = true
        active = true
        wake =
            getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SudoAI:capture")
                .apply { acquire(6 * 60 * 60 * 1000L) }
        registerReceiver(
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            RECEIVER_NOT_EXPORTED,
        )
        if (micEnabled) startAudio("mic")
        if (omiEnabled) {
            gapStart("omi", "Not connected. Tap Connect Omi.")
            omi =
                OmiBle(
                    this,
                    { s ->
                        statuses["omi"] = s
                        if (s.startsWith("Omi connected")) gapEnd("omi")
                        publish()
                    },
                    { pcm ->
                        if (running && !paused) {
                            gapEnd("omi")
                            writer("omi").append(pcm)
                        }
                    },
                    { reason ->
                        if (running) {
                            chunks["omi"]?.flush()
                            gapStart("omi", reason)
                            publish()
                        }
                    },
                )
        }
        if (screenEnabled || playbackEnabled) {
            val data =
                if (Build.VERSION.SDK_INT >= 33)
                    intent.getParcelableExtra("projection", Intent::class.java)
                else @Suppress("DEPRECATION") intent.getParcelableExtra("projection")
            require(data != null) { "Screen consent missing" }
            projection =
                getSystemService(MediaProjectionManager::class.java)
                    .getMediaProjection(Activity.RESULT_OK, data)
            projectionLive = true
            projection!!.registerCallback(
                object : MediaProjection.Callback() {
                    override fun onStop() {
                        if (running) {
                            projectionLive = false
                            projection = null
                            releaseScreenResources()
                            if (screenEnabled) projectionGap("screen", PROJECTION_REVOKED)
                            if (playbackEnabled) projectionGap("playback", PROJECTION_REVOKED)
                            publish()
                        }
                    }
                },
                handler,
            )
            if (screenEnabled) startScreen()
            if (playbackEnabled) startAudio("playback")
        }
        handler.post(ticker)
        publish()
    }

    private fun writer(track: String) =
        chunks.getOrPut(track) { PcmChunks(store, dir!!, track, ::now) }

    private fun startAudio(track: String) {
        try {
            val format =
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(16000)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            val min =
                AudioRecord.getMinBufferSize(
                        16000,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT,
                    )
                    .coerceAtLeast(6400)
            val builder = AudioRecord.Builder().setAudioFormat(format).setBufferSizeInBytes(min * 2)
            if (track == "mic") builder.setAudioSource(MediaRecorder.AudioSource.MIC)
            else
                builder.setAudioPlaybackCaptureConfig(
                    AudioPlaybackCaptureConfiguration.Builder(projection!!)
                        .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                        .addMatchingUsage(AudioAttributes.USAGE_GAME)
                        .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                        .build()
                )
            val record = builder.build()
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                record.release()
                error("Audio input unavailable")
            }
            synchronized(recorders) { recorders.add(record) }
            captureWorker(
                workers,
                start = {
                    record.registerAudioRecordingCallback(
                        mainExecutor,
                        object : AudioManager.AudioRecordingCallback() {
                            override fun onRecordingConfigChanged(
                                configs: MutableList<AudioRecordingConfiguration>
                            ) {
                                configs
                                    .firstOrNull {
                                        it.clientAudioSessionId == record.audioSessionId
                                    }
                                    ?.let { config ->
                                        if (config.isClientSilenced) {
                                            silenced.add(track)
                                            chunks[track]?.flush()
                                            gapStart(
                                                track,
                                                "Android silenced this input; another app or call may own the microphone",
                                            )
                                        } else {
                                            silenced.remove(track)
                                            if (
                                                gaps[track]
                                                    ?.second
                                                    ?.startsWith("Android silenced") == true
                                            )
                                                gapEnd(track)
                                        }
                                        publish()
                                    }
                            }
                        },
                    )
                    record.startRecording()
                    statuses[track] =
                        if (track == "mic") "Phone microphone / room audio"
                        else "Eligible app playback only; app may block capture"
                },
                release = {
                    synchronized(recorders) { recorders.remove(record) }
                    runCatching { record.stop() }
                    record.release()
                    runCatching { chunks[track]?.flush() }
                        .onFailure { gapStart(track, "Local save failed: ${it.message}") }
                },
            ) {
                val buffer = ByteArray(min)
                try {
                    while (running) {
                        val count = record.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
                        if (count < 0) error("Audio read failed ($count)")
                        if (
                            running &&
                                !paused &&
                                count > 0 &&
                                track !in silenced &&
                                !(track == "playback" && (!projectionLive || gaps[track] != null))
                        )
                            writer(track).append(buffer.copyOf(count))
                    }
                } catch (e: Exception) {
                    if (running) gapStart(track, e.message ?: "Audio interrupted")
                }
            }
        } catch (e: Exception) {
            gapStart(track, e.message ?: "Source unavailable")
        }
    }

    private fun gapStart(track: String, reason: String) {
        if (running && !projectionLive && (track == "screen" || track == "playback")) {
            projectionGap(track, PROJECTION_REVOKED)
            return
        }
        gaps.putIfAbsent(track, now() to reason)
        statuses[track] = reason
    }

    private fun gapEnd(track: String, end: Long = now()) {
        if (running && (track == "screen" || track == "playback")) {
            val reason = projectionInterruption(projectionLive, track == "screen" && locked, paused)
            if (reason != null) {
                projectionGap(track, reason)
                return
            }
        }
        gaps.remove(track)?.let { (at, reason) ->
            dir?.let { store.gap(it, track, at, end, reason) }
        }
        statuses[track] = "Capturing"
    }

    private fun projectionGap(track: String, reason: String) {
        // Persist changes of cause so a paused/locked interval cannot disguise revocation.
        gaps.compute(track) { _, previous ->
            if (previous != null && previous.second != reason) {
                dir?.let { store.gap(it, track, previous.first, now(), previous.second) }
            }
            if (previous?.second == reason) previous else now() to reason
        }
        statuses[track] = reason
    }

    private fun togglePause() {
        if (!running) return
        paused = !paused
        if (paused) {
            pauseAt = now()
            chunks.values.forEach { it.flush() }
            statuses.keys.forEach { gapStart(it, "Paused by user") }
        } else {
            gaps.entries.filter { it.value.second == "Paused by user" }.forEach { gapEnd(it.key) }
            silenced.forEach { gapStart(it, "Android silenced this input") }
        }
        for (track in listOf("screen", "playback")) {
            if ((track == "screen" && screenEnabled) || (track == "playback" && playbackEnabled)) {
                projectionInterruption(projectionLive, track == "screen" && locked, paused)?.let {
                    projectionGap(track, it)
                }
            }
        }
        publish()
    }

    private fun startScreen() {
        recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val metrics = resources.displayMetrics
        val width = 720
        val height = (width * metrics.heightPixels.toFloat() / metrics.widthPixels).toInt()
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader!!.setOnImageAvailableListener(
            { r ->
                if (!running || !projectionLive || reader !== r) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                try {
                    if (
                        !running ||
                            projectionInterruption(projectionLive, locked, paused) != null ||
                            ocrBusy ||
                            (!bookmark && now() - lastScreen < 15000)
                    )
                        return@setOnImageAvailableListener
                    val plane = image.planes[0]
                    val stride = plane.rowStride / plane.pixelStride
                    val padded = Bitmap.createBitmap(stride, image.height, Bitmap.Config.ARGB_8888)
                    padded.copyPixelsFromBuffer(plane.buffer)
                    val bitmap = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
                    if (bitmap !== padded) padded.recycle()
                    val at = now()
                    lastScreen = at
                    ocrBusy = true
                    val marked = bookmark
                    bookmark = false
                    recognizer!!
                        .process(InputImage.fromBitmap(bitmap, 0))
                        .addOnSuccessListener { result ->
                            if (
                                !running ||
                                    projectionInterruption(projectionLive, locked, paused) != null
                            )
                                return@addOnSuccessListener
                            val text = result.text
                            val hash =
                                sha(
                                    text
                                        .lowercase()
                                        .replace(Regex("\\s+"), " ")
                                        .trim()
                                        .toByteArray()
                                )
                            if ((text.isNotBlank() && hash != lastHash) || marked) {
                                val jpg = ByteArrayOutputStream()
                                bitmap.compress(Bitmap.CompressFormat.JPEG, 65, jpg)
                                dir?.let {
                                    store.moment(
                                        it,
                                        at,
                                        text.ifBlank { "Screen bookmark" },
                                        marked,
                                        jpg.toByteArray(),
                                    )
                                }
                                lastHash = hash
                            }
                            gapEnd("screen")
                        }
                        .addOnFailureListener {
                            if (
                                running &&
                                    projectionInterruption(projectionLive, locked, paused) == null
                            )
                                gapStart("screen", "Local OCR failed: ${it.message}")
                        }
                        .addOnCompleteListener {
                            bitmap.recycle()
                            ocrBusy = false
                            if (!projectionLive) releaseScreenResources()
                            if (running) publish()
                        }
                } finally {
                    image.close()
                }
            },
            handler,
        )
        display =
            projection!!.createVirtualDisplay(
                "SudoAI moments",
                width,
                height,
                metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                reader!!.surface,
                null,
                handler,
            )
        statuses["screen"] = "Local OCR every 15s; changed screens only"
    }

    private fun releaseScreenResources() {
        display?.release()
        display = null
        reader?.setOnImageAvailableListener(null, null)
        reader?.close()
        reader = null
        // ML Kit may still be reading its bitmap. Close only after its completion callback.
        if (!ocrBusy) {
            recognizer?.close()
            recognizer = null
        }
    }

    private fun addBookmark() {
        if (!running) return
        if (
            screenEnabled &&
                projectionLive &&
                !locked &&
                projection != null &&
                gaps["screen"] == null
        ) {
            bookmark = true
            lastScreen = 0
        } else dir?.let { store.moment(it, now(), "Bookmark", true) }
    }

    private val ticker =
        object : Runnable {
            override fun run() {
                if (running) {
                    if (dir?.let { store.manifest(it).optBoolean("deleted") } == true) {
                        stopCapture()
                        return
                    }
                    publish()
                    handler.postDelayed(this, 1000)
                }
            }
        }

    private fun publish() {
        snapshot =
            (if (paused) "Paused" else "Recording") +
                " • ${now()/1000}s\n" +
                statuses.entries.joinToString("\n") { "${it.key}: ${it.value}" }
        if (running) getSystemService(NotificationManager::class.java).notify(10, notification())
    }

    private fun stopCapture() {
        android.util.Log.i(
            "SudoAICapture",
            "stop instance=${System.identityHashCode(this)} running=$running stopping=$stopping",
        )
        CaptureStartAuthorization.process.cancel()
        if (stopping) return
        if (!running) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelfResult(lastCommandId)
            return
        }
        stopping = true
        running = false
        projectionLive = false
        val duration = now()
        handler.removeCallbacks(ticker)
        omi?.disconnect(false)
        omi = null
        synchronized(recorders) {
            recorders.forEach { runCatching { it.stop() } }
            recorders.clear()
        }
        releaseScreenResources()
        projection?.stop()
        projection = null
        runCatching { unregisterReceiver(screenReceiver) }
        if (wake?.isHeld == true) wake?.release()
        // Capture workers finish flushing before finalization can be enqueued.
        workers.shutdown()
        Thread {
                val result = runCatching {
                    workers.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)
                    chunks.values.forEach { it.flush() }
                    gaps.keys.toList().forEach { gapEnd(it, duration) }
                    dir?.let { store.close(it, duration) }
                    store.executor.execute { runCatching { store.sync() } }
                }
                handler.post {
                    finalized = true
                    snapshot =
                        if (result.isSuccess)
                            "Stopped. Audio saved locally; uploads retry automatically."
                        else
                            "Local save interrupted: ${result.exceptionOrNull()?.message}. Keep app data for recovery."
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    // Never tear down a service record with a newer command still queued.
                    stopSelfResult(lastCommandId)
                }
            }
            .start()
        snapshot = "Saving final audio chunks…"
    }

    override fun onDestroy() {
        if (running) stopCapture()
        // Keep Start disabled until this stopping instance is actually gone. Its worker
        // pool has been shut down and must never be reused by a new start command.
        active = false
        activeId = null
        super.onDestroy()
    }

    companion object {
        @Volatile internal var rejectedStarts = 0L
        @Volatile var active = false
        @Volatile var activeId: String? = null
        @Volatile var snapshot = "Ready"
    }
}
