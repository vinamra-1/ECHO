import sys
import wave
from pathlib import Path

import numpy as np
import sherpa_onnx


# ============================================================
# ECHO PHASE 3B-1
# EXACT SHERPA-ONNX KWS BASELINE
# ============================================================

SAMPLE_RATE = 16000

MODEL_DIR = Path(
    r"..\app\src\main\assets\kws"
)

ENCODER = MODEL_DIR / (
    "encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
)

DECODER = MODEL_DIR / (
    "decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
)

JOINER = MODEL_DIR / (
    "joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx"
)

TOKENS = MODEL_DIR / "tokens.txt"
KEYWORDS = MODEL_DIR / "keywords.txt"


# ============================================================
# CURRENT ANDROID ECHO SETTINGS
# ============================================================

NUM_THREADS = 2
PROVIDER = "cpu"

MAX_ACTIVE_PATHS = 4
KEYWORDS_SCORE = 3.0
KEYWORDS_THRESHOLD = 0.1
NUM_TRAILING_BLANKS = 1

MIC_GAIN = 2.5
HIGH_PASS_CUTOFF = 150.0


# ============================================================
# READ WAV
# ============================================================

def read_wav(filename):

    with wave.open(str(filename), "rb") as wf:

        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        sample_rate = wf.getframerate()

        frames = wf.getnframes()
        raw = wf.readframes(frames)

    if channels != 1:
        raise ValueError(
            f"{filename}: expected mono, got {channels} channels"
        )

    if sample_width != 2:
        raise ValueError(
            f"{filename}: expected 16-bit PCM"
        )

    if sample_rate != SAMPLE_RATE:
        raise ValueError(
            f"{filename}: expected 16000 Hz, got {sample_rate} Hz"
        )

    audio = np.frombuffer(
        raw,
        dtype=np.int16
    ).astype(np.float32)

    return audio / 32768.0


# ============================================================
# HIGH-PASS FILTER
#
# Matches the one-pole IIR filter in EchoForegroundService.kt
# ============================================================

def high_pass_filter(samples):

    dt = 1.0 / SAMPLE_RATE

    rc = 1.0 / (
        2.0 * np.pi * HIGH_PASS_CUTOFF
    )

    alpha = rc / (rc + dt)

    output = np.empty_like(samples)

    prev_in = 0.0
    prev_out = 0.0

    for i, current_in in enumerate(samples):

        current_out = alpha * (
            prev_out
            + current_in
            - prev_in
        )

        output[i] = current_out

        prev_in = current_in
        prev_out = current_out

    return output


# ============================================================
# APPLY ANDROID AUDIO PIPELINE
#
# Android:
#
# PCM16
#   ↓
# 2.5x gain
#   ↓
# float conversion
#   ↓
# 150 Hz high-pass
#   ↓
# Sherpa
# ============================================================

def prepare_audio(audio):

    # Convert normalized float back to the equivalent
    # PCM16 domain used by Android gain processing.

    pcm = np.clip(
        audio * 32768.0,
        -32768,
        32767
    )

    # Android:
    #
    # boosted = buffer[i] * micGain
    # coerceIn(Short.MIN_VALUE, Short.MAX_VALUE)

    pcm = np.clip(
        pcm * MIC_GAIN,
        -32768,
        32767
    )

    # Android converts the gained ShortArray to float:
    #
    # buffer[i] / 32768.0f

    samples = pcm / 32768.0

    # Android applies the 150 Hz high-pass
    # immediately before Sherpa.

    samples = high_pass_filter(
        samples.astype(np.float32)
    )

    return samples


# ============================================================
# CREATE SHERPA KWS
# ============================================================

def create_keyword_spotter():

    print()
    print("Loading exact Echo Sherpa model...")
    print()

    print("Encoder :", ENCODER)
    print("Decoder :", DECODER)
    print("Joiner  :", JOINER)
    print("Tokens  :", TOKENS)
    print("Keywords:", KEYWORDS)

    for path in [
        ENCODER,
        DECODER,
        JOINER,
        TOKENS,
        KEYWORDS,
    ]:

        if not path.exists():

            raise FileNotFoundError(
                f"Required file not found:\n{path}"
            )

    kws = sherpa_onnx.KeywordSpotter(
        tokens=str(TOKENS),
        encoder=str(ENCODER),
        decoder=str(DECODER),
        joiner=str(JOINER),

        num_threads=NUM_THREADS,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,

        max_active_paths=MAX_ACTIVE_PATHS,

        keywords_score=KEYWORDS_SCORE,
        keywords_threshold=KEYWORDS_THRESHOLD,

        num_trailing_blanks=NUM_TRAILING_BLANKS,

        keywords_file=str(KEYWORDS),

        provider=PROVIDER,
    )

    return kws


# ============================================================
# RUN ONE FILE
# ============================================================

