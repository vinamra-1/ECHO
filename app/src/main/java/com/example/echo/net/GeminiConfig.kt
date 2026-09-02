package com.example.echo.net

/**
 * Central place for which Gemini model Echo talks to.
 *
 * DO NOT hardcode a model string anywhere else in the codebase.
 * Gemini 1.5 models are fully retired (calls now 404). As of this
 * writing, reasonable choices are:
 *   - "gemini-2.5-flash"       (stable, thinking-capable)
 *   - "gemini-flash-latest"    (rolling alias, auto-tracks newest stable Flash)
 *
 * Before shipping, re-check https://ai.google.dev/gemini-api/docs/models
 * for current availability — Google deprecates models on its own schedule
 * independent of this codebase.
 */
object GeminiConfig {
    const val MODEL_NAME: String = "gemini-flash-latest"

    // Base endpoint; the Interactions API may eventually replace raw
    // generateContent calls as Google's default interface. Verify the
    // current request/response shape against the docs above before
    // wiring GeminiApiClient's actual network calls.
    const val BASE_URL: String = "https://generativelanguage.googleapis.com/"
}
