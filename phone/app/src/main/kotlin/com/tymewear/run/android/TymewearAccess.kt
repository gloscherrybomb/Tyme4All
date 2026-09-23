package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.sync.IntervalsClient
import com.tymewear.run.domain.sync.SyncEngine
import com.tymewear.run.domain.tymewear.SignInGuard
import com.tymewear.run.domain.tymewear.TymewearApi
import com.tymewear.run.domain.tymewear.TymewearAuthException
import com.tymewear.run.domain.tymewear.TymewearClient
import com.tymewear.run.domain.tymewear.TymewearProfileSync
import com.tymewear.run.domain.tymewear.TymewearUploader
import timber.log.Timber

/**
 * Sign-in, sign-out, the thresholds read and the sync engine's Tymewear step, in one place.
 * Every settings save starts from what is stored at that moment.
 */
object TymewearAccess {

    /** The sync engine, with the Tymewear step only while [Settings.tymewearActive]. */
    fun engine(ctx: Context, intervals: IntervalsClient, settings: Settings): SyncEngine {
        val step = if (settings.tymewearActive) {
            SignInGuard(
                TymewearUploader(intervals, TymewearClient(Graph.tymewearCredentials)),
                refused = { Graph.settings.load().tymewearSignInRefused },
                onRefused = { markRefused(ctx) },
            )
        } else null
        return SyncEngine(intervals, Graph.sessionStore, step)
    }

    /** Tymewear refused the saved sign-in: stop calling it and tell the user once. */
    @Synchronized
    fun markRefused(ctx: Context) {
        val s = Graph.settings.load()
        if (!s.tymewearSignedIn || s.tymewearSignInRefused) return
        Graph.settings.save(s.copy(tymewearSignInRefused = true))
        Notifications.tymewearSignIn(ctx)
    }

    /**
     * Reads the thresholds and resting/max values from Tymewear. A failure keeps the stored values;
     * a refused sign-in sets the refused flag. Call off the main thread.
     */
    fun refreshProfile(ctx: Context, api: TymewearApi = TymewearClient(Graph.tymewearCredentials)) {
        if (!Graph.settings.load().tymewearProfileReadable) return
        try {
            val fetched = TymewearProfileSync.apply(Graph.settings.load(), api)
            val now = Graph.settings.load()
            if (!now.tymewearProfileReadable) return
            Graph.settings.save(now.copy(bikeThresholds = fetched.bikeThresholds, runThresholds = fetched.runThresholds, tymewearReserve = fetched.tymewearReserve))
        } catch (e: TymewearAuthException) {
            markRefused(ctx)
        } catch (e: Exception) {
            Timber.w("Tymewear profile refresh failed: ${e.javaClass.simpleName}")
        }
    }

    /** Signs in and reads the thresholds; the message for the user. Call off the main thread. */
    fun signIn(ctx: Context, email: String, password: String): String = try {
        val client = TymewearClient(Graph.tymewearCredentials)
        client.signIn(email, password)
        Graph.settings.save(Graph.settings.load().copy(tymewearSignedIn = true, tymewearSignInRefused = false))
        refreshProfile(ctx, client)
        "Signed in to Tymewear"
    } catch (_: TymewearAuthException) {
        "Tymewear did not accept that email and password"
    } catch (e: Exception) {
        Timber.w("Tymewear sign-in failed: ${e.javaClass.simpleName}")
        "Could not reach Tymewear. Try again."
    }

    /** Forgets the sign-in and everything read from Tymewear. Call off the main thread. */
    fun signOut() {
        Graph.tymewearCredentials.clear()
        Graph.settings.save(
            Graph.settings.load().copy(
                tymewearSignedIn = false, tymewearSignInRefused = false,
                bikeThresholds = null, runThresholds = null, tymewearReserve = null,
            ),
        )
    }
}
