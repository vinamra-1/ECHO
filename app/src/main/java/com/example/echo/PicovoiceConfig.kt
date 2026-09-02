package com.example.echo

/**
 * Picovoice/Porcupine configuration.
 *
 * SECURITY: Do not commit a real AccessKey to source control. Paste your
 * key here locally only, or better, load it from a local.properties /
 * BuildConfig field that's gitignored. For this project's current stage
 * (personal device testing, not distributed), a local-only constant is
 * acceptable — just don't push it to a public repo.
 *
 * Get your AccessKey from: https://console.picovoice.ai
 */
object PicovoiceConfig {
    // TODO: paste your real AccessKey here before building Phase 3
    const val ACCESS_KEY: String = "PASTE_YOUR_PICOVOICE_ACCESS_KEY_HERE"

    // Filename of your custom "Echo" keyword file, relative to
    // app/src/main/assets/. Must be the ANDROID-targeted .ppn from
    // console.picovoice.ai — a .ppn built for iOS/Web/Windows will fail
    // to initialize here.
    const val KEYWORD_FILE: String = "echo_android.ppn"
}
