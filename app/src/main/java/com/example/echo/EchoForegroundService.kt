package com.example.echo

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.util.Log

import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat

import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig

import kotlin.math.abs
import kotlin.math.sqrt

import java.io.File
import java.io.RandomAccessFile


class EchoForegroundService : Service() {

    private val channelId = "echo_listening_channel"
    private val notifId = 1001
    private val tag = "EchoAudio"

    // Sherpa-ONNX KWS model expects 16 kHz audio.
    private val sampleRate = 16000

    var actualSampleRate: Int = 0
        private set

    // Digital microphone gain.
    // 1.0 = no boost.
    private val micGain = 2.5f

    // Current audio source.
    // VOICE_RECOGNITION usually enables Android audio processing.
    private val audioSource = MediaRecorder.AudioSource.MIC

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var isCapturing = false

    private var keywordSpotter: KeywordSpotter? = null
    private var keywordStream: OnlineStream? = null

    // --- Auto noise-suppression (based on ambient RMS) ---
    // We stay on raw MIC always (proven better for KWS than
    // VOICE_RECOGNITION's built-in processing). NoiseSuppressor is a
    // separate effect we can flip on/off at runtime WITHOUT tearing down
    // AudioRecord, so no manual switching is needed.
    // NoiseSuppressor testing showed it HURTS far-field detection (Android's
    // generic suppressor smooths audio in ways that work against the KWS
    // model). Kept in code but gated off by this flag rather than deleted,
    // in case it's worth revisiting later (e.g. with a different model).
    private val enableAutoNoiseSuppression = false

    private var noiseSuppressor: NoiseSuppressor? = null
    private var suppressorEnabled = false

    // RMS above this, sustained, is treated as a "noisy" environment.
    // RMS below this, sustained, is treated as "quiet" again.
    // Calibrated from real logged level= values: ~112 quiet, ~1500-2000 fan.
    private val noiseSuppressorOnThreshold = 700f
    private val noiseSuppressorOffThreshold = 300f

    // Number of consecutive 500ms RMS checks required before switching.
    // 4 x 500ms = ~2 seconds sustained before we flip the suppressor.
    private val requiredConsecutiveWindows = 4

    private var loudWindowCount = 0
    private var quietWindowCount = 0

    // --- Targeted high-pass filter (fan/rumble cutoff) ---
    // Simple one-pole IIR high-pass filter applied to the float samples
    // right before they're fed to Sherpa. Unlike NoiseSuppressor, this
    // only removes low-frequency rumble (fans, AC hum) and leaves the
    // speech-relevant frequency range untouched. Fully our own code, so
    // behavior is predictable and doesn't depend on OEM DSP quality.
    // Cutoff is tunable — start around 150 Hz and adjust based on results.
    private val highPassCutoffHz = 150f
    private var hpPrevIn = 0f
    private var hpPrevOut = 0f

    // --- TEMPORARY: WAV debug capture, for openWakeWord benchmark ---
    // Writes the same gained PCM16 audio that feeds Sherpa into a .wav
    // file, so it can be pulled off the phone and run through a
    // DIFFERENT wake-word engine on the PC for a fair architecture
    // comparison (same physical mic, same room, same fan).
    // Set to false once the benchmark is done — this is not meant to be
    // a permanent feature.
    private val enableWavDebugCapture = true

    private var wavFile: RandomAccessFile? = null
    private var wavBytesWritten: Long = 0


