package com.tymewear.run.android

import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.companion.DeviceNotAssociatedException
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import timber.log.Timber
import java.util.regex.Pattern

/**
 * Thin wrapper around Companion Device Manager. Every CDM call is guarded by
 * `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` (CDM presence observation is a
 * S/API-31 feature); on older devices this object is inert. Keeps CDM types out of
 * `domain/` so the pure decision logic in ServiceLifecycle stays platform-free.
 */
object CompanionAssociation {

    private const val NAME_PATTERN = "(?i)(vitalpro|tymewear|tyme-[0-9a-f]{4}).*"

    /**
     * True when CDM presence observation is usable: API >= 31 and the system feature is
     * present. Takes a Context (the spec sketch omitted one, but checking the system
     * feature requires it) — see the deviation note in the final report.
     */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    fun associationIds(context: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mgr.myAssociations.map { it.id.toString() }
            } else {
                @Suppress("DEPRECATION")
                mgr.associations
            }
        } catch (e: Exception) {
            Timber.w(e, "failed to read companion associations")
            emptyList()
        }
    }

    fun associationRequest(): AssociationRequest {
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile(NAME_PATTERN))
            .build()
        return AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
            .build()
    }

    /**
     * Starts presence observation for every existing association.
     *
     * The non-deprecated `ObservingDevicePresenceRequest` API (added in a later platform
     * revision than this project's compileSdk 34) is not on the compile classpath here, so
     * this uses the deprecated `startObservingDevicePresence(String)` overload on all of
     * API 31+ rather than only on 31..35 as originally sketched. Behaviourally equivalent
     * for a single BLE device filter; revisit once compileSdk moves past 34.
     */
    fun startObserving(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        for (id in associationIds(context)) {
            try {
                @Suppress("DEPRECATION")
                mgr.startObservingDevicePresence(id)
            } catch (e: DeviceNotAssociatedException) {
                Timber.w(e, "startObservingDevicePresence: not associated ($id)")
            } catch (e: Exception) {
                Timber.w(e, "startObservingDevicePresence failed ($id)")
            }
        }
    }

    fun stopObserving(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        for (id in associationIds(context)) {
            try {
                @Suppress("DEPRECATION")
                mgr.stopObservingDevicePresence(id)
            } catch (e: DeviceNotAssociatedException) {
                Timber.w(e, "stopObservingDevicePresence: not associated ($id)")
            } catch (e: Exception) {
                Timber.w(e, "stopObservingDevicePresence failed ($id)")
            }
        }
    }

    fun disassociateAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        for (id in associationIds(context)) {
            try {
                @Suppress("DEPRECATION")
                mgr.disassociate(id)
            } catch (e: Exception) {
                Timber.w(e, "disassociate failed ($id)")
            }
        }
    }
}
