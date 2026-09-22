package com.tymewear.run.domain

object Constants {
    const val DEFAULT_VT1 = 73.0
    const val DEFAULT_VT2 = 96.0
    const val DEFAULT_TOP_Z4 = 112.0
    const val DEFAULT_VO2MAX = 130.0

    const val DEFAULT_RESTING_BR = 12.0
    const val DEFAULT_MAX_BR = 55.0
    const val DEFAULT_RESTING_HR = 60.0
    const val DEFAULT_MAX_HR = 190.0

    const val BLE_RAPID_PHASE_ATTEMPTS = 10
    const val BLE_RAPID_PHASE_DELAY_MS = 2000L
    const val BLE_SLOW_PHASE_DELAY_MS = 60000L
    const val BLE_DATA_WATCHDOG_TIMEOUT_MS = 45000L
    const val BLE_DATA_WATCHDOG_INTERVAL_MS = 10000L
    const val BLE_DATA_STALENESS_TIMEOUT_MS = 10000L
    const val BLE_INITIAL_SCAN_TIMEOUT_MS = 8000L

    const val MAX_BREATHING_RATE = 120.0
    const val MAX_MINUTE_VENTILATION = 250.0

    const val RELAY_PORT = 41415
    const val SMOOTHING_BREATHS = 8

    const val IDLE_STOP_MS = 3 * 60 * 1000L
    const val MAX_SESSION_MS = 8 * 60 * 60 * 1000L
    const val SYNC_POLL_INTERVAL_MS = 2 * 60 * 1000L
    const val SYNC_GIVE_UP_MS = 6 * 60 * 60 * 1000L
    const val MIN_OVERLAP_MS = 5 * 60 * 1000L
    const val RETENTION_DAYS = 90
}