    /**
     * TEMPORARY debug helper — starts a new .wav file for this Arm session.
     * Written to app-specific external storage (no extra permission needed
     * on API 19+), pullable via Android Studio's Device File Explorer or
     * `adb pull`. Path:
     * /storage/emulated/0/Android/data/com.example.echo/files/
     */
    private fun startWavCapture() {

        if (!enableWavDebugCapture) return

        try {
            val dir = getExternalFilesDir(null)

            val fileName = "echo_debug_${System.currentTimeMillis()}.wav"

            val file = File(dir, fileName)

            val raf = RandomAccessFile(file, "rw")

            // Write a placeholder 44-byte header now; real sizes get
            // patched in when the file is finalized in finalizeWavCapture().
            raf.write(ByteArray(44))

            wavFile = raf
            wavBytesWritten = 0

            Log.i(tag, "WAV debug capture started: ${file.absolutePath}")

        } catch (e: Exception) {
            Log.w(tag, "Failed to start WAV debug capture: ${e.message}")
            wavFile = null
        }
    }


    /**
     * TEMPORARY debug helper — appends one chunk of PCM16 audio.
     * Called with the SAME buffer/length already being fed to Sherpa,
     * after gain is applied, so the recording matches what the KWS
     * pipeline actually sees.
     */
    private fun writeWavChunk(buffer: ShortArray, length: Int) {

        val raf = wavFile ?: return

        try {
            val bytes = ByteArray(length * 2)
            var idx = 0

            for (i in 0 until length) {
                val s = buffer[i].toInt()
                bytes[idx++] = (s and 0xFF).toByte()
                bytes[idx++] = ((s shr 8) and 0xFF).toByte()
            }

            raf.write(bytes)
            wavBytesWritten += bytes.size

        } catch (e: Exception) {
            Log.w(tag, "WAV chunk write failed: ${e.message}")
        }
    }


    /**
     * TEMPORARY debug helper — patches the WAV header with real sizes
     * and closes the file. Called when audio capture stops (Unarm).
     */
    private fun finalizeWavCapture() {

        val raf = wavFile ?: return

        try {
            val byteRate = sampleRate * 2 // mono, 16-bit
            val dataLen = wavBytesWritten.toInt()
            val riffLen = dataLen + 36

            val header = ByteArray(44)

            "RIFF".toByteArray().copyInto(header, 0)
            writeIntLE(header, 4, riffLen)
            "WAVE".toByteArray().copyInto(header, 8)
            "fmt ".toByteArray().copyInto(header, 12)
            writeIntLE(header, 16, 16)      // Subchunk1Size (PCM)
            writeShortLE(header, 20, 1)     // AudioFormat = PCM
            writeShortLE(header, 22, 1)     // NumChannels = mono
            writeIntLE(header, 24, sampleRate)
            writeIntLE(header, 28, byteRate)
            writeShortLE(header, 32, 2)     // BlockAlign
            writeShortLE(header, 34, 16)    // BitsPerSample
            "data".toByteArray().copyInto(header, 36)
            writeIntLE(header, 40, dataLen)

            raf.seek(0)
            raf.write(header)
            raf.close()

            Log.i(
                tag,
                "WAV debug capture finalized, bytes=$wavBytesWritten"
            )

        } catch (e: Exception) {
            Log.w(tag, "Failed to finalize WAV debug capture: ${e.message}")
        } finally {
            wavFile = null
            wavBytesWritten = 0
        }
    }


    private fun writeIntLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
        arr[offset + 2] = ((value shr 16) and 0xFF).toByte()
        arr[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }


