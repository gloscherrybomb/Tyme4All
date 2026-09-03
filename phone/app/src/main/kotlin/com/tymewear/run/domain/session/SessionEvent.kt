package com.tymewear.run.domain.session

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
sealed class SessionEvent {
    abstract val tMs: Long

    @Serializable @SerialName("start")
    data class Start(override val tMs: Long, val source: String) : SessionEvent()

    @Serializable @SerialName("breath")
    data class Breath(
        override val tMs: Long, val br: Double, val tv: Double, val ie: Double,
        val tvRaw: Int, val inhale: Int, val exhale: Int, val ts40: Long,
    ) : SessionEvent()

    @Serializable @SerialName("battery")
    data class Battery(override val tMs: Long, val pct: Int) : SessionEvent()

    @Serializable @SerialName("strap")
    data class Strap(override val tMs: Long, val connected: Boolean) : SessionEvent()

    @Serializable @SerialName("stop")
    data class Stop(override val tMs: Long, val reason: String) : SessionEvent()
}

internal val sessionJson = Json { classDiscriminator = "type"; ignoreUnknownKeys = true; encodeDefaults = true }
