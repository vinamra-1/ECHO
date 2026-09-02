import os
import sys
import wave
import numpy as np

from openwakeword.model import Model


# ============================================================
# ECHO â€” OPENWAKEWORD CONTROLLED GAIN BENCHMARK
# ============================================================

SAMPLE_RATE = 16000
CHUNK_SIZE = 1280          # 80 ms @ 16 kHz
EVENT_GAP_SECONDS = 1.0

THRESHOLDS = [0.20, 0.30, 0.40, 0.50, 0.60, 0.70]

# Gain conditions.
# 0 dB = original recording
# -6 dB = half amplitude
# -12 dB = quarter amplitude
GAIN_CONDITIONS = [
    ("RAW (0 dB)", 1.0),
    ("-6 dB", 10 ** (-6 / 20)),
    ("-12 dB", 10 ** (-12 / 20)),
]


# ============================================================
# FILES
# ============================================================

if len(sys.argv) != 3:
    print("Usage:")
    print("python run_oww_benchmark.py close.wav far_fan.wav")
    sys.exit(1)

FILES = [
    sys.argv[1],
    sys.argv[2],
]


# ============================================================
# LOAD WAV
# ============================================================

def load_wav(filename):
    with wave.open(filename, "rb") as wf:

        channels = wf.getnchannels()
        sample_width = wf.getsampwidth()
        sample_rate = wf.getframerate()
        frame_count = wf.getnframes()

        raw = wf.readframes(frame_count)

    if channels != 1:
        raise ValueError(
            f"{filename}: expected mono WAV, got {channels} channels"
        )

    if sample_width != 2:
        raise ValueError(
            f"{filename}: expected 16-bit PCM, got {sample_width * 8}-bit"
        )

    if sample_rate != SAMPLE_RATE:
        raise ValueError(
            f"{filename}: expected {SAMPLE_RATE} Hz, got {sample_rate} Hz"
        )

    audio = np.frombuffer(raw, dtype=np.int16).astype(np.float32)

    return audio


# ============================================================
# AUDIO STATISTICS
# ============================================================

def audio_stats(audio):

    rms = np.sqrt(np.mean(audio ** 2))
    peak = np.max(np.abs(audio))

    clipped = np.sum(np.abs(audio) >= 32767)

    clipping_percent = (
        clipped / len(audio) * 100
        if len(audio) > 0
        else 0
    )

    return rms, peak, clipped, clipping_percent


# ============================================================
# APPLY GAIN
# ============================================================

def apply_gain(audio, gain):

    processed = audio * gain

    # Keep within int16 range.
    processed = np.clip(processed, -32768, 32767)

    return processed.astype(np.int16)


# ============================================================
# RUN OPENWAKEWORD
# ============================================================

def run_oww(audio_int16, model):

    scores = []

    total_samples = len(audio_int16)

    for start in range(0, total_samples, CHUNK_SIZE):

        chunk = audio_int16[start:start + CHUNK_SIZE]

        if len(chunk) < CHUNK_SIZE:
            chunk = np.pad(
                chunk,
                (0, CHUNK_SIZE - len(chunk))
            )

        # openWakeWord expects int16 PCM.
        prediction = model.predict(chunk)

        # Model output is a dictionary containing hey_jarvis.
        score = float(prediction.get("hey_jarvis", 0.0))

        scores.append(score)

    return np.array(scores, dtype=np.float32)


# ============================================================
# DETECTION EVENTS
# ============================================================

def get_events(scores, threshold):

    events = []

    last_event_time = -999.0

    for i, score in enumerate(scores):

        if score >= threshold:

            timestamp = i * CHUNK_SIZE / SAMPLE_RATE

            if timestamp - last_event_time >= EVENT_GAP_SECONDS:

                events.append(
                    (timestamp, float(score))
                )

                last_event_time = timestamp

            else:
                # Same event â€” keep the strongest score.
                if events and score > events[-1][1]:
                    events[-1] = (
                        events[-1][0],
                        float(score)
                    )

    return events


# ============================================================
# PRINT DETECTIONS
# ============================================================

def print_threshold_results(scores):

    results = {}

    for threshold in THRESHOLDS:

        events = get_events(scores, threshold)

        results[threshold] = events

        print()
        print(f"THRESHOLD = {threshold:.2f}")
        print("-" * 60)
        print(f"Detection events: {len(events)}")

        if not events:

            print("    No detections above threshold.")

        else:

            for number, (timestamp, score) in enumerate(events, 1):

                print(
                    f"    {number:02d}. "
                    f"{timestamp:7.2f}s  "
                    f"score={score:.4f}"
                )

    return results


# ============================================================
# MAIN
# ============================================================