    private fun writeShortLE(arr: ByteArray, offset: Int, value: Int) {
        arr[offset] = (value and 0xFF).toByte()
        arr[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }


    /**
     * One-pole IIR high-pass filter, applied in place to Float samples.
     *
     * Removes low-frequency content (fan rumble, AC hum) below
     * highPassCutoffHz while leaving speech-relevant frequencies intact.
     * Filter state (hpPrevIn/hpPrevOut) persists across calls so the
     * filter stays continuous across successive audio chunks.
     */
    private fun applyHighPassFilter(samples: FloatArray) {

        val dt = 1f / sampleRate

        val rc = 1f / (2f * Math.PI.toFloat() * highPassCutoffHz)

        val alpha = rc / (rc + dt)

        for (i in samples.indices) {

            val currentIn = samples[i]

            val output = alpha * (hpPrevOut + currentIn - hpPrevIn)

            hpPrevIn = currentIn
            hpPrevOut = output

            samples[i] = output
        }
    }


    /**
     * Apply digital gain to the PCM16 samples.
     *
     * Values are clamped to the valid Short range to prevent
     * integer overflow / wraparound.
     */
    private fun applyGain(buffer: ShortArray, length: Int) {
        if (micGain == 1.0f) return

        for (i in 0 until length) {
            val boosted = buffer[i] * micGain

            buffer[i] = boosted
                .coerceIn(
                    Short.MIN_VALUE.toFloat(),
                    Short.MAX_VALUE.toFloat()
                )
                .toInt()
                .toShort()
        }
    }


    override fun onCreate() {
        super.onCreate()

        Log.i(tag, "EchoForegroundService.onCreate() ENTER")

        try {
            createNotificationChannel()

            startForeground(
                notifId,
                buildNotification()
            )

            Log.i(tag, "Foreground service started successfully")

        } catch (e: Exception) {
            Log.e(
                tag,
                "onCreate() failed: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
        }

        Log.i(tag, "EchoForegroundService.onCreate() EXIT")
    }


    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        Log.i(tag, "onStartCommand() ENTER startId=$startId")

        try {
            startAudioCapture()
        } catch (e: SecurityException) {
            Log.e(
                tag,
                "Microphone permission/security error: ${e.message}",
                e
            )
            stopSelf()
        } catch (e: Exception) {
            Log.e(
                tag,
                "startAudioCapture() failed: ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            stopSelf()
        }

        Log.i(tag, "onStartCommand() EXIT")

        return START_STICKY
    }


    override fun onBind(intent: Intent?): IBinder? = null


    override fun onDestroy() {
        Log.i(tag, "EchoForegroundService.onDestroy() ENTER")

        stopAudioCapture()

        Log.i(tag, "EchoForegroundService.onDestroy() EXIT")

        super.onDestroy()
    }


    /**
     * Initialize Sherpa-ONNX KeywordSpotter using files from:
     *
     * app/src/main/assets/kws/
     *
     * Required files:
     *   encoder-...int8.onnx
     *   decoder-...int8.onnx
     *   joiner-...int8.onnx
     *   tokens.txt
     *   keywords.txt
     */
    private fun initSherpaKeywordSpotter(): Boolean {

        return try {

            val modelDir = "kws"

            val modelConfig = OnlineModelConfig(
                transducer = OnlineTransducerModelConfig(
                    encoder =
                        "$modelDir/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",

                    decoder =
                        "$modelDir/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",

                    joiner =
                        "$modelDir/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
                ),

                tokens = "$modelDir/tokens.txt",

                numThreads = 2,

                provider = "cpu",

                modelType = "zipformer2"
            )


            val keywordConfig = KeywordSpotterConfig(
                featConfig = FeatureConfig(
                    sampleRate = sampleRate,
                    featureDim = 80
                ),

                modelConfig = modelConfig,

                maxActivePaths = 4,

                keywordsFile = "$modelDir/keywords.txt",

                keywordsScore = 3.0f,

                keywordsThreshold = 0.1f,

                numTrailingBlanks = 1
            )


            keywordSpotter = KeywordSpotter(
                assetManager = application.assets,
                config = keywordConfig
            )


            keywordStream = keywordSpotter!!.createStream()


            Log.i(
                tag,
                "Sherpa-ONNX KeywordSpotter initialized successfully"
            )

            true

        } catch (e: Exception) {

            Log.e(
                tag,
                "Sherpa-ONNX initialization FAILED: " +
                        "${e.javaClass.simpleName}: ${e.message}",
                e
            )

            try {
                keywordStream?.release()
            } catch (_: Exception) {
            }

            keywordStream = null

            try {
                keywordSpotter?.release()
            } catch (_: Exception) {
            }

            keywordSpotter = null

            false
        }
    }


    private fun startAudioCapture() {

        if (isCapturing) {
            Log.w(tag, "Audio capture already running")
            return
        }


        // Explicit microphone permission check.
        if (
            ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {

            Log.e(
                tag,
                "RECORD_AUDIO permission is not granted. " +
                        "Cannot start microphone capture."
            )

            stopSelf()
            return
        }


        // Sherpa KWS model requires 16 kHz.
        val minBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )


        if (
            minBufferSize == AudioRecord.ERROR ||
            minBufferSize == AudioRecord.ERROR_BAD_VALUE
        ) {

            Log.e(
                tag,
                "16 kHz AudioRecord is not supported. " +
                        "getMinBufferSize() failed."
            )

            stopSelf()
            return
        }


        // Give AudioRecord a little more room than the minimum.
        val bufferSize = minBufferSize * 2


        val record = try {

            AudioRecord(
                audioSource,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )

        } catch (e: SecurityException) {

            Log.e(
                tag,
                "AudioRecord construction denied by permission: ${e.message}",
                e
            )

            stopSelf()
            return

        } catch (e: Exception) {

            Log.e(
                tag,
                "AudioRecord construction failed: ${e.message}",
                e
            )

            stopSelf()
            return
        }


        if (record.state != AudioRecord.STATE_INITIALIZED) {

            Log.e(
                tag,
                "AudioRecord failed to initialize."
            )

            record.release()

            stopSelf()
            return
        }


        audioRecord = record
        actualSampleRate = record.sampleRate


        // TEMPORARY: start debug WAV capture for openWakeWord benchmark.
        startWavCapture()


        // Attach NoiseSuppressor to this session but leave it OFF.
        // We start OFF because raw, unprocessed MIC audio tested better
        // in quiet/close-range conditions. We only turn it on when the
        // ambient RMS says we're in a noisy environment (see capture loop).
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(record.audioSessionId)
                noiseSuppressor?.enabled = false
                suppressorEnabled = false
                Log.i(tag, "NoiseSuppressor attached (starting OFF)")
            } else {
                Log.w(tag, "NoiseSuppressor not available on this device")
                noiseSuppressor = null
            }
        } catch (e: Exception) {
            Log.w(tag, "NoiseSuppressor create failed: ${e.message}")
            noiseSuppressor = null
        }


        Log.i(tag, "=== AudioRecord initialized ===")
        Log.i(tag, "sampleRate=${record.sampleRate}")
        Log.i(tag, "channelCount=${record.channelCount}")
        Log.i(tag, "audioFormat=${record.audioFormat}")
        Log.i(tag, "bufferSizeInFrames=${record.bufferSizeInFrames}")
        Log.i(tag, "state=${record.state}")
        Log.i(tag, "================================")


        // This KWS configuration is specifically 16 kHz.
        if (record.sampleRate != sampleRate) {

            Log.e(
                tag,
                "Unexpected sample rate ${record.sampleRate}. " +
                        "Sherpa KWS requires $sampleRate Hz."
            )

            record.release()
            audioRecord = null

            stopSelf()
            return
        }


        // Initialize Sherpa before starting the processing thread.
        if (!initSherpaKeywordSpotter()) {

            Log.e(
                tag,
                "Sherpa KeywordSpotter initialization failed. " +
                        "Stopping microphone capture."
            )

            record.release()
            audioRecord = null

            stopSelf()
            return
        }


        try {

            record.startRecording()

        } catch (e: SecurityException) {

            Log.e(
                tag,
                "AudioRecord.startRecording() denied: ${e.message}",
                e
            )

            stopSherpa()

            record.release()
            audioRecord = null

            stopSelf()
            return

        } catch (e: Exception) {

            Log.e(
                tag,
                "AudioRecord.startRecording() failed: ${e.message}",
                e
            )

            stopSherpa()

            record.release()
            audioRecord = null

            stopSelf()
            return
        }


        isCapturing = true


        Log.i(
            tag,
            "Microphone capture started at ${record.sampleRate} Hz"
        )


        captureThread = Thread {

            var lastLogTime = 0L

            val readSize =
                record.bufferSizeInFrames.coerceAtLeast(320)


            val buffer = ShortArray(readSize)


            val stream = keywordStream
                ?: return@Thread


            val kws = keywordSpotter
                ?: return@Thread


            while (isCapturing) {

                val read = try {

                    record.read(
                        buffer,
                        0,
                        buffer.size
                    )

                } catch (e: Exception) {

                    Log.e(
                        tag,
                        "AudioRecord.read() failed: ${e.message}",
                        e
                    )

                    break
                }


                if (read <= 0) {

                    Log.w(
                        tag,
                        "AudioRecord.read() returned $read"
                    )

                    continue
                }


                // Boost microphone samples before KWS.
                applyGain(buffer, read)


                // TEMPORARY: capture this same audio for the
                // openWakeWord benchmark comparison.
                writeWavChunk(buffer, read)


                // RMS logging for microphone diagnostics.
                val now = System.currentTimeMillis()

                if (now - lastLogTime >= 500) {

                    val rms = computeRms(
                        buffer,
                        read
                    )

                    Log.d(
                        tag,
                        "level=$rms"
                    )

                    // Auto noise-suppression decision — currently disabled
                    // (see enableAutoNoiseSuppression comment above).
                    if (enableAutoNoiseSuppression) {
                        noiseSuppressor?.let { suppressor ->

                            if (rms > noiseSuppressorOnThreshold) {
                                loudWindowCount++
                                quietWindowCount = 0
                            } else if (rms < noiseSuppressorOffThreshold) {
                                quietWindowCount++
                                loudWindowCount = 0
                            } else {
                                // In between thresholds: ambiguous, don't
                                // count toward either direction.
                                loudWindowCount = 0
                                quietWindowCount = 0
                            }

                            if (!suppressorEnabled &&
                                loudWindowCount >= requiredConsecutiveWindows
                            ) {
                                try {
                                    suppressor.enabled = true
                                    suppressorEnabled = true
                                    Log.i(
                                        tag,
                                        "NoiseSuppressor AUTO-ENABLED " +
                                                "(sustained noisy environment, level=$rms)"
                                    )
                                } catch (e: Exception) {
                                    Log.w(
                                        tag,
                                        "Failed to enable NoiseSuppressor: ${e.message}"
                                    )
                                }
                                loudWindowCount = 0

                            } else if (suppressorEnabled &&
                                quietWindowCount >= requiredConsecutiveWindows
                            ) {
                                try {
                                    suppressor.enabled = false
                                    suppressorEnabled = false
                                    Log.i(
                                        tag,
                                        "NoiseSuppressor AUTO-DISABLED " +
                                                "(sustained quiet environment, level=$rms)"
                                    )
                                } catch (e: Exception) {
                                    Log.w(
                                        tag,
                                        "Failed to disable NoiseSuppressor: ${e.message}"
                                    )
                                }
                                quietWindowCount = 0
                            }
                        }
                    }

                    lastLogTime = now
                }


                // Convert PCM16 [-32768, 32767]
                // to Float [-1.0, 1.0].
                val samples = FloatArray(read)

                for (i in 0 until read) {

                    samples[i] =
                        buffer[i] / 32768.0f
                }


                // Remove low-frequency rumble (fan/AC hum) before KWS.
                // Speech-relevant frequencies pass through untouched.
                applyHighPassFilter(samples)


                // Feed audio into Sherpa stream.
                stream.acceptWaveform(
                    samples,
                    record.sampleRate
                )


                // Decode all currently available frames.
                while (kws.isReady(stream)) {

                    kws.decode(stream)

                    val result =
                        kws.getResult(stream)


                    if (result.keyword.isNotBlank()) {

                        Log.e(
                            tag,
                            "================================"
                        )

                        Log.e(
                            tag,
                            "ECHO WAKE DETECTED!"
                        )

                        Log.e(
                            tag,
                            "keyword=${result.keyword}"
                        )

                        Log.e(
                            tag,
                            "================================"
                        )


                        /*
                         * PHASE 3:
                         *
                         * Wake-word detection is working here.
                         *
                         * Later this point becomes:
                         *
                         *     ECHO woke up
                         *          ↓
                         *     speaker verification
                         *          ↓
                         *     listen for command
                         *
                         * For ,now we only log the detection.
                         */


                        // Reset immediately after detection
                        // so the same keyword doesn't stay active.
                        kws.reset(stream)
                    }
                }
            }


            Log.i(
                tag,
                "EchoAudioCapture thread exiting"
            )

        }.also {

            it.name = "EchoAudioCapture"
            it.start()
        }
    }


