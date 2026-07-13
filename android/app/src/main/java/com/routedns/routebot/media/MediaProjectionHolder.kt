package com.routedns.routebot.media

import android.content.Intent
import kotlinx.coroutines.CompletableDeferred
import javax.inject.Singleton

/**
 * Holds the user's MediaProjection screen-capture grant (if any) for reuse across multiple
 * `take_screenshot` commands within the same app process, and coordinates the one-time
 * consent flow via [com.routedns.routebot.ui.screenshare.ScreenCaptureConsentActivity].
 *
 * Android requires explicit, interactive user consent before any app can capture the screen
 * (MediaProjection) — this cannot be granted silently or remotely, by design of the platform.
 * The grant is not guaranteed to remain valid indefinitely; if capture fails because it was
 * revoked, [pendingGrant] must be requested again.
 */
@Singleton
object MediaProjectionHolder {
    @Volatile var resultCode: Int? = null
    @Volatile var resultData: Intent? = null

    @Volatile private var pendingGrant: CompletableDeferred<Boolean>? = null

    fun hasGrant(): Boolean = resultCode != null && resultData != null

    fun applyGrant(resultCode: Int, data: Intent?) {
        this.resultCode = resultCode
        this.resultData = data
        pendingGrant?.complete(data != null)
    }

    fun clearGrant() {
        resultCode = null
        resultData = null
    }

    /** Registers a new pending-grant waiter; call before launching the consent activity. */
    fun newPendingGrant(): CompletableDeferred<Boolean> {
        val deferred = CompletableDeferred<Boolean>()
        pendingGrant = deferred
        return deferred
    }
}
