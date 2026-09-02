# Echo — Project Context

Paste this file into any AI tool before asking it to touch this codebase.
Last updated: after Architecture Audit #1 (see below). Read the audit
section before writing any code — it corrects several assumptions in
the original blueprint PDF that are no longer true.

## What Echo is
An on-device Android 14 voice assistant for a Galaxy A23 (Snapdragon 680).
User-armed wake-word listening → local voice verification → cloud LLM
(Gemini) for reasoning → executes actions via official Android APIs
(intents, Accessibility Service, Google REST APIs). No root. Total app
footprint budget: under 300MB (unverified — measure, don't assume).

## Architecture Audit #1 — corrections to the original blueprint

1. **No true "always listening from boot."** Android 14 blocks starting a
   microphone-type foreground service from the background. There is no
   `BOOT_COMPLETED` receiver in this app and there will not be one for the
   mic service — that path is a dead end on this OS version. Instead:
   the user taps "Arm Echo" in `MainActivity` (a foreground UI action),
   which calls `ContextCompat.startForegroundService(...)`. This is
   already implemented. If the OS kills the service later (battery
   optimization, memory pressure), it requires the user to re-arm it —
   document this limitation to the user, don't silently pretend otherwise.

2. **No "Knox 0x0 guarantee" wording anywhere** — in code comments, UI
   copy, or docs. Knox eFuse status is Samsung hardware/firmware state,
   not something app code can assert. Correct phrasing: "designed to
   avoid root, bootloader unlock, or custom kernels" — a description of
   what the app does, not a promise about device state.

3. **Silero speaker-ID model is unverified.** Before writing
   `VoiceAuthenticator.kt` for real, open the actual chosen ONNX file in
   Netron and confirm: input tensor shape, output tensor shape (is it
   really a 256-dim d-vector?), and required preprocessing (mel filterbank
   params, sample rate, normalization). Do not assume the blueprint's
   claims about this model are accurate — verify against the model file
   and its card directly.

4. **Gemini model name is centralized.** Never hardcode a model string
   in `GeminiApiClient.kt` or anywhere else — use
   `com.example.echo.net.GeminiConfig.MODEL_NAME`. Gemini 1.5 models are
   fully retired (404 on call). Current default here is
   `"gemini-flash-latest"`. Re-verify against
   https://ai.google.dev/gemini-api/docs/models before each release —
   Google deprecates on its own schedule. Also check whether the
   Interactions API (Google's newer default interface) changes the
   request/response shape before finalizing the client.

5. **Google Photos voice-search feature is out of scope for v1.** As of
   March 31, 2025, Google removed the Library API scopes/search needed
   to query a user's *existing* camera roll by content category or date
   (`mediaItems:search` with `contentFilter`/`dateFilter` no longer works
   for arbitrary library content — only app-uploaded content). The
   Picker API is the replacement, but it requires manual user selection
   through a Google-provided UI each time; it cannot be silently
   triggered by voice and return results. Do not implement the "show me
   photos of dogs" flow as originally scoped. If Photos integration is
   wanted at all in v1, scope it down to: Echo launches the Photo Picker
   Intent for the user, nothing more automated than that.

6. **All storage figures are unverified placeholders.** Don't design
   around specific MB numbers (MiniLM 25MB, Sherpa 35MB, etc.) from the
   blueprint. After each phase that adds a model or library, run
   `./gradlew assembleRelease` and check the real APK size with APK
   Analyzer before proceeding.

## Current status
**Phase 1: COMPLETE** — verified on physical Galaxy A23 hardware.
**Phase 2: COMPLETE** — verified on physical Galaxy A23 hardware via
Logcat, not just build-time. Confirmed via actual log output:
- `startForegroundService()` succeeds, no exceptions thrown
- `AudioRecord` initializes at 16kHz on first try (A23 supports it
  natively — the 44.1kHz fallback path exists but was not needed)
- `state=1` (STATE_INITIALIZED), `recordingState=3` (RECORDSTATE_RECORDING)
- `level=` values genuinely fluctuate over a sustained multi-minute run
  (baseline ~130-300, spikes to 1000-3500+ on louder sound) — this is
  real audio, not a static/fake number
- Tapping "Arm Echo" a second time while already running correctly
  no-ops (the `isCapturing` guard prevents double-init) instead of
  crashing or double-starting the mic
- Two real bugs also found and fixed along the way: a stray
  `BIND_NOTIFICATION_LISTENER_SERVICE` uses-permission (removed) and
  missing `POST_NOTIFICATIONS` for API 33+ (added, now requested at runtime)

**Phase 2b: COMPLETE** — Arm/Unarm toggle verified on physical hardware,
including a rapid-fire stress test (~12 toggles in under 10 seconds, several
overlapping taps where a new Arm fired before the previous cycle's
onDestroy() finished). Result: every cycle initialized cleanly at 16000 Hz
(state=1, recordingState=3) and stopped cleanly ("Audio capture stopped"),
with zero exceptions, zero STATE_INITIALIZED failures, and no leaked
AudioRecord instances. The isCapturing guard and stopService()/onDestroy()
path hold up under real-world messy usage, not just a clean single cycle.

**Phase 3: IN PROGRESS, MID-SWAP.** Originally built on Porcupine (below),
but **Picovoice discontinued its Free Tier entirely on June 30, 2026**
(confirmed via web search — not a misunderstanding, the product actually
changed), making it unusable for a no-budget personal project. Swapping to
**sherpa-onnx's open-vocabulary keyword spotting** instead: free forever,
open source (Apache 2.0), no account/AccessKey, runs fully on-device, and
already part of the original blueprint's tech stack (was planned for STT).
Architecturally the swap is clean — sherpa-onnx's `KeywordSpotter` API also
takes audio frames you feed it (like Porcupine's low-level API), so the
Phase 2 AudioRecord pipeline stays untouched either way.

Waiting on user to download: (1) sherpa-onnx Android AAR from
huggingface.co/csukuangfj/sherpa-onnx-libs (android/aar/ folder), (2) the
English KWS model `sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01`.
Once downloaded, next code step is wiring `com.k2fsa.sherpa.onnx.KeywordSpotter`
+ `KeywordSpotterConfig` + `OnlineStream` into the existing low-level
AudioRecord loop, then converting "ECHO" into a keywords.txt entry via the
sherpa-onnx-cli text2token tool (needs Python on the dev machine). The
Porcupine code below is the CURRENT state in the codebase but will be
REMOVED once sherpa-onnx is wired in — do not extend or fix Porcupine code
further, it's being replaced, not maintained.

Historical Porcupine implementation notes (for reference until removed):
- Uses Porcupine's LOW-LEVEL API (`Porcupine.Builder` + `.process(frame)`),
  NOT `PorcupineManager`. PorcupineManager owns its own AudioRecord
  internally and would conflict with/replace the verified Phase 2 mic
  pipeline. Never introduce PorcupineManager into this codebase.
- Pinned to `ai.picovoice:porcupine-android:4.0.2` (verified current as of
  integration date via mvnrepository.com — re-check before bumping).
- `PicovoiceConfig.kt` holds the AccessKey and keyword filename — the
  AccessKey is a placeholder (`PASTE_YOUR_PICOVOICE_ACCESS_KEY_HERE`) that
  must be filled in locally and NEVER committed to a public repo.
- The `.ppn` keyword file must be Android-targeted (selected on
  console.picovoice.ai before training) and placed at
  `app/src/main/assets/echo_android.ppn` — a `.ppn` built for another
  platform will fail Porcupine's init with an exception, not a crash.
- Capture loop reads AudioRecord in exact `porcupine.frameLength`-sized
  chunks when Porcupine is active (not `bufferSizeInFrames`) — feeding
  Porcupine the wrong size throws `IllegalArgumentException`. Deliberately
  reads exact frames rather than splitting a larger read into slices, to
  avoid discarding leftover samples between reads (which would create
  small audio gaps and hurt detection reliability).
- If AudioRecord ends up on the 44.1kHz fallback rate instead of 16kHz,
  Porcupine init is skipped entirely (logged as a warning) rather than
  attempted — Porcupine requires exactly 16kHz.
- Wake detection currently only logs `ECHO WAKE DETECTED` (Log.e for
  visibility) — no STT, no Gemini, no stopping the loop. This is
  deliberately minimal to first prove reliable triggering alone.
- Porcupine init failures (bad key, wrong-platform .ppn, missing asset)
  degrade gracefully — level logging keeps working even if wake-word
  detection can't initialize, so one broken piece doesn't kill the other.
- `porcupine.delete()` is called in `stopAudioCapture()` — Porcupine holds
  native resources that leak if not explicitly freed (unlike AudioRecord's
  `release()` pattern, which this mirrors).

**Verification checklist for Phase 3:**
- [ ] Paste real AccessKey into `PicovoiceConfig.kt` (locally, don't commit)
- [ ] Place Android-targeted `echo_android.ppn` in `app/src/main/assets/`
- [ ] Build succeeds
- [ ] Logcat shows `Porcupine initialized: frameLength=..., sampleRate=16000`
- [ ] Say random words → no `ECHO WAKE DETECTED` line
- [ ] Say "Echo" → `ECHO WAKE DETECTED` appears
- [ ] Repeat several times → triggers reliably, no false positives on
      normal conversation

## Full Project Vision (confirmed requirements)
ECHO is a full conversational voice assistant, not just a wake-word demo.
End-to-end flow once complete:
```
🎤 Mic → 👂 "Echo" detected (sherpa-onnx KWS) → 👤 Verify it's the
authorized user's voice (speaker verification) → 🎙️ Listen to the actual
command (STT) → 🧠 AI response (Gemini) → 🔊 Speak back (TTS) →
[optionally] perform a phone action (call, open app, send message)
```

Confirmed requirements (treat as architecture constraints going forward):
- [x] "Echo" custom wake word — sherpa-onnx KWS, open-vocabulary (no retraining)
- [x] Wake-word detection works fully offline, on-device
- [x] No Picovoice / no paid dependency anywhere in the wake-word path
- [x] Improve weak mic sensitivity — `micGain` boost implemented in
      `EchoForegroundService` (default 2.5x, clipping-safe), plus
      `audioSource` made a one-line-swap constant (VOICE_RECOGNITION vs
      MIC) for A/B testing on the actual A23. Tuning ongoing based on
      real-world testing, not finalized.
- [ ] Handle noisy environments as well as reasonably possible
- [ ] **Speaker verification**: only the authorized user's voice should wake
      ECHO; a stranger saying "Echo" should be ignored. This is a SEPARATE
      check from wake-word detection — wake-word answers "was that the word
      Echo?", speaker verification answers "was that YOUR voice?". Keep
      on-device if practical, so voice data isn't uploaded anywhere for this
      check. Per Architecture Audit #1 point 3, the exact model (likely
      Silero-based) must be verified in Netron before writing
      `VoiceAuthenticator.kt` — do not assume input/output tensor shapes.
      Set expectations honestly: this will not be 100% accurate, especially
      on a weak mic or noisy room — tune empirically, don't promise perfection.
- [ ] Conversational interaction (not just single command-and-done)
- [ ] Real speech-to-text for full sentences (separate from wake-word;
      likely Sherpa-ONNX streaming ASR model, already part of original
      storage budget plan)
- [ ] ECHO speaks responses (Android TTS, tune only if voice quality
      becomes a real problem — don't replace prematurely)
- [ ] Phone control/actions (calls, opening apps, messages) — same Intent/
      Accessibility-based approach as the original blueprint, Knox-safe,
      official APIs only
- [ ] Eventually usable as a genuine daily assistant

## Current phase sequencing (do not skip ahead)
We are deliberately hardening the wake-word + microphone layer FIRST before
adding the conversational layer, speaker verification, STT, or TTS. Each
new capability should be proven working and tested on the real A23 before
the next one is added — this is why Phase 2/2b were fully stress-tested
before Phase 3 began, and why Phase 3 (wake-word) is being finished before
speaker verification, STT, or Gemini integration starts.
1. Setup & research — DONE
2. Base app + foreground service — DONE (Arm Echo flow implemented)
3. Architecture audit — DONE (this document)
4. Choose & verify exact ONNX models (Netron inspection) — NOT STARTED ← current step
5. VoiceAuth & wake-word (Porcupine + verified Silero model) — NOT STARTED
6. Intent & phone control (calls, media, web search) — NOT STARTED
7. Accessibility layer (UI traversal, tap/type/gesture) — NOT STARTED
8. Google OAuth & APIs (Gmail/YouTube; Photos scoped down per audit) — NOT STARTED
9. Gemini integration (function-calling schema, via GeminiConfig) — NOT STARTED
10. Offline embeddings & memory DB (MiniLM + SQLite FTS5) — NOT STARTED
11. Adaptive sensitivity router (battery/noise-based thresholds) — NOT STARTED
12. Polish, storage budget verification (measured, not assumed) — NOT STARTED

## Hard constraints to respect in any suggestion
- Storage budget ~250-300MB total, UNVERIFIED — measure after each phase
- Official Android APIs only, no root, no unsupported hacks (never claim a
  Knox device-state "guarantee" — see audit #2)
- minSdk 26, targetSdk/compileSdk 34
- OAuth tokens go in EncryptedSharedPreferences, never plain SharedPreferences
- Google API data (emails/photo picker results/video metadata) stays in RAM
  only, never persisted to SQLite
- foregroundServiceType="microphone" service must only ever be started from
  a foreground context (button tap, notification tap) — never from
  BOOT_COMPLETED or any other background trigger (see audit #1)
- Gemini model name only ever referenced via GeminiConfig.MODEL_NAME

## File map
- app/src/main/java/com/example/echo/MainActivity.kt (Arm Echo flow)
- app/src/main/java/com/example/echo/EchoForegroundService.kt
- app/src/main/java/com/example/echo/EchoAccessibilityService.kt
- app/src/main/java/com/example/echo/OAuthCallbackActivity.kt
- app/src/main/java/com/example/echo/net/GeminiConfig.kt (model name lives here)
- app/src/main/res/layout/activity_main.xml (Arm Echo button + status text)
- app/src/main/res/xml/accessibility_service_config.xml
- app/src/main/AndroidManifest.xml
