package com.tymewear.run.android

import android.content.Context
import com.tymewear.run.domain.Settings
import com.tymewear.run.domain.sync.IntervalsClient
import com.tymewear.run.domain.sync.SyncEngine
import com.tymewear.run.domain.tymewear.ProfileRefresh
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
     * Reads the thresholds and resting/max values from Tymewear and stores them with the time of
     * the read, or the time and reason of a failure; a failure keeps the stored values and a
     * refused sign-in sets the refused flag. Call off the main thread.
     */
    fun refreshProfile(ctx: Context, api: TymewearApi = TymewearClient(Graph.tymewearCredentials)): ProfileRefresh {
        val result = TymewearProfileSync.refresh(Graph.settings.load(), api)
        when (result) {
            is ProfileRefresh.Failed -> Timber.w("Tymewear profile refresh failed: ${result.errorClass}")
            ProfileRefresh.AuthRefused -> {
                Timber.w("Tymewear profile refresh failed: TymewearAuthException")
                markRefused(ctx)
            }
            else -> {}
        }
        if (result != ProfileRefresh.NotSignedIn) recordRefresh(result)
        return result
    }

    @Synchronized
    private fun recordRefresh(result: ProfileRefresh) {
        Graph.settings.save(TymewearProfileSync.record(Graph.settings.load(), result, System.currentTimeMillis()))
    }

    /**
     * Signs in, reads the thresholds and, when Tymewear uploads are still waiting (within the give-up
     * window), starts the service so they run; the message for the user. Call off the main thread.
     */
    fun signIn(ctx: Context, email: String, password: String): String = try {
        val client = TymewearClient(Graph.tymewearCredentials)
        client.signIn(email, password)
        Graph.settings.save(Graph.settings.load().copy(tymewearSignedIn = true, tymewearSignInRefused = false))
        val refreshed = refreshProfile(ctx, client)
        // A new sign-in (including one that clears a refusal) lets waiting Tymewear uploads run: start the service if any wait.
        RecorderService.startIfNeeded(ctx)
        ProfileRefresh.signInMessage(refreshed)
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
                tymewearRefreshMs = null, tymewearRefreshError = null,
            ),
        )
    }
}
