package com.example.echo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {

    private val permissionRequestCode = 100
    private val tag = "EchoAudio"
    private var isArmed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        Log.i(tag, "MainActivity.onCreate")

        val statusText = findViewById<TextView>(R.id.txtServiceStatus)
        val armButton = findViewById<Button>(R.id.btnArmEcho)

        // IMPORTANT (Android 14 architecture fix):
        // A microphone-type foreground service cannot be started while the app
        // is in the background. It MUST be started from a foreground context —
        // e.g. this button tap. There is no BOOT_COMPLETED auto-start for the
        // mic service in this app, and there will not be one; that path is
        // blocked by the OS. See PROJECT_CONTEXT.md, "Arm Echo flow".
        armButton.setOnClickListener {
            if (isArmed) {
                Log.i(tag, "Unarm Echo button tapped")
                unarmEcho(statusText, armButton)
            } else {
                Log.i(tag, "Arm Echo button tapped")
                armEcho(statusText, armButton)
            }
        }

        findViewById<Button>(R.id.btnEnableAccessibility).setOnClickListener {
            // User must manually flip this on in system settings; Android does not
            // allow apps to enable their own AccessibilityService programmatically.
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
    }

    private fun requiredPermissions(): Array<String> {
        // POST_NOTIFICATIONS only exists/matters on API 33+; RECORD_AUDIO always required.
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        } else {
            arrayOf(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun missingPermissions(): List<String> =
        requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

    private fun armEcho(statusText: TextView, armButton: Button) {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) {
            Log.i(tag, "Requesting missing permissions: $missing")
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), permissionRequestCode)
            return
        }

        Log.i(tag, "All permissions granted — calling startForegroundService")
        try {
            // We are in the foreground right now (user just tapped a button),
            // so this call is legal under Android 14's while-in-use rules.
            ContextCompat.startForegroundService(this, Intent(this, EchoForegroundService::class.java))
            Log.i(tag, "startForegroundService() returned without throwing")
            isArmed = true
            statusText.text = "Status: armed (listening)"
            armButton.text = "Unarm Echo (stop listening)"
        } catch (e: Exception) {
            // If this throws (e.g. ForegroundServiceStartNotAllowedException on some
            // OEM/Android versions), we want it in Logcat, not a silent failure.
            Log.e(tag, "startForegroundService() THREW: ${e.javaClass.simpleName}: ${e.message}", e)
            statusText.text = "Status: FAILED to arm — see Logcat tag EchoAudio"
        }
    }

    private fun unarmEcho(statusText: TextView, armButton: Button) {
        // stopService() triggers the service's onDestroy(), which stops the
        // AudioRecord capture thread and releases the microphone.
        val stopped = stopService(Intent(this, EchoForegroundService::class.java))
        Log.i(tag, "stopService() returned $stopped")
        isArmed = false
        statusText.text = "Status: not armed"
        armButton.text = "Arm Echo (start listening)"
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == permissionRequestCode) {
            Log.i(tag, "onRequestPermissionsResult: ${permissions.toList()} -> ${grantResults.toList()}")
            if (missingPermissions().isEmpty()) {
                armEcho(findViewById(R.id.txtServiceStatus), findViewById(R.id.btnArmEcho))
            } else {
                findViewById<TextView>(R.id.txtServiceStatus).text =
                    "Status: permission denied — cannot arm"
            }
        }
    }
}