def main():

    print("=" * 70)
    print("ECHO PHASE 3")
    print("openWakeWord HEY JARVIS CONTROLLED GAIN BENCHMARK")
    print("=" * 70)

    print()
    print("Loading Hey Jarvis model...")
    print()

    try:

        model = Model(
            wakeword_models=["hey_jarvis"],
            inference_framework="onnx"
        )

    except Exception as e:

        print()
        print("ERROR loading openWakeWord model:")
        print(e)
        print()
        print("If the model is missing, run:")
        print()
        print(
            "python -c "
            "\"import openwakeword; "
            "openwakeword.utils.download_models("
            "model_names=['hey_jarvis_v0.1']); "
            "print('MODELS DOWNLOADED')\""
        )

        sys.exit(1)

    print("Hey Jarvis model loaded successfully.")

    print()
    print("=" * 70)
    print("CONTROLLED TEST")
    print("=" * 70)
    print()
    print("Each recording will be tested at:")
    print()
    print("    RAW  (0 dB)")
    print("    -6 dB")
    print("    -12 dB")
    print()
    print("Thresholds:")
    print("    0.20  0.30  0.40  0.50  0.60  0.70")
    print()
    print("Original WAV files will NOT be modified.")
    print()

    all_results = {}

    # ========================================================
    # PROCESS EACH FILE
    # ========================================================

    for filename in FILES:

        print()
        print("=" * 70)
        print(f"FILE: {filename}")
        print("=" * 70)

        if not os.path.exists(filename):

            print()
            print("ERROR: File not found:")
            print(os.path.abspath(filename))
            print()

            continue

        audio = load_wav(filename)

        duration = len(audio) / SAMPLE_RATE

        rms, peak, clipped, clipping_percent = audio_stats(audio)

        print()
        print("WAV FORMAT")
        print("-" * 60)
        print(f"Sample rate : {SAMPLE_RATE} Hz")
        print("Channels    : 1")
        print("Bit depth   : 16-bit")
        print(f"Samples     : {len(audio)}")
        print(f"Duration    : {duration:.2f} seconds")

        print()
        print("ORIGINAL AUDIO STATISTICS")
        print("-" * 60)
        print(f"RMS             : {rms:.1f}")
        print(f"Peak            : {peak:.0f}")
        print(f"Clipped samples : {clipped}")
        print(f"Clipping        : {clipping_percent:.3f}%")

        file_results = {}

        # ====================================================
        # GAIN CONDITIONS
        # ====================================================

        for condition_name, gain in GAIN_CONDITIONS:

            print()
            print()
            print("#" * 70)
            print(f"GAIN CONDITION: {condition_name}")
            print("#" * 70)

            processed = apply_gain(audio, gain)

            proc_rms, proc_peak, proc_clipped, proc_clip_percent = (
                audio_stats(processed.astype(np.float32))
            )

            print()
            print("AUDIO STATISTICS")
            print("-" * 60)
            print(f"RMS             : {proc_rms:.1f}")
            print(f"Peak            : {proc_peak:.0f}")
            print(f"Clipped samples : {proc_clipped}")
            print(f"Clipping        : {proc_clip_percent:.3f}%")

            print()
            print("Running openWakeWord...")

            scores = run_oww(processed, model)

            max_index = int(np.argmax(scores))
            max_score = float(scores[max_index])
            max_timestamp = max_index * CHUNK_SIZE / SAMPLE_RATE

            print()
            print("=" * 70)
            print("SCORE SUMMARY")
            print("=" * 70)
            print(f"Maximum Hey Jarvis score : {max_score:.4f}")
            print(
                f"Maximum score timestamp   : "
                f"{max_timestamp:.2f}s"
            )

            threshold_results = print_threshold_results(scores)

            file_results[condition_name] = {
                "max_score": max_score,
                "max_timestamp": max_timestamp,
                "thresholds": threshold_results,
            }

        all_results[filename] = file_results

    # ========================================================
    # FINAL COMPARISON
    # ========================================================

    print()
    print()
    print("=" * 70)
    print("FINAL GAIN COMPARISON")
    print("=" * 70)

    for filename, conditions in all_results.items():

        print()
        print(filename)
        print("-" * 70)

        print(
            f"{'Condition':<15}"
            f"{'Peak Score':<15}"
            f"{'@ 0.20':<10}"
            f"{'@ 0.30':<10}"
            f"{'@ 0.40':<10}"
            f"{'@ 0.50':<10}"
            f"{'@ 0.60':<10}"
            f"{'@ 0.70':<10}"
        )

        print("-" * 70)

        for condition_name, data in conditions.items():

            counts = []

            for threshold in THRESHOLDS:

                count = len(
                    data["thresholds"][threshold]
                )

                counts.append(count)

            print(
                f"{condition_name:<15}"
                f"{data['max_score']:<15.4f}"
                f"{counts[0]:<10}"
                f"{counts[1]:<10}"
                f"{counts[2]:<10}"
                f"{counts[3]:<10}"
                f"{counts[4]:<10}"
                f"{counts[5]:<10}"
            )

    # ========================================================
    # CONCLUSION
    # ========================================================

    print()
    print()
    print("=" * 70)
    print("REFERENCE â€” CURRENT SHERPA BASELINE")
    print("=" * 70)
    print()
    print("Close range : approximately 7â€“8 / 10")
    print("Far + fan   : approximately 3â€“4 / 10")

    print()
    print("=" * 70)
    print("BENCHMARK COMPLETE")
    print("=" * 70)
    print()
    print("IMPORTANT:")
    print("Do not change Echo's Android KWS yet.")
    print("Use the results above to decide the next experiment.")
    print()


if __name__ == "__main__":
    main()
