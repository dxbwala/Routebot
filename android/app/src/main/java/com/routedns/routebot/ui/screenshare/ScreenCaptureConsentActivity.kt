package com.routedns.routebot.ui.screenshare

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import com.routedns.routebot.common.RouteBotLog
import com.routedns.routebot.media.MediaProjectionHolder

/**
 * Transparent, no-UI activity whose sole purpose is to show the system's mandatory
 * screen-capture consent dialog on behalf of a remote `take_screenshot` command, then
 * immediately finish. Android does not allow this consent to be granted without a
 * foreground activity showing it — see [MediaProjectionHolder] for details.
 */
class ScreenCaptureConsentActivity : ComponentActivity() {

    private val launcher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        MediaProjectionHolder.applyGrant(result.resultCode, if (result.resultCode == Activity.RESULT_OK) result.data else null)
        if (result.resultCode != Activity.RESULT_OK) {
            RouteBotLog.w("screen_capture_consent_denied")
        }
        finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val projectionManager = getSystemService(MediaProjectionManager::class.java)
        launcher.launch(projectionManager.createScreenCaptureIntent())
    }

    companion object {
        fun launch(context: Context) {
            val intent = Intent(context, ScreenCaptureConsentActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
