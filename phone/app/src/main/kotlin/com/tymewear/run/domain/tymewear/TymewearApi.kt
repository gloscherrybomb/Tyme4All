package com.tymewear.run.domain.tymewear

import com.tymewear.run.domain.ReserveSettings
import com.tymewear.run.domain.ZoneThresholds
import java.time.Instant

data class TymewearProfile(val id: Long)
data class PartnerCopy(val tpId: Long, val partner: String, val startDate: Instant)
data class TymewearActivity(val id: String, val listedAt: Instant?, val partnerCopies: List<PartnerCopy>)

class TymewearException(val httpCode: Int, message: String) : Exception(message)
/** Tymewear refused the saved sign-in and the stored email and password. */
class TymewearAuthException(message: String) : Exception(message)

/** Where the app keeps the Tymewear sign-in. Android: EncryptedSharedPreferences. Tests: in memory. */
interface CredentialStore {
    val email: String?
    val password: String?
    var token: String?
    fun save(email: String, password: String)
    fun clear()
}

class InMemoryCredentialStore(override var email: String? = null, override var password: String? = null, override var token: String? = null) : CredentialStore {
    override fun save(email: String, password: String) { this.email = email; this.password = password }
    override fun clear() { email = null; password = null; token = null }
}

interface TymewearApi {
    /** Signs in with the given email and password, stores them and the token. Throws TymewearAuthException if refused. */
    fun signIn(email: String, password: String)
    fun profile(): TymewearProfile
    /** The Fitness Profile's markers for "running" or "bike", same names as Tymewear, or null if it has none (404, or any marker missing). */
    fun activeThresholds(userId: Long, sport: String): ZoneThresholds?
    /** Resting and max BR and HR, or null if Tymewear has none. */
    fun restingMax(): ReserveSettings?
    fun recentActivities(userId: Long, limit: Int): List<TymewearActivity>
    fun activity(id: String): TymewearActivity
    fun replaceFile(tpId: Long, fit: ByteArray)
}
