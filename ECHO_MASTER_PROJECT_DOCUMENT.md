# ECHO — Master Project Document

Reconnaissance handoff produced from a full read of the repository
`https://github.com/vinamra-1/ECHO.git` at commit `950f3ec`
("Add microphone recording and KWS pipeline", 2026-09-02).

Nothing in the repository was modified to produce this document. The only
change introduced is this file.

Every non-trivial statement below carries one of these tags:

| Tag | Meaning |
|---|---|
| `CONFIRMED` | Directly verified from code, files, Git history, build output, or a benchmark run performed during reconnaissance. |
| `INFERRED` | Strongly suggested by code/comments/context, but not directly verifiable from the repository. |
| `PLANNED` | Described as intended (mostly in `PROJECT_CONTEXT.md` or code comments) but not implemented. |
| `UNKNOWN` | Not determinable from the repository. |

Where a claim comes only from `PROJECT_CONTEXT.md` (the previous
developer's own notes) and cannot be cross-checked in code or Git history,
it is tagged `INFERRED (source: PROJECT_CONTEXT.md)`.

---

## Table of contents

1. Executive summary
2. Project goal and long-term vision
3. Current state (what actually exists)
4. Architecture and component map
5. Runtime data flow
6. Android architecture (activities, services, manifest, lifecycle)
7. Microphone and audio pipeline
8. Audio processing (gain, RMS, high-pass, NoiseSuppressor, ordering)
9. Wake-word system (Sherpa-ONNX KWS)
10. Sherpa-ONNX configuration (exact values)
11. Models and assets
12. WAV debug capture system
13. Benchmark system (recordings + Python scripts)
14. Experimental history — successful, failed, inconclusive
15. Speaker verification
16. Speech recognition (STT)
17. AI / Gemini
18. OAuth
19. Accessibility service
20. Text-to-speech
21. Security review
22. Build configuration and build results
23. Dependencies
24. Current bugs
25. Known limitations
26. TODO list (as found in code and context)
27. Recommended roadmap
28. Testing strategy
29. Git history
30. Important files
31. Sensitive / "DO NOT CHANGE without understanding" areas
32. Open questions
33. Future architecture
34. Definition of done (for the next milestone)
35. Appendix A — Reconnaissance environment and reproduction commands

---

## 1. Executive summary

- ECHO is an Android (Kotlin) voice-assistant project targeting a Samsung
  Galaxy A23 (Snapdragon 680) on Android 14. `CONFIRMED` (from
  `PROJECT_CONTEXT.md` and `app/build.gradle.kts`).
- **What works today:** a user-armed foreground service captures 16 kHz mono
  PCM from the microphone, applies 2.5x digital gain, writes a debug WAV,
  applies a 150 Hz one-pole high-pass, and feeds the audio to a
  Sherpa-ONNX streaming Zipformer2 keyword spotter configured for the
  keyword "ECHO". On detection it only logs `ECHO WAKE DETECTED!` and
  resets the stream. `CONFIRMED`.
- **What does not exist yet:** speaker verification, speech-to-text,
  Gemini client, OAuth token exchange, TTS, phone actions, accessibility
  automation. Their scaffolding exists as empty classes, config objects,
  manifest entries, and TODO comments. `CONFIRMED`.
- **Build status:** `assembleDebug` and `assembleRelease` both succeed
  with Gradle 9.3.0 / AGP 8.5.0 / Kotlin 1.9.24 on Linux (JDK 17), after
  installing an Android SDK (platform 34, build-tools 34.0.0). Release
  minification runs R8 with a *missing* `proguard-rules.pro` (Gradle
  warns but does not fail). `CONFIRMED`.
- **Most important open finding:** running the repository's own
  `run_sherpa_benchmark.py` logic against the two checked-in recordings
  with the exact Android KWS configuration produced **zero detections of
  the "ECHO" keyword** in both files (sherpa-onnx Python 1.13.7, not the
  1.12.21 AAR — see §13.5 and §32). Alternative keywords built from valid
  tokens (e.g. `HEY`) *do* fire on the same audio, so the model and
  pipeline are functional; the specific keyword/threshold configuration
  for "ECHO" is what has not been shown to work on these recordings.
  Whether it works on-device on the A23 is `UNKNOWN` — there are no
  on-device wake-word results recorded in the repository.
- **Security:** no real secrets found in the working tree or Git history.
  `PicovoiceConfig.ACCESS_KEY` is a placeholder string. `CONFIRMED`.
- **Git history is very shallow** (2 commits, same day). Most
  "experimental history" lives only in comments and `PROJECT_CONTEXT.md`,
  not in commits. `CONFIRMED`.

---

## 2. Project goal and long-term vision

### 2.1 Goal (`CONFIRMED`, source: `PROJECT_CONTEXT.md` "What Echo is")

> An on-device Android 14 voice assistant for a Galaxy A23 (Snapdragon
> 680). User-armed wake-word listening → local voice verification → cloud
> LLM (Gemini) for reasoning → executes actions via official Android APIs
> (intents, Accessibility Service, Google REST APIs). No root.

### 2.2 End-to-end flow (`PLANNED`)

```
Mic → "Echo" detected (sherpa-onnx KWS)
    → Verify it is the authorized user's voice (speaker verification)
    → Listen to the actual command (STT)
    → AI response (Gemini)
    → Speak back (TTS)
    → [optionally] perform a phone action (call, open app, send message)
```

Only the first arrow (mic → KWS → log) is implemented. `CONFIRMED`.

### 2.3 Confirmed requirements / hard constraints
(`CONFIRMED` as stated requirements in `PROJECT_CONTEXT.md`; whether the
code honours each is noted)

| Constraint | Status in code |
|---|---|
| Custom "Echo" wake word via sherpa-onnx open-vocabulary KWS, no retraining | Implemented (`keywords.txt` = `▁E CH O`) `CONFIRMED` |
| Wake word fully offline / on-device | Implemented (`provider = "cpu"`, assets bundled) `CONFIRMED` |
| No Picovoice / paid dependency in the wake-word path | Wake path is Sherpa-only, **but** the Porcupine dependency and `PicovoiceConfig.kt` still exist in the build `CONFIRMED` |
| Improve weak mic sensitivity (`micGain`, swappable `audioSource`) | Implemented, "tuning ongoing" `CONFIRMED` |
| Handle noisy environments reasonably | Partially: HPF on; auto-NoiseSuppressor gated off `CONFIRMED` |
| Speaker verification, on-device if practical | Not started `CONFIRMED` |
| Conversational interaction | Not started `CONFIRMED` |
| Full-sentence STT (likely Sherpa streaming ASR) | Not started `CONFIRMED` |
| Android TTS responses | Not started `CONFIRMED` |
| Phone control via Intents / Accessibility, official APIs only | Not started (empty `EchoAccessibilityService`) `CONFIRMED` |
| minSdk 26, target/compileSdk 34 | Honoured `CONFIRMED` |
| OAuth tokens only in `EncryptedSharedPreferences` | Library present, no code yet `CONFIRMED` |
| Google API data stays in RAM, never SQLite | No such code yet `CONFIRMED` |
| Mic FGS only started from foreground context, never `BOOT_COMPLETED` | Honoured (`MainActivity` button only; no receiver) `CONFIRMED` |
| Gemini model name only via `GeminiConfig.MODEL_NAME` | Honoured (only place it appears) `CONFIRMED` |
| Storage budget ~250–300 MB, unverified | Not measured in repo; see §22.4 for measured APK sizes `CONFIRMED` |
| No "Knox 0x0 guarantee" wording anywhere | No such wording found `CONFIRMED` |

---

## 3. Current state (what actually exists)

### 3.1 Implemented and verifiable from code (`CONFIRMED`)

- `MainActivity`: Arm/Unarm toggle, runtime permission requests
  (`RECORD_AUDIO`, `POST_NOTIFICATIONS` on API 33+), button that opens
  system Accessibility settings.
- `EchoForegroundService`: microphone-type foreground service with
  notification; `AudioRecord` capture thread; gain; WAV debug writer;
  RMS logging; (disabled) auto-NoiseSuppressor logic; high-pass filter;
  Sherpa-ONNX `KeywordSpotter` init/feed/decode/reset; clean teardown.
- Sherpa-ONNX 1.12.21 AAR (Git LFS) and the
  `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` int8 model
  files bundled as assets.
- Two 16 kHz mono WAV test recordings and two Python benchmark scripts
  (openWakeWord comparison; exact Sherpa baseline).

### 3.2 Scaffold only (`CONFIRMED`)

- `EchoAccessibilityService`: registered in manifest, static `instance`,
  empty `onAccessibilityEvent` with TODO.
- `OAuthCallbackActivity`: registered with custom scheme intent-filter,
  `finish()`es immediately with TODO.
- `net/GeminiConfig.kt`: two constants (`MODEL_NAME`, `BASE_URL`), no
  client.
- `PicovoiceConfig.kt`: legacy Porcupine constants (placeholder key);
  nothing references it.

### 3.3 Status claims that could not be verified from the repository

- "Phase 1 / 2 / 2b verified on physical Galaxy A23" —
  `INFERRED (source: PROJECT_CONTEXT.md)`. No logs, screenshots, or test
  artifacts are checked in.
- "Wake-word detection is working here" (comment in the service at the
  detection site) — `UNKNOWN`. No on-device detection evidence in repo;
  offline benchmark (§13.5) produced zero "ECHO" detections.
- `PROJECT_CONTEXT.md` phase list still says step 4 ("Choose & verify
  exact ONNX models") is the *current* step and step 5 is "Porcupine +
  Silero", while the code has already moved to Sherpa KWS. The document
  is partially stale relative to the code. `CONFIRMED`.

---

## 4. Architecture and component map

```
com.example.echo
├── MainActivity                 (UI: Arm/Unarm, permissions, open A11y settings)
├── EchoForegroundService        (mic capture + audio DSP + Sherpa KWS)  <-- core
├── EchoAccessibilityService     (scaffold; future UI automation)
├── OAuthCallbackActivity        (scaffold; receives com.example.echo://oauth2redirect)
├── PicovoiceConfig              (legacy Porcupine constants; unused)
└── net/
    └── GeminiConfig             (MODEL_NAME, BASE_URL constants; no client)

app/libs/sherpa-onnx-1.12.21.aar (Git LFS)   -> com.k2fsa.sherpa.onnx.* (Kotlin API + JNI .so)
app/src/main/assets/kws/                      -> encoder/decoder/joiner int8 ONNX, tokens.txt, keywords.txt
recordings/                                   -> close_range.wav.wav, far_field_fan.wav.wav, 2 Python benchmark scripts
PROJECT_CONTEXT.md                            -> previous developer's design notes / audit
```

Inter-component communication (`CONFIRMED`):

- `MainActivity` → `EchoForegroundService`: `ContextCompat.startForegroundService(Intent)` and `stopService(Intent)`. No binding (`onBind` returns null), no broadcasts, no shared state. The Activity tracks `isArmed` locally and does **not** learn if the service stopped itself (e.g. after an init failure) — see §24.
- `EchoForegroundService` → Sherpa: direct in-process calls through the AAR's Kotlin wrapper (`KeywordSpotter`, `OnlineStream`).
- No communication exists yet between the service and `EchoAccessibilityService`, `OAuthCallbackActivity`, or any network layer.

---

## 5. Runtime data flow

### 5.1 Launch → armed (`CONFIRMED`)

1. User launches app → `MainActivity.onCreate` inflates `activity_main.xml`
   (title "Echo", subtitle *"Phase 1 scaffold — wake word coming next"*,
   "Arm Echo (start listening)" button, status text, "Enable Accessibility
   Service" button).
2. User taps **Arm Echo** → `armEcho()`:
   - If `RECORD_AUDIO` (and `POST_NOTIFICATIONS` on API ≥ 33) not granted →
     `requestPermissions`; on grant callback `armEcho()` re-runs.
   - Else → `ContextCompat.startForegroundService(EchoForegroundService)`;
     UI shows "Status: armed (listening)".
3. `EchoForegroundService.onCreate` → creates notification channel
   `echo_listening_channel` (IMPORTANCE_LOW) → `startForeground(1001,
   notification "Echo is listening / Listening for \"Echo\"")`.
4. `onStartCommand` → `startAudioCapture()` (details §7) → returns
   `START_STICKY`.

### 5.2 Steady state (per audio chunk, on thread `EchoAudioCapture`) (`CONFIRMED`)

```
AudioRecord.read(short[readSize])
  → applyGain(×2.5, hard clip to int16)            [in place, PCM16]
  → writeWavChunk(gained PCM16)                    [debug WAV, if enabled]
  → every ≥500 ms: computeRms → Log.d "level=N"
       → (auto-NoiseSuppressor decision; DISABLED by flag)
  → float[] = pcm / 32768f
  → applyHighPassFilter(150 Hz one-pole IIR)       [float]
  → stream.acceptWaveform(float[], 16000)
  → while kws.isReady(stream): kws.decode(stream); result = kws.getResult(stream)
       → if result.keyword not blank: Log.e "ECHO WAKE DETECTED!" / "keyword=…"; kws.reset(stream)
```

### 5.3 Unarm / teardown (`CONFIRMED`)

User taps **Unarm Echo** → `stopService()` → `onDestroy` →
`stopAudioCapture()`: `isCapturing=false` → `captureThread.join(500)` →
`finalizeWavCapture()` (patch WAV header) → `AudioRecord.stop()/release()`
→ `stopSherpa()` (release NoiseSuppressor, reset HPF state, release
stream and spotter).

### 5.4 After detection

Nothing. Detection is log-only. The comment block at the detection site
describes the plan: *ECHO woke up → speaker verification → listen for
command*. `PLANNED`.

---

## 6. Android architecture

### 6.1 Manifest (`CONFIRMED`, `app/src/main/AndroidManifest.xml`)

Permissions:

| Permission | Used by current code? |
|---|---|
| `INTERNET` | No (no network code yet) |
| `RECORD_AUDIO` | Yes |
| `CALL_PHONE` | No — declared for future phone actions |
| `READ_CONTACTS` | No — declared for future phone actions |
| `WAKE_LOCK` | No — nothing acquires a wake lock |
| `FOREGROUND_SERVICE` | Yes |
| `FOREGROUND_SERVICE_MICROPHONE` | Yes (required on API 34 for `foregroundServiceType="microphone"`) |
| `POST_NOTIFICATIONS` | Yes (requested at runtime on API ≥ 33) |

Components:

- `.MainActivity` — launcher activity, exported.
- `.EchoForegroundService` — `foregroundServiceType="microphone"`, `exported="false"`.
- `.EchoAccessibilityService` — `permission="android.permission.BIND_ACCESSIBILITY_SERVICE"`, `exported="false"`, intent-filter `android.accessibilityservice.AccessibilityService`, meta-data → `@xml/accessibility_service_config`.
- `.OAuthCallbackActivity` — `launchMode="singleTop"`, `exported="true"`, intent-filter VIEW/DEFAULT/BROWSABLE with `<data android:scheme="com.example.echo" android:host="oauth2redirect" />`.

No `BroadcastReceiver`, no `BOOT_COMPLETED`. `CONFIRMED`.

### 6.2 Accessibility service config (`CONFIRMED`, `res/xml/accessibility_service_config.xml`)

- Event types: `typeWindowStateChanged|typeWindowContentChanged`
- Feedback: `feedbackGeneric`, `notificationTimeout=100`
- `canRetrieveWindowContent="true"`, `canPerformGestures="true"`
- Flags: `flagRequestEnhancedWebAccessibility|flagIncludeNotImportantViews|flagRequestTouchExplorationMode|flagReportViewIds`
- `android:settingsActivity="com.example.echo.SettingsActivity"` — **this
  class does not exist** in the project. `CONFIRMED` (see §24).

### 6.3 Lifecycle notes (`CONFIRMED`)

- Service returns `START_STICKY`; if the OS kills and restarts it, `onStartCommand` re-enters `startAudioCapture()`. On Android 14 a restarted mic-type FGS from background may be disallowed — `PROJECT_CONTEXT.md` explicitly documents "user must re-arm" as the accepted limitation. `INFERRED` for OS behaviour; `CONFIRMED` for the documented stance.
- Every failure path in `startAudioCapture()` calls `stopSelf()`. The Activity is not notified, so UI can show "armed" while the service is dead (§24).
- `isCapturing` guard prevents double-start on repeated `onStartCommand`.

### 6.4 Android version requirements (`CONFIRMED`)

`minSdk 26`, `compileSdk 34`, `targetSdk 34`. Notification channel guarded
by `SDK_INT >= O` (redundant with minSdk 26 but harmless).

---

## 7. Microphone and audio pipeline

All values from `EchoForegroundService.kt`. `CONFIRMED`.

| Parameter | Value | Notes |
|---|---|---|
| Audio source | `MediaRecorder.AudioSource.MIC` | Comment says VOICE_RECOGNITION "usually enables Android audio processing"; MIC chosen because raw audio "tested better" (§14). |
| Sample rate requested | 16000 Hz | Required by KWS model. |
| Sample rate handling | `actualSampleRate = record.sampleRate`; if `!= 16000` → log error, release, `stopSelf()`. **No resampling / no 44.1 kHz fallback exists in current code** (PROJECT_CONTEXT.md mentions a fallback path from an earlier iteration; it is not in the current tree). |
| Channels | `CHANNEL_IN_MONO` |
| Encoding | `ENCODING_PCM_16BIT` |
| Buffer size | `AudioRecord.getMinBufferSize(...) * 2`; errors (`ERROR`, `ERROR_BAD_VALUE`) → `stopSelf()` |
| Read chunk | `record.bufferSizeInFrames.coerceAtLeast(320)` shorts per `read()` (device dependent; ≥ 20 ms) |
| Thread | Single `Thread` named `EchoAudioCapture`; loop `while (isCapturing)`; blocking `record.read()`; `read <= 0` → warn & continue |
| Permission checks | `RECORD_AUDIO` checked in service before constructing `AudioRecord`; `SecurityException` caught on construction and `startRecording()` |
| Foreground service | `startForeground` in `onCreate` **before** any mic access (required for `foregroundServiceType="microphone"`) |
| Effects attached | `NoiseSuppressor.create(audioSessionId)` if available, set `enabled=false` |

Failure behaviour: every early exit releases the `AudioRecord` and calls
`stopSelf()`; Sherpa init failure also aborts capture (the pipeline does
**not** degrade to level-logging-only as the old Porcupine notes describe).
`CONFIRMED`.

---

## 8. Audio processing

### 8.1 Digital gain (`CONFIRMED`)

```kotlin
private val micGain = 2.5f
// per sample: (s * 2.5f).coerceIn(-32768f, 32767f).toInt().toShort()
```
Hard clipping, no soft limiter, no AGC. Applied in place to the PCM16
buffer before the WAV write and before float conversion → the WAV and
Sherpa both see gained audio. The checked-in `close_range.wav.wav` has
~1.14% of samples at full scale (§13.2), consistent with this gain
clipping on close speech. `CONFIRMED` measurement; attribution to gain
`INFERRED`.

### 8.2 RMS diagnostics (`CONFIRMED`)

`computeRms()` = `sqrt(mean(s²))` over the *gained* PCM16 chunk, computed at
most every 500 ms, logged as `Log.d("EchoAudio", "level=N")`. Comments
record real observed values: ~112 quiet, ~1500–2000 with fan;
PROJECT_CONTEXT.md records baseline ~130–300, spikes 1000–3500+.
`INFERRED (source: comments/PROJECT_CONTEXT.md)`.

### 8.3 Auto NoiseSuppressor (present, **disabled**) (`CONFIRMED`)

```kotlin
private val enableAutoNoiseSuppression = false
private val noiseSuppressorOnThreshold  = 700f   // RMS
private val noiseSuppressorOffThreshold = 300f   // RMS
private val requiredConsecutiveWindows  = 4      // 4 × 500 ms ≈ 2 s
```
Hysteresis state machine: ≥4 consecutive loud windows → enable; ≥4
consecutive quiet windows → disable; in-between resets both counters.
Entire block is inside `if (enableAutoNoiseSuppression)`, so today the
`NoiseSuppressor` object is created and stays OFF forever. Comment states
why: "NoiseSuppressor testing showed it HURTS far-field detection …
Kept in code but gated off by this flag rather than deleted." `CONFIRMED`
(code) / `INFERRED` (test result — no data in repo).

### 8.4 High-pass filter (`CONFIRMED`)

```kotlin
private val highPassCutoffHz = 150f
// one-pole IIR: y[n] = α (y[n-1] + x[n] − x[n-1]),  α = RC / (RC + dt),  RC = 1/(2π·150)
```
Applied to normalized floats immediately before `acceptWaveform`. Filter
state (`hpPrevIn`, `hpPrevOut`) persists across chunks and is reset to 0
in `stopSherpa()`. Comment: cutoff "start around 150 Hz and adjust based
on results" — no tuning result recorded. **Not applied to the WAV**
(WAV is pre-HPF, post-gain).

### 8.5 Processing order (`CONFIRMED`)

`read → gain(clip) → WAV → RMS/log → (NS decision, disabled) → /32768 → HPF → Sherpa`

Exact audio to Sherpa: float32 in [-1, 1], gained ×2.5 with clipping, HPF 150 Hz, 16 kHz.
Exact audio to WAV: int16, gained ×2.5 with clipping, **no HPF**, 16 kHz mono.

---

## 9. Wake-word system

- Engine: Sherpa-ONNX `KeywordSpotter` (open-vocabulary transducer KWS) from `sherpa-onnx-1.12.21.aar`. `CONFIRMED`.
- Model: streaming Zipformer2 transducer, int8, `epoch-12-avg-2-chunk-16-left-64`. Filenames and ONNX metadata (`model_type=zipformer2`, feature dim 80, encoder out 320, vocab 500) match the public `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01` release named in PROJECT_CONTEXT.md. `CONFIRMED` (metadata) / `INFERRED` (exact upstream release identity).
- Keyword: `keywords.txt` contains exactly one line `▁E CH O` (BPE tokens from `tokens.txt`; no per-keyword `:score`/`#threshold`/`@label` overrides). Sherpa returns `result.keyword` as the token string; the code checks `isNotBlank()` only — it does not compare against a specific label. `CONFIRMED`.
- Stream lifecycle: one `OnlineStream` created at init; never recreated; `kws.reset(stream)` after each hit. `CONFIRMED`.
- Detection output: four `Log.e` lines including `ECHO WAKE DETECTED!` and `keyword=<tokens>`. Nothing else. `CONFIRMED`.
- Asset loading: `KeywordSpotter(assetManager = application.assets, config)` with relative paths `kws/...` — Sherpa reads directly from APK assets (no copy to filesystem). `CONFIRMED`.
- Threading: init and decoding all on the capture thread (init happens in `onStartCommand` on the main thread — see §24). `CONFIRMED`.

---

## 10. Sherpa-ONNX configuration (exact values, `CONFIRMED`)

```kotlin
OnlineModelConfig(
  transducer = OnlineTransducerModelConfig(
    encoder = "kws/encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    decoder = "kws/decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx",
    joiner  = "kws/joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"),
  tokens = "kws/tokens.txt",
  numThreads = 2,
  provider = "cpu",
  modelType = "zipformer2")

KeywordSpotterConfig(
  featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
  modelConfig = <above>,
  maxActivePaths = 4,
  keywordsFile = "kws/keywords.txt",
  keywordsScore = 3.0f,
  keywordsThreshold = 0.1f,
  numTrailingBlanks = 1)
```

`run_sherpa_benchmark.py` mirrors these exactly (`NUM_THREADS=2`,
`PROVIDER="cpu"`, `MAX_ACTIVE_PATHS=4`, `KEYWORDS_SCORE=3.0`,
`KEYWORDS_THRESHOLD=0.1`, `NUM_TRAILING_BLANKS=1`, `MIC_GAIN=2.5`,
`HIGH_PASS_CUTOFF=150.0`). `CONFIRMED`.

---

## 11. Models and assets

| File | Size | Notes |
|---|---|---|
| `app/src/main/assets/kws/encoder-…int8.onnx` | 4,807,159 B | Zipformer2 streaming encoder, int8 |
| `app/src/main/assets/kws/decoder-…int8.onnx` | 277,985 B | vocab 500 |
| `app/src/main/assets/kws/joiner-…int8.onnx` | 163,380 B | joiner dim 320 |
| `app/src/main/assets/kws/tokens.txt` | 500 lines | BPE tokens `<token> <id>` |
| `app/src/main/assets/kws/keywords.txt` | 1 line | `▁E CH O` |
| `app/libs/sherpa-onnx-1.12.21.aar` | LFS (oid `4ce353cda9…`) | Kotlin API + `libsherpa-onnx-jni.so`, `libsherpa-onnx-c-api.so`, `libsherpa-onnx-cxx-api.so`, `libonnxruntime.so` for arm64-v8a, armeabi-v7a, x86, x86_64 |

`CONFIRMED`. The `.ppn` Porcupine keyword file referenced by
`PicovoiceConfig.KEYWORD_FILE` is **not** present. `CONFIRMED`.

Git LFS: cloning without `git-lfs` installed yields a 3-line pointer file
instead of the AAR and the build will fail at dependency resolution /
class lookup. `CONFIRMED` (LFS tracked) / `INFERRED` (failure mode).

---

## 12. WAV debug capture system (`CONFIRMED`)

- Flag: `enableWavDebugCapture = true` (marked TEMPORARY, "set to false once the benchmark is done").
- Location: `getExternalFilesDir(null)` → `/storage/emulated/0/Android/data/com.example.echo/files/echo_debug_<epoch-ms>.wav`; pullable with `adb pull`, no extra permission.
- Content: the gained PCM16 buffer exactly as fed to the float conversion (post-gain, **pre-HPF**), mono, 16-bit, 16 kHz.
- Mechanism: `RandomAccessFile`; 44-byte zeroed header placeholder written at start; chunks appended; `finalizeWavCapture()` patches RIFF/fmt/data sizes (mono, 16 kHz, 16-bit) after the capture thread has stopped.
- Every arm session creates a new file; nothing deletes old files → unbounded growth of raw microphone audio on device (privacy/storage concern, §21, §25).
- Purpose (comment): produce audio for the openWakeWord PC benchmark under identical mic/room/fan conditions. The two files in `recordings/` are `INFERRED` to have been produced this way (format matches exactly: mono/16-bit/16 kHz, close-range file clips at full scale as gain would cause). Their filenames end in `.wav.wav` (double extension) — `CONFIRMED`; scripts open them by path so this is harmless.

---

## 13. Benchmark system

### 13.1 Files (`CONFIRMED`)

- `recordings/close_range.wav.wav`
- `recordings/far_field_fan.wav.wav`
- `recordings/run_oww_benchmark.py` — openWakeWord comparison
- `recordings/run_sherpa_benchmark.py` — "ECHO PHASE 3B-1 EXACT SHERPA-ONNX KWS BASELINE"

### 13.2 Recording properties (measured during reconnaissance, `CONFIRMED`)

| File | Frames | Duration | RMS | Peak | Clipped samples |
|---|---|---|---|---|---|
| `close_range.wav.wav` | 499,200 | 31.20 s | ≈5,952 | 32,768 | 5,703 (1.14%) |
| `far_field_fan.wav.wav` | 359,680 | 22.48 s | ≈3,433 | 32,767 | 9 (0.003%) |

Recording conditions (speaker, distance, what was said, how many times
"Echo" was spoken, room, fan type) are **not documented anywhere** in the
repository. Names imply "close range" vs "far field with a fan running".
`UNKNOWN` beyond the file names.

### 13.3 `run_oww_benchmark.py` (`CONFIRMED`)

- Requires `openwakeword`, `numpy`; usage `python run_oww_benchmark.py close.wav far_fan.wav`.
- Loads openWakeWord model **`hey_jarvis`** (a stock oWW keyword — there is no "echo" oWW model). It therefore measures *engine behaviour on a different wake word*, not "Echo" detection. Any hits are false-positive-rate/sensitivity indications, not ECHO accuracy.
- Input must be mono / 16-bit / 16 kHz (raises otherwise).
- Feeds 1,280-sample (80 ms) chunks; tests gain conditions RAW, −6 dB, −12 dB; thresholds 0.20–0.70; groups scores above threshold into events with a 1.0 s gap.
- Output: per-file, per-gain, per-threshold event counts and timestamps.

### 13.4 `run_sherpa_benchmark.py` (`CONFIRMED`)

- Requires `sherpa_onnx` (pip), `numpy`.
- Model paths are hard-coded as **Windows relative paths** (`r"..\app\src\main\assets\kws"`) → must be run from `recordings/` on Windows, or adapted for POSIX. This also tells us the previous developer worked on Windows (see also `gradlew` exec bit and the Windows `Get-ChildItem` text in the initial commit's service file, §29). `CONFIRMED`.
- Reproduces the Android pipeline: ×2.5 gain with clipping, 150 Hz one-pole HPF, exact KWS config (§10), feeds chunks, ~0.66 s of tail padding, `reset()` after each detection; prints WAV info and detection times.
- **No benchmark output/results are committed.** `CONFIRMED`.

### 13.5 Benchmark executed during reconnaissance (`CONFIRMED`, exploratory, not on-device)

Environment: Linux x86_64, Python `sherpa-onnx` **1.13.7** (the Android AAR
is 1.12.21 — version mismatch; the Python package for 1.12.21 was not
used). Model paths adapted to POSIX; script logic otherwise identical.

| Keyword list | close_range | far_field_fan |
|---|---|---|
| Repository `keywords.txt` (`▁E CH O`), threshold 0.1, score 3.0 | **0 detections** | **0 detections** |
| Exploratory alternative keywords built from valid tokens (e.g. `▁HE Y`) | 3 detections | 1 detection |

Interpretation:
- The model, feature pipeline, and script run correctly (alternative
  keywords fire), so a zero result for "ECHO" is not a broken harness.
- It is `UNKNOWN` whether the recordings actually contain utterances of
  "Echo", how many, or whether the on-device 1.12.21 AAR behaves
  differently. Do **not** treat this as proof that on-device detection
  fails; treat it as "no evidence in the repository that the current
  keyword configuration detects 'Echo' on these recordings."
- Exploratory keyword tests that used tokens absent from `tokens.txt`
  (e.g. `▁EC`, `▁J`) made Sherpa log token errors and `create_stream`
  returned `None`. Any custom keyword must be composed only of tokens
  present in `tokens.txt`. `CONFIRMED`.

No oWW benchmark was run during reconnaissance (would measure `hey_jarvis`, not ECHO).

### 13.6 Limitations of the benchmark system (`CONFIRMED`)

- Two recordings, no ground-truth annotation → cannot compute
  precision/recall/false-accepts.
- oWW script cannot test the ECHO keyword at all.
- No results are stored; no CI; scripts are Windows-path bound.
- Sherpa Python version ≠ Android AAR version unless pinned to 1.12.21.

---

## 14. Experimental history

Git history contains only two commits (§29), so the following is
reconstructed from code comments and `PROJECT_CONTEXT.md`. Each item states
its evidence class. Items marked **REJECTED** should not be repeated
without a new hypothesis.

| # | Experiment | Change | Hypothesis / problem | Test method | Result | Conclusion | Status | Evidence |
|---|---|---|---|---|---|---|---|---|
| E1 | Porcupine wake word | `porcupine-android:4.0.2`, `PicovoiceConfig`, low-level `Porcupine.Builder` | Custom "Echo" .ppn wake word | On-device (planned checklist) | Abandoned: Picovoice Free Tier discontinued (context says 2026-06-30) | Replace with Sherpa-ONNX KWS | **REJECTED (business reason, not technical)** — remove dependency | `INFERRED (source: PROJECT_CONTEXT.md)`; dependency still in gradle `CONFIRMED` |
| E2 | `PorcupineManager` vs low-level API | Chose low-level API | Manager owns its own AudioRecord and would conflict with Phase 2 pipeline | Design decision | n/a | "Never introduce PorcupineManager" | Moot after E1 | `INFERRED (source: PROJECT_CONTEXT.md)` |
| E3 | Sherpa-ONNX KWS swap | AAR 1.12.21 + gigaspeech 3.3M int8 model; `▁E CH O` keyword | Free, offline, open-vocabulary KWS | On-device Logcat | Comment at detection site says "working here" | Adopted | **KEEP** (but see §13.5 zero-detection result) | Code `CONFIRMED`; on-device result `UNKNOWN` |
| E4 | Audio source `VOICE_RECOGNITION` vs `MIC` | `audioSource` constant for one-line A/B | OEM processing on VOICE_RECOGNITION may hurt KWS | On-device A/B on A23 | "raw MIC … proven better for KWS than VOICE_RECOGNITION" (comment) | Use `MIC` | **KEEP** | `INFERRED (source: comments)`; no data |
| E5 | Digital mic gain | `micGain = 2.5f`, clipping-safe | A23 mic is weak / low level | On-device | "Tuning ongoing … not finalized" | Keep at 2.5 for now | **NEEDS FURTHER TESTING** (note clipping in close-range WAV) | `CONFIRMED` code; result `INFERRED` |
| E6 | Android `NoiseSuppressor` always/auto on | Attach effect; RMS-hysteresis auto-toggle (700/300, 4×500 ms) | Improve far-field/fan detection | On-device | "HURTS far-field detection — smooths audio in ways that work against the KWS model" | Gate off with `enableAutoNoiseSuppression=false`, keep code | **REJECTED for this model** — do not re-enable without a different model or new data | `INFERRED (source: comments)` |
| E7 | One-pole high-pass 150 Hz | `applyHighPassFilter` pre-Sherpa | Remove fan/AC rumble without OEM DSP | On-device / benchmark script mirrors it | Not recorded ("adjust based on results") | Currently ON | **INCONCLUSIVE / NEEDS FURTHER TESTING** | `CONFIRMED` code; result `UNKNOWN` |
| E8 | WAV debug capture for oWW comparison | `enableWavDebugCapture=true` | Compare Sherpa vs openWakeWord on identical audio | `run_oww_benchmark.py` on pulled WAVs | Not recorded | Recordings exist; results absent | **INCONCLUSIVE** | `CONFIRMED` code; results `UNKNOWN` |
| E9 | Sherpa exact-baseline benchmark ("Phase 3B-1") | `run_sherpa_benchmark.py` | Reproduce Android detection offline | Script | Not recorded by previous dev; reconnaissance run → 0 ECHO detections | Open problem | **NEEDS FURTHER TESTING** | §13.5 `CONFIRMED` |
| E10 | Phase 2/2b Arm/Unarm stress test | `isCapturing` guard, `stopService→onDestroy` teardown | Robust rapid toggling without leaks | ~12 toggles <10 s on A23 via Logcat | "zero exceptions, no leaked AudioRecord" | Adopted | **KEEP** | `INFERRED (source: PROJECT_CONTEXT.md)` |
| E11 | Removed stray `BIND_NOTIFICATION_LISTENER_SERVICE`; added `POST_NOTIFICATIONS` | Manifest | Bugs found in Phase 2 | On-device | Fixed | — | KEEP | Manifest `CONFIRMED`; history `INFERRED` |
| E12 | 44.1 kHz fallback path | Existed in an earlier iteration per PROJECT_CONTEXT.md; **not present now** | A23 might not support 16 kHz | On-device: 16 kHz worked natively | Fallback unnecessary | Removed | — | Absence `CONFIRMED` |

---

## 15. Speaker verification

- Intended: on-device speaker verification so only the enrolled user can wake ECHO; "likely Silero-based" model; planned class `VoiceAuthenticator.kt`; blueprint mentioned a 256-dim d-vector. `PLANNED`.
- Implementation status: **nothing exists** — no class, no model, no dependency, no enrolment UI, no storage design. `CONFIRMED`.
- Hard requirement from audit #3: inspect the chosen ONNX in Netron and confirm input/output tensor shapes and preprocessing before writing code. `CONFIRMED` (as a documented requirement).
- Connection to wake pipeline: only a comment block at the detection site. `CONFIRMED`.
- Remaining work: choose model (Sherpa-ONNX also ships speaker-embedding extractors — `INFERRED`, verify), enrolment flow, threshold tuning on A23 mic, decision on which audio buffer to verify (the wake utterance vs. a follow-up), secure storage of the enrolled embedding.

---

## 16. Speech recognition (STT)

- Searched for: Sherpa ASR (`OnlineRecognizer`/`OfflineRecognizer`), Whisper, `android.speech.SpeechRecognizer`, cloud speech APIs, extra ONNX models. **None present.** `CONFIRMED`.
- Plan: "likely Sherpa-ONNX streaming ASR model" (PROJECT_CONTEXT.md). `PLANNED`.
- Note: the 1.12.21 AAR already contains the ASR classes, so no new native dependency is needed for a Sherpa ASR; a model download (tens of MB) would be. `INFERRED`.

---

## 17. AI / Gemini

`net/GeminiConfig.kt` (`CONFIRMED`):

```kotlin
object GeminiConfig {
    const val MODEL_NAME: String = "gemini-flash-latest"
    const val BASE_URL: String = "https://generativelanguage.googleapis.com/"
}
```

- No `GeminiApiClient`, no Retrofit interface, no OkHttp client, no request/response models, no API-key handling, no function-calling schema. `CONFIRMED`.
- Retrofit 2.11.0 / Gson converter / OkHttp 4.12.0 / logging-interceptor are declared but unused. `CONFIRMED`.
- Audit #4: Gemini 1.5 models are retired; model name must stay centralized; re-verify before each release; consider the newer Interactions API shape. `INFERRED (source: PROJECT_CONTEXT.md)`.
- Missing: how the API key will be provisioned (BuildConfig from `local.properties`? user-entered + `EncryptedSharedPreferences`?) — `UNKNOWN`.
- Caution for future work: `HttpLoggingInterceptor` at `BODY` level would log prompts/responses (and headers → API key) to Logcat; keep it out of release builds. `INFERRED` best practice.

---

## 18. OAuth

- `OAuthCallbackActivity` receives `com.example.echo://oauth2redirect` and calls `finish()`; TODO says "read intent.data (auth code), exchange for tokens, store via EncryptedSharedPreferences". `CONFIRMED`.
- No authorization request, PKCE, client ID, token exchange, token storage, or AppAuth library. `CONFIRMED`.
- `androidx.security:security-crypto:1.1.0-alpha06` is declared (for `EncryptedSharedPreferences`) but unused. `CONFIRMED`.
- Intended scopes: Gmail, YouTube; Photos scoped down to launching the Photo Picker (audit #5). `PLANNED`.

---

## 19. Accessibility service

- `EchoAccessibilityService`: `companion object { var instance: EchoAccessibilityService? }` set in `onServiceConnected`, cleared in `onDestroy`; `onAccessibilityEvent` empty with TODO; `onInterrupt` empty. `CONFIRMED`.
- Capabilities declared (window content retrieval, gestures, view IDs) exceed what is used → a reviewer / Play policy would flag this once published. `INFERRED`.
- `settingsActivity="com.example.echo.SettingsActivity"` points to a non-existent class (§24).
- User enablement is manual via `Settings.ACTION_ACCESSIBILITY_SETTINGS` (button in `MainActivity`). `CONFIRMED`.
- Planned use: screen traversal, tap/type/gesture for phone actions (roadmap step 7). `PLANNED`.

---

## 20. Text-to-speech

No `TextToSpeech` usage anywhere. Plan: Android platform TTS, replace only if quality is a real problem. `PLANNED`.

---

## 21. Security review

### 21.1 Secrets (`CONFIRMED`)

- Working tree scan (API keys, `AIza…`, OAuth client secrets, bearer tokens, private keys, passwords, keystores, `.env`, `google-services.json`): **none found**.
- Full Git history scan (both commits, all blobs): **none found**.
- `PicovoiceConfig.ACCESS_KEY = "PASTE_YOUR_PICOVOICE_ACCESS_KEY_HERE"` — placeholder, not a secret. However it is a `const val` in source: if someone follows the old instruction to "paste the key locally", a `git add` would commit it. Since Porcupine is abandoned, the safest path is removal (future work, not done here).
- `.gitignore` excludes `.env`, `.env.*`, `local.properties`, `*.apk`, `*.aab`, `*.log`. `CONFIRMED`.

### 21.2 Logging (`CONFIRMED`)

- Tag `EchoAudio`, verbose lifecycle logging at `Log.i`/`Log.e`; RMS level at `Log.d` every 500 ms; detection at `Log.e`. No audio content, no PII is logged. Fine for a debug build; consider gating for release.

### 21.3 Sensitive audio storage (`CONFIRMED`)

- WAV debug capture writes **raw microphone audio for every armed session** to app-external storage, never deleted. Readable by any app with legacy storage access on older APIs, and by anyone with the unlocked phone / adb. This is the most significant privacy issue in the current build and is explicitly marked TEMPORARY in code.

### 21.4 Permissions (`CONFIRMED`)

- `CALL_PHONE`, `READ_CONTACTS`, `INTERNET`, `WAKE_LOCK` are declared without any implementing code. Acceptable for a private prototype; would need justification later. `CALL_PHONE`/`READ_CONTACTS` are runtime permissions and are not requested anywhere yet.

### 21.5 Network (`CONFIRMED`)

- No network calls exist. `BASE_URL` is HTTPS. No `usesCleartextTraffic`, no network security config.

### 21.6 Components (`CONFIRMED`)

- `OAuthCallbackActivity` is `exported="true"` with a custom scheme — required for the redirect, but a custom scheme (vs. App Links) means any app can register the same scheme; when implementing, use PKCE + state verification.
- Foreground service and accessibility service are `exported="false"` / permission-guarded — correct.

### 21.7 Release hardening (`CONFIRMED`)

- Release build has `isMinifyEnabled = true` but `proguard-rules.pro` is missing; there are therefore **no keep rules for `com.k2fsa.sherpa.onnx.*`** (JNI-called Kotlin classes). R8 completed, but whether the release APK actually initialises Sherpa at runtime is `UNKNOWN` — untested; risk of `ClassNotFound`/`NoSuchMethod` from JNI is `INFERRED`.
- No signing config; release APK is unsigned. `CONFIRMED`.

No `SECRET DETECTED` entries.

---

## 22. Build configuration and build results

### 22.1 Toolchain (`CONFIRMED`)

| Item | Value |
|---|---|
| Gradle wrapper | 9.3.0 (`gradle-wrapper.properties`) |
| Android Gradle Plugin | 8.5.0 |
| Kotlin | 1.9.24 (`org.jetbrains.kotlin.android`) |
| JDK used in recon | 17 |
| compileSdk / targetSdk / minSdk | 34 / 34 / 26 |
| `applicationId` | `com.example.echo` |
| versionCode / versionName | 1 / 0.1 |
| `gradle.properties` | `-Xmx2048m`, `android.useAndroidX=true`, `android.nonTransitiveRClass=true` |
| Repositories | `google()`, `mavenCentral()`, `gradlePluginPortal()` (plugins), `FAIL_ON_PROJECT_REPOS` |
| Local AAR | `implementation(files("libs/sherpa-onnx-1.12.21.aar"))` |
| Release | `isMinifyEnabled = true`, `proguard-android-optimize.txt` + `proguard-rules.pro` (file absent) |
| `gradlew` | committed **without** the executable bit (`-rw-r--r--`); use `sh gradlew …` or `chmod +x` locally |

### 22.2 Build results (`CONFIRMED`, Linux x86_64, 2026-09-02)

```
sh gradlew :app:assembleDebug   --no-daemon --console=plain   → BUILD SUCCESSFUL (37 tasks)
sh gradlew :app:assembleRelease --console=plain               → BUILD SUCCESSFUL in 1m 5s
sh gradlew :app:compileDebugKotlin --rerun-tasks              → no Kotlin warnings/errors
```

Warnings emitted:
- `Unable to strip the following libraries, packaging them as they are: libonnxruntime.so, libpv_porcupine.so, libsherpa-onnx-c-api.so, libsherpa-onnx-cxx-api.so, libsherpa-onnx-jni.so` (no NDK installed in recon environment — informational).
- `Supplied proguard configuration does not exist: …/app/proguard-rules.pro` (R8 continues).
- Gradle 9 deprecation notices from AGP 8.5.0 (build still passes). AGP 8.5.0 officially supports Gradle 8.7+; running on 9.3.0 is outside the documented matrix but worked here. `CONFIRMED` result / `INFERRED` support-matrix statement.

### 22.3 Environment problems encountered (not project bugs)

- Maven Central returned HTTP 429 (rate limiting) for plugin artifacts in the recon environment; resolved with a **session-only** Gradle init script pointing at the Google-hosted Maven Central mirror. Nothing in the repo was changed for this.
- Android SDK had to be installed (`cmdline-tools`, `platforms;android-34`, `build-tools;34.0.0`, `platform-tools`).

### 22.4 Artifact sizes (`CONFIRMED`)

| Artifact | Size |
|---|---|
| `app-debug.apk` | 113,275,623 B (~108 MiB) |
| `app-release-unsigned.apk` | 108,039,126 B (~103 MiB) |

Native library share of the debug APK: arm64-v8a 24.3 MiB, armeabi-v7a
16.8 MiB, x86 27.8 MiB, x86_64 27.1 MiB (all four ABIs are packaged;
`libonnxruntime.so` arm64 alone is ~16 MB; `libpv_porcupine.so` still
present at 234 KB). Assets (KWS model) ~5 MiB. **No ABI filtering or
split APKs configured** — for an A23-only target, arm64-v8a alone would
remove ~70 MiB. This relates directly to the "storage budget unverified"
constraint.

---

## 23. Dependencies (`CONFIRMED`, `app/build.gradle.kts`)

| Dependency | Used today? |
|---|---|
| `androidx.core:core-ktx:1.13.1` | Yes |
| `androidx.appcompat:appcompat:1.7.0` | Yes |
| `com.google.android.material:material:1.12.0` | Theme only |
| `androidx.security:security-crypto:1.1.0-alpha06` | No (OAuth future) |
| `com.squareup.retrofit2:retrofit:2.11.0` | No (Gemini future) |
| `com.squareup.retrofit2:converter-gson:2.11.0` | No |
| `com.squareup.okhttp3:okhttp:4.12.0` | No |
| `com.squareup.okhttp3:logging-interceptor:4.12.0` | No |
| `ai.picovoice:porcupine-android:4.0.2` | **No — legacy, slated for removal** |
| `files("libs/sherpa-onnx-1.12.21.aar")` | Yes (KWS) |

Python (benchmarks): `numpy`, `sherpa-onnx` (unpinned in script; use 1.12.21 to match AAR), `openwakeword`.

---

## 24. Current bugs (all `CONFIRMED` from code unless noted)

1. **UI state can desynchronise from service state.** Every failure inside `startAudioCapture()` (no permission, 16 kHz unsupported, AudioRecord init fail, Sherpa init fail, `startRecording` fail) calls `stopSelf()`, but `MainActivity` already set "Status: armed (listening)" and `isArmed = true`. There is no callback/broadcast/bound-service to report failure. Also `isArmed` is lost on Activity recreation (rotation) while the service keeps running.
2. **Accessibility config references a non-existent `com.example.echo.SettingsActivity`.** Android will fail to launch the settings entry from the Accessibility settings screen (`ActivityNotFoundException` in Settings, or a greyed entry — behaviour `INFERRED`).
3. **`proguard-rules.pro` missing** while referenced → release minification runs with no project keep rules. Build passes; runtime risk for Sherpa JNI classes (`INFERRED`).
4. **Sherpa init runs on the main thread** inside `onStartCommand` (model load of ~5 MB ONNX ×3 + ORT session creation) → potential UI jank / ANR risk on slower devices. Impact on A23 `UNKNOWN`.
5. **Stale UI copy:** subtitle says "Phase 1 scaffold — wake word coming next" although KWS is implemented.
6. **Debug WAV capture is permanently on** and never cleans up → storage growth + privacy (§21.3).
7. **Legacy Porcupine dependency** still packaged (`libpv_porcupine.so`, ~234 KB/ABI) though unused.
8. **`gradlew` not executable** on POSIX checkouts.
9. **Recording filenames have `.wav.wav`** double extension (cosmetic).
10. **`PROJECT_CONTEXT.md` is stale** in places (Phase 3 described as "waiting on user to download" AAR/model; step 5 still says Porcupine; phase list says step 4 is current).
11. **Zero "ECHO" detections in offline baseline** (§13.5) — status `NEEDS FURTHER TESTING`, may indicate the recordings do not contain "Echo", a keyword tokenisation/threshold issue, or a library version difference.

---

## 25. Known limitations (`CONFIRMED` unless noted)

- No always-on from boot; user must re-arm after the OS kills the service (Android 14 mic-FGS policy; documented as accepted).
- Wake detection only logs; no downstream behaviour.
- No speaker verification → anyone saying "Echo" would wake it once wired.
- Single global keyword, no per-keyword threshold tuning yet.
- Gain is fixed ×2.5 with hard clipping → close speech clips (1.1% of samples in the close-range recording).
- HPF is a first-order filter (gentle 6 dB/oct roll-off); its benefit is unmeasured.
- All four ABIs packaged → ~105 MB APK.
- No tests of any kind (unit, instrumentation) in the repo.
- No CI.
- Benchmarks lack ground truth and are Windows-path bound.
- `PROJECT_CONTEXT.md` storage budget numbers are placeholders.

---

## 26. TODO list (as found in code and context, `CONFIRMED`)

Code TODOs:
- `EchoAccessibilityService.onAccessibilityEvent`: "TODO Phase 5: traverse rootInActiveWindow, extract text nodes".
- `OAuthCallbackActivity.onCreate`: "TODO Phase 6: read intent.data (auth code), exchange for tokens, store via EncryptedSharedPreferences".
  (Note: the code TODOs number phases 5/6 while `PROJECT_CONTEXT.md` numbers the same work as steps 7/8 — the two numbering schemes are inconsistent.)
- `EchoForegroundService` detection site: "PHASE 3: … Later this point becomes: ECHO woke up → speaker verification → listen for command".
- `EchoForegroundService`: TEMPORARY WAV capture — "Set to false once the benchmark is done".
- `EchoForegroundService`: HPF cutoff "start around 150 Hz and adjust based on results".
- `PicovoiceConfig`: paste key locally (obsolete).

Context TODOs (`PROJECT_CONTEXT.md`): Netron-verify speaker model; Gemini model re-verification; measure real APK size after each phase; remove Porcupine code once Sherpa is wired (Sherpa is wired — removal now due).

---

## 27. Recommended roadmap

Order chosen to (a) close the reliability gap on the layer that exists, (b) remove dead weight before adding more, (c) follow the previous developer's "prove each layer on the A23 before adding the next" rule. Nothing below has been started.

1. **Establish wake-word ground truth (Milestone 3-final).**
   - Annotate the two recordings (or record new ones) with timestamps of each "Echo" utterance and negatives.
   - Pin Python `sherpa-onnx==1.12.21`, make `run_sherpa_benchmark.py` path-portable, and sweep `keywordsThreshold` (e.g. 0.05–0.5) and `keywordsScore` (1.0–4.0) plus per-keyword `:score`/`#threshold` in `keywords.txt`; try alternative tokenisations produced by `sherpa-onnx-cli text2token` for "echo".
   - Capture on-device Logcat evidence for the same utterances; record results in the repo.
2. **Cleanup of the KWS layer** (small, low-risk): remove Porcupine dependency + `PicovoiceConfig`; gate WAV capture behind a debug flag/BuildConfig and add cleanup; add `proguard-rules.pro` with `-keep class com.k2fsa.sherpa.onnx.** { *; }`; fix `settingsActivity`; update UI copy; consider `abiFilters("arm64-v8a")` for the A23 build; move Sherpa init off the main thread; report service failures back to the UI (e.g. `LocalBroadcastManager`/`LiveData`/bound service).
3. **Speaker verification** (roadmap step 5): pick model, Netron-verify, `VoiceAuthenticator.kt`, enrolment UI, secure embedding storage, empirical threshold on A23.
4. **STT**: Sherpa streaming ASR model, command window after wake, end-pointing.
5. **TTS**: Android `TextToSpeech`.
6. **Gemini client** via `GeminiConfig`, key provisioning design, function-calling schema.
7. **Intent/phone actions**, then **Accessibility automation**, then **OAuth/Google APIs**, then embeddings/memory, adaptive sensitivity, storage verification — as in `PROJECT_CONTEXT.md` steps 6–12.

---

## 28. Testing strategy (recommended; none exists today)

- **Offline KWS regression:** pinned `sherpa-onnx==1.12.21`, annotated WAVs, script emits detections vs. ground truth → precision/recall; run in CI (Linux) on every KWS/audio change.
- **DSP unit tests (JVM):** `applyGain` clipping bounds; `applyHighPassFilter` frequency response (feed sine at 50 Hz vs 1 kHz, check attenuation); `computeRms`; WAV header patching. Requires extracting these from the Service into a testable class (refactor — only after milestone 1).
- **Instrumentation on A23:** arm/unarm stress (reproduce Phase 2b), permission-denied path, service-killed path, Logcat assertion of `ECHO WAKE DETECTED!` for scripted playback.
- **Release-build smoke test on device:** verify Sherpa initialises under R8.
- **Build CI:** `assembleDebug` + `lint` on Linux with `git lfs` enabled.

---

## 29. Git history (`CONFIRMED`)

```
950f3ec 2026-09-02 Vinamra1  Add microphone recording and KWS pipeline
        app/src/main/java/com/example/echo/EchoForegroundService.kt | 1112 insertions, 1 deletion
4470887 2026-09-02 Vinamra1  Initial ECHO project snapshot
        36 files, 2599 insertions
```

Observations:
- Not a shallow clone; this is the entire history. Both commits on the same day; one author.
- In the initial commit `EchoForegroundService.kt` contained a single line of stray PowerShell text (`Get-ChildItem .\app\src\main\assets\kws\` repeated) — i.e. the file was accidentally overwritten with a shell command before the snapshot, then replaced with the real service in the second commit. This confirms Windows/PowerShell development and that the snapshot was taken mid-work.
- All experiments described in §14 predate the repository; there is no commit-level record of them. `PROJECT_CONTEXT.md` is the only historical record and is itself partially stale.
- Branches: only `master` (remote `origin/master`). No tags.

---

## 30. Important files

| File | Why it matters |
|---|---|
| `app/src/main/java/com/example/echo/EchoForegroundService.kt` | The entire working system: mic, DSP, WAV, Sherpa KWS (1112 lines) |
| `app/src/main/java/com/example/echo/MainActivity.kt` | Arm/Unarm flow; the only legal way to start the mic service |
| `app/src/main/assets/kws/keywords.txt` | The wake word (`▁E CH O`) |
| `app/src/main/assets/kws/tokens.txt` | Legal tokens for any keyword |
| `app/src/main/assets/kws/*.onnx` | KWS model |
| `app/libs/sherpa-onnx-1.12.21.aar` | Engine (Git LFS — must have `git lfs` installed to clone) |
| `app/src/main/AndroidManifest.xml` | Permissions, FGS type, A11y + OAuth registration |
| `app/src/main/res/xml/accessibility_service_config.xml` | A11y capabilities; broken `settingsActivity` |
| `app/build.gradle.kts` | SDK levels, deps, missing `proguard-rules.pro` reference |
| `recordings/run_sherpa_benchmark.py` | Exact offline replica of the Android KWS pipeline |
| `recordings/run_oww_benchmark.py` | openWakeWord (`hey_jarvis`) comparison harness |
| `recordings/*.wav.wav` | Only test audio in the repo |
| `PROJECT_CONTEXT.md` | Previous developer's audit + constraints (partially stale) |
| `app/src/main/java/com/example/echo/net/GeminiConfig.kt` | Single source of truth for Gemini model name |
| `app/src/main/java/com/example/echo/PicovoiceConfig.kt` | Legacy; safe to delete once Porcupine dependency removed |

---

## 31. Sensitive / "DO NOT CHANGE without understanding" areas

- **Arm flow (`MainActivity.armEcho`) and `startForeground` in `Service.onCreate` before any mic access.** Moving `startForeground` later, or starting the service from any background context, will crash on Android 14 (`ForegroundServiceStartNotAllowedException` / `SecurityException`). `CONFIRMED` design; OS behaviour `INFERRED`.
- **Processing order gain → WAV → HPF → Sherpa.** The benchmark script depends on the WAV being post-gain/pre-HPF and on Sherpa receiving post-HPF audio. Changing order silently invalidates the benchmark comparison.
- **`enableAutoNoiseSuppression = false`.** Re-enabling it repeats a rejected experiment (E6).
- **`audioSource = MIC`.** Switching to `VOICE_RECOGNITION` repeats E4.
- **KWS config values (§10) and `keywords.txt`.** Any change must be mirrored in `run_sherpa_benchmark.py` and re-benchmarked; keyword tokens must exist in `tokens.txt`.
- **`isCapturing` guard and teardown order in `stopAudioCapture()`** (join thread → finalize WAV → stop/release AudioRecord → release Sherpa). Verified under stress per PROJECT_CONTEXT.md; reordering risks writing to a closed file or JNI use-after-release.
- **`GeminiConfig.MODEL_NAME`** must remain the only model string.
- **Git LFS attribute for the AAR.**
- **Manifest `exported` flags** on the two services.

---

## 32. Open questions (`UNKNOWN`)

1. Do the two recordings actually contain "Echo" utterances, how many, and where? Was on-device detection ever observed in Logcat? (No evidence in repo.)
2. Does the Android 1.12.21 AAR produce different KWS results than Python 1.13.7 for the same audio/config?
3. What were the results of the openWakeWord comparison, and what decision did they drive?
4. Was the 150 Hz HPF measured to help, hurt, or neither?
5. What is the A23's actual `bufferSizeInFrames` at 16 kHz (determines chunk size/latency)?
6. Does the release (R8) build initialise Sherpa correctly on device?
7. How will the Gemini API key be provisioned and stored?
8. Which speaker-verification model is intended, and what enrolment UX?
9. Is `PROJECT_CONTEXT.md` meant to be kept authoritative (it currently lags the code)?
10. Is any Windows-specific tooling (e.g. `sherpa-onnx-cli text2token`) expected on the dev machine for generating new `keywords.txt` entries?

---

## 33. Future architecture (`PLANNED`, synthesised from context; not implemented)

```
MainActivity (arm/unarm, enrolment, settings)
   │ startForegroundService
EchoForegroundService
   ├─ AudioCapture (AudioRecord 16k mono) ─► DSP (gain, HPF) ─► KeywordSpotter
   │                                                │ wake
   │                                                ▼
   ├─ VoiceAuthenticator (on-device speaker embedding vs enrolled) ── reject → back to KWS
   │                                                │ accept
   │                                                ▼
   ├─ CommandListener (Sherpa streaming ASR + end-pointing)
   │                                                │ text
   │                                                ▼
   ├─ GeminiApiClient (Retrofit/OkHttp, GeminiConfig.MODEL_NAME, function-calling)
   │                                                │ reply / action
   │                          ┌─────────────────────┴─────────────────────┐
   ├─ TtsSpeaker (Android TextToSpeech)                          ActionExecutor
   │                                                        (Intents: call/app/message;
   │                                                         EchoAccessibilityService for UI automation;
   │                                                         Google REST APIs via OAuth tokens in
   │                                                         EncryptedSharedPreferences; RAM-only data)
   └─ Memory (MiniLM embeddings + SQLite FTS5)  [step 10]
```

Design constraints that must hold: official APIs only; no boot auto-start; tokens encrypted; Google data in RAM; single mic pipeline (no second `AudioRecord`, no `PorcupineManager`-style engines that own the mic).

---

## 34. Definition of done — next milestone ("wake word proven")

The next implementation phase should be considered done only when **all** of the following are true and evidenced in the repository:

1. Annotated test audio exists with ground-truth "Echo" timestamps and negative speech.
2. `run_sherpa_benchmark.py` (pinned to 1.12.21, path-portable) reports ≥ N/N true detections and 0 false accepts on the annotated set at the committed `keywords.txt` / threshold / score values, with the output committed.
3. On the Galaxy A23, Logcat shows `ECHO WAKE DETECTED!` for each spoken "Echo" in a scripted session and none for control phrases; the log is committed or summarised in the repo.
4. The debug WAV capture is disabled by default (or gated to debug builds) and no raw audio accumulates on device.
5. Porcupine dependency and `PicovoiceConfig.kt` are removed; `proguard-rules.pro` exists with Sherpa keep rules; release APK verified to initialise Sherpa on device.
6. `PROJECT_CONTEXT.md` (or this document) is updated so the phase list matches the code.
7. No new permissions, no boot receiver, no second microphone pipeline introduced.

---

## 35. Appendix A — Reconnaissance environment and reproduction commands

Environment used: Ubuntu Linux x86_64, JDK 17, Android SDK installed to
`$HOME/android-sdk` (platform 34, build-tools 34.0.0, platform-tools),
`git-lfs` present, Python 3 with `numpy`, `sherpa-onnx==1.13.7`, `pypdf`.

```bash
git clone https://github.com/vinamra-1/ECHO.git && cd ECHO
git lfs pull                                   # ensure the AAR is real, not a pointer
export ANDROID_HOME=$HOME/android-sdk
sh gradlew :app:assembleDebug   --console=plain
sh gradlew :app:assembleRelease --console=plain
```

Offline KWS baseline (POSIX; script paths in the repo are Windows-relative):

```bash
pip install "sherpa-onnx==1.12.21" numpy      # match the AAR version
cd recordings
# edit MODEL_DIR to Path("../app/src/main/assets/kws") or run a copy with POSIX paths
python run_sherpa_benchmark.py close_range.wav.wav far_field_fan.wav.wav
```

Recording inspection (values in §13.2 were produced this way):

```bash
python - <<'EOF'
import wave, numpy as np
for f in ["recordings/close_range.wav.wav","recordings/far_field_fan.wav.wav"]:
    w=wave.open(f); x=np.frombuffer(w.readframes(w.getnframes()),dtype=np.int16).astype(np.float64)
    print(f, w.getnchannels(), w.getsampwidth()*8, w.getframerate(), w.getnframes(),
          round(w.getnframes()/w.getframerate(),2), "s  rms", round(np.sqrt((x**2).mean()),1),
          "peak", int(np.abs(x).max()), "clipped", int((np.abs(x)>=32767).sum()))
EOF
```

End of document.
