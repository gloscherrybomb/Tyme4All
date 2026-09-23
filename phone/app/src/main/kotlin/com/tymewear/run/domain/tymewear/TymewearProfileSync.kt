package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.Settings

/** Copies Tymewear's per-sport thresholds and resting/max values into the settings. Exceptions reach the caller. */
object TymewearProfileSync {
    fun apply(settings: Settings, api: TymewearApi): Settings {
        val id = api.profile().id
        val bike = api.activeThresholds(id, "bike")
        val run = api.activeThresholds(id, "running")
        val reserve = api.restingMax()
        return settings.copy(
            bikeThresholds = bike ?: settings.bikeThresholds,
            runThresholds = run ?: settings.runThresholds,
            tymewearReserve = reserve ?: settings.tymewearReserve,
        )
    }


    /** Reads what Tymewear holds; never throws. Nothing is called unless the thresholds may be read. */
    fun refresh(settings: Settings, api: TymewearApi): ProfileRefresh {
        if (!settings.tymewearProfileReadable) return ProfileRefresh.NotSignedIn
        return try {
            val id = api.profile().id
            ProfileRefresh.Refreshed(api.activeThresholds(id, "bike"), api.activeThresholds(id, "running"), api.restingMax())
        } catch (e: TymewearAuthException) {
            ProfileRefresh.AuthRefused
        } catch (e: TymewearException) {
            // A 2xx here is a reply that could not be read; its code says nothing useful.
            val reason = if (e.httpCode in 200..299) e.javaClass.simpleName else "HTTP ${e.httpCode}"
            ProfileRefresh.Failed(reason, e.javaClass.simpleName)
        } catch (e: Exception) {
            ProfileRefresh.Failed(e.javaClass.simpleName, e.javaClass.simpleName)
        }
    }

    /**
     * The settings to save after [result], built from [current] (what is stored at save time).
     * Nothing changes once the user has signed out, or when a read lands after a refusal.
     */
    fun record(current: Settings, result: ProfileRefresh, nowMs: Long): Settings {
        if (!current.tymewearSignedIn) return current
        return when (result) {
            is ProfileRefresh.Refreshed -> if (!current.tymewearProfileReadable) current else current.copy(
                bikeThresholds = result.bikeThresholds ?: current.bikeThresholds,
                runThresholds = result.runThresholds ?: current.runThresholds,
                tymewearReserve = result.reserve ?: current.tymewearReserve,
                tymewearRefreshMs = nowMs, tymewearRefreshError = null,
            )
            is ProfileRefresh.Failed -> if (!current.tymewearProfileReadable) current else
                current.copy(tymewearRefreshMs = nowMs, tymewearRefreshError = result.reason)
            ProfileRefresh.AuthRefused -> current.copy(tymewearRefreshMs = nowMs, tymewearRefreshError = ProfileRefresh.REFUSED_REASON)
            ProfileRefresh.NotSignedIn -> current
        }
    }
}