    private fun stopSherpa() {

        try {
            noiseSuppressor?.enabled = false
            noiseSuppressor?.release()
        } catch (e: Exception) {
            Log.w(
                tag,
                "Error releasing NoiseSuppressor: ${e.message}"
            )
        }

        noiseSuppressor = null
        suppressorEnabled = false
        loudWindowCount = 0
        quietWindowCount = 0

        hpPrevIn = 0f
        hpPrevOut = 0f


        try {
            keywordStream?.release()
        } catch (e: Exception) {
            Log.w(
                tag,
                "Error releasing Sherpa stream: ${e.message}"
            )
        }

        keywordStream = null


        try {
            keywordSpotter?.release()
        } catch (e: Exception) {
            Log.w(
                tag,
                "Error releasing Sherpa KeywordSpotter: ${e.message}"
            )
        }

        keywordSpotter = null
    }


    private fun stopAudioCapture() {

        Log.i(
            tag,
            "Stopping audio capture..."
        )


        isCapturing = false


        // Give the capture thread a short period to exit.
        try {
            captureThread?.join(500)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }


        captureThread = null


        // TEMPORARY: finalize debug WAV capture (patches header, closes file).
        // Done after the capture thread has stopped so no more chunks
        // get written after this point.
        finalizeWavCapture()


        audioRecord?.let { record ->

            try {

                if (
                    record.recordingState ==
                    AudioRecord.RECORDSTATE_RECORDING
                ) {
                    record.stop()
                }

            } catch (e: IllegalStateException) {

                Log.w(
                    tag,
                    "AudioRecord.stop() failed: ${e.message}"
                )
            }


            try {
                record.release()
            } catch (e: Exception) {

                Log.w(
                    tag,
                    "AudioRecord.release() failed: ${e.message}"
                )
            }
        }


        audioRecord = null


        stopSherpa()


        Log.i(
            tag,
            "Audio capture stopped"
        )
    }


    /**
     * RMS amplitude of PCM16 samples.
     */
    private fun computeRms(
        buffer: ShortArray,
        length: Int
    ): Int {

        if (length <= 0) {
            return 0
        }


        var sum = 0.0


        for (i in 0 until length) {

            val sample =
                buffer[i].toDouble()

            sum += sample * sample
        }


        val rms =
            sqrt(sum / length)


        return abs(rms.toInt())
    }


    private fun buildNotification(): Notification {

        return NotificationCompat.Builder(
            this,
            channelId
        )
            .setContentTitle("Echo is listening")
            .setContentText("Listening for \"Echo\"")
            .setSmallIcon(
                android.R.drawable.ic_btn_speak_now
            )
            .setOngoing(true)
            .build()
    }


    private fun createNotificationChannel() {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {

            val channel = NotificationChannel(
                channelId,
                "Echo listening",
                NotificationManager.IMPORTANCE_LOW
            )


            val manager =
                getSystemService(
                    NotificationManager::class.java
                )


            manager.createNotificationChannel(channel)
        }
    }
}