def run_file(kws, filename):

    print()
    print("=" * 70)
    print(f"FILE: {filename}")
    print("=" * 70)

    audio = read_wav(filename)

    duration = len(audio) / SAMPLE_RATE

    print()
    print("WAV")
    print("-" * 60)
    print(f"Sample rate : {SAMPLE_RATE} Hz")
    print("Channels    : 1")
    print("Bit depth   : 16-bit")
    print(f"Samples     : {len(audio)}")
    print(f"Duration    : {duration:.2f} seconds")

    print()
    print("ANDROID PIPELINE")
    print("-" * 60)
    print(f"Microphone gain : {MIC_GAIN}x")
    print(f"High-pass       : {HIGH_PASS_CUTOFF} Hz")
    print("NoiseSuppressor : OFF")

    # ========================================================
    # PREPARE AUDIO
    # ========================================================

    samples = prepare_audio(audio)

    print()
    print("PROCESSED AUDIO")
    print("-" * 60)

    rms = np.sqrt(
        np.mean(samples ** 2)
    )

    peak = np.max(
        np.abs(samples)
    )

    print(f"RMS             : {rms:.6f}")
    print(f"Peak            : {peak:.6f}")

    # ========================================================
    # CREATE STREAM
    # ========================================================

    stream = kws.create_stream()

    # Feed the whole recording.
    #
    # Sherpa's official Python example uses accept_waveform,
    # then tail padding, input_finished, and decode_stream.
    # We do the same here.

    stream.accept_waveform(
        SAMPLE_RATE,
        samples
    )

    # Tail padding allows the model to finish processing
    # a keyword near the end of the recording.

    tail_padding = np.zeros(
        int(0.66 * SAMPLE_RATE),
        dtype=np.float32
    )

    stream.accept_waveform(
        SAMPLE_RATE,
        tail_padding
    )

    stream.input_finished()

    # ========================================================
    # DECODE
    # ========================================================

    detections = []

    while kws.is_ready(stream):

        kws.decode_stream(stream)

        result = kws.get_result(stream)

        if result != "":

            detections.append(result)

            print()
            print("!!! SHERPA WAKE DETECTED !!!")
            print(
                f"Keyword: {result}"
            )

            # Same behavior as Android:
            # reset immediately after detection.

            kws.reset_stream(stream)

    # ========================================================
    # RESULTS
    # ========================================================

    print()
    print("=" * 70)
    print("RESULT")
    print("=" * 70)

    print(
        f"Detections: {len(detections)}"
    )

    if detections:

        for i, detection in enumerate(
            detections,
            1
        ):

            print(
                f"    {i:02d}. {detection}"
            )

    else:

        print(
            "    No Sherpa wake detections."
        )

    return detections


# ============================================================
# MAIN
# ============================================================

def main():

    if len(sys.argv) != 3:

        print()
        print("Usage:")
        print(
            "python run_sherpa_benchmark.py "
            "close.wav far_fan.wav"
        )
        print()

        sys.exit(1)

    close_file = Path(sys.argv[1])
    far_file = Path(sys.argv[2])

    for filename in [
        close_file,
        far_file,
    ]:

        if not filename.exists():

            print()
            print(
                f"ERROR: File not found: {filename}"
            )
            print()

            sys.exit(1)

    print("=" * 70)
    print("ECHO PHASE 3B-1")
    print("EXACT SHERPA-ONNX BASELINE")
    print("=" * 70)

    print()
    print("Sherpa-ONNX configuration:")
    print()
    print(f"Threads             : {NUM_THREADS}")
    print(f"Provider             : {PROVIDER}")
    print(f"Max active paths     : {MAX_ACTIVE_PATHS}")
    print(f"Keywords score       : {KEYWORDS_SCORE}")
    print(f"Keywords threshold   : {KEYWORDS_THRESHOLD}")
    print(f"Trailing blanks      : {NUM_TRAILING_BLANKS}")
    print(f"Mic gain              : {MIC_GAIN}x")
    print(f"High-pass             : {HIGH_PASS_CUTOFF} Hz")
    print()

    try:

        kws = create_keyword_spotter()

    except Exception as e:

        print()
        print("=" * 70)
        print("FAILED TO CREATE SHERPA KEYWORD SPOTTER")
        print("=" * 70)
        print()
        print(type(e).__name__)
        print(e)
        print()

        sys.exit(1)

    print()
    print("Sherpa KeywordSpotter created successfully.")

    close_results = run_file(
        kws,
        close_file
    )

    far_results = run_file(
        kws,
        far_file
    )

    # ========================================================
    # FINAL SUMMARY
    # ========================================================

    print()
    print()
    print("=" * 70)
    print("PHASE 3B-1 FINAL SUMMARY")
    print("=" * 70)

    print()
    print(
        f"Close range detections : "
        f"{len(close_results)}"
    )

    print(
        f"Far + fan detections   : "
        f"{len(far_results)}"
    )

    print()
    print("CURRENT SHERPA ANDROID SETTINGS")
    print("-" * 70)
    print(f"Gain                  : {MIC_GAIN}x")
    print(f"High-pass             : {HIGH_PASS_CUTOFF} Hz")
    print(f"NoiseSuppressor       : OFF")
    print(f"Max active paths      : {MAX_ACTIVE_PATHS}")
    print(f"Keywords score        : {KEYWORDS_SCORE}")
    print(f"Keywords threshold    : {KEYWORDS_THRESHOLD}")
    print(f"Trailing blanks       : {NUM_TRAILING_BLANKS}")

    print()
    print("=" * 70)
    print("PHASE 3B-1 COMPLETE")
    print("=" * 70)
    print()


if __name__ == "__main__":
    main()
