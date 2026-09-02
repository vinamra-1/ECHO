package com.example.echo

import android.app.Activity
import android.os.Bundle

/**
 * Receives the redirect after Google's OAuth consent screen.
 * Token exchange logic lands here in Phase 6 (Google OAuth & APIs)
 * — see PROJECT_CONTEXT.md.
 */
class OAuthCallbackActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // TODO Phase 6: read intent.data (auth code), exchange for tokens,
        // store via EncryptedSharedPreferences.
        finish()
    }
}
