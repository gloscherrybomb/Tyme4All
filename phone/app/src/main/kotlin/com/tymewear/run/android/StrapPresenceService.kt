package com.tymewear.run.android

import android.companion.CompanionDeviceService
import android.os.Build
import androidx.annotation.RequiresApi
import com.tymewear.run.domain.StrapPresence
import timber.log.Timber

/**
 * Receives Companion Device Manager presence callbacks for the paired strap.
 *
 * Only sets `Graph.strapPresence` and (on appearance) starts the recorder; the
 * housekeeping loop in RecorderService is what actually stops the service, within its
 * usual 10 second tick, via ServiceLifecycle.shouldStop.
 */
@RequiresApi(Build.VERSION_CODES.S)
class StrapPresenceService : CompanionDeviceService() {

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceAppeared(associationId: String) {
        Timber.i("Strap appeared (association $associationId)")
        Graph.init(this)
        Graph.strapPresence = StrapPresence.NEARBY
        RecorderService.start(this)
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onDeviceDisappeared(associationId: String) {
        Timber.i("Strap disappeared (association $associationId)")
        Graph.init(this)
        Graph.strapPresence = StrapPresence.AWAY
    }
}
