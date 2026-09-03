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
     * One CDM association. `id` (the small integer CDM assigns per association) and
     * `address` (the device's Bluetooth MAC) are *not* interchangeable:
     * `startObservingDevicePresence(String)` / `stopObservingDevicePresence(String)`
     * take the MAC address on every API level where that overload exists, while
     * `disassociate(Int)` (API 33+) takes the id and `disassociate(String)` (all
     * levels, bound when the argument's static type is String) takes the address.
     * Passing the id where the address is required throws
     * `DeviceNotAssociatedException` at runtime with no compile-time warning — this
     * type exists so callers can't make that mistake by accident.
     */
    data class Association(val id: Int?, val address: String?)

    /**
     * True when CDM presence observation is usable: API >= 31 and the system feature is
     * present. Takes a Context (the spec sketch omitted one, but checking the system
     * feature requires it) — see the deviation note in the final report.
     */
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_COMPANION_DEVICE_SETUP)
    }

    /**
     * Existing associations for this app.
     *
     * On API 33+, `myAssociations` gives both the id and the MAC address. On 31..32,
     * only the deprecated `associations` is available, which returns MAC address
     * strings with no id — `Association.id` is null there, so `disassociateAll` falls
     * back to the address-based deprecated `disassociate(String)` overload on those
     * API levels.
     */
    fun associations(context: Context): List<Association> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return emptyList()
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                mgr.myAssociations.map { Association(id = it.id, address = it.deviceMacAddress?.toString()) }
            } else {
                @Suppress("DEPRECATION")
                mgr.associations.map { address -> Association(id = null, address = address) }
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
     * Starts presence observation for every existing association that has a known MAC
     * address, and returns how many it successfully started observing — callers should
     * surface a mismatch between association count and this number rather than let the
     * feature degrade to always-on silently (see `Graph.observingCount`).
     *
     * The non-deprecated `ObservingDevicePresenceRequest` API (added in a later platform
     * revision than this project's compileSdk 34) is not on the compile classpath here, so
     * this uses the deprecated `startObservingDevicePresence(String)` overload — which
     * takes the MAC address, not the association id — on all of API 31+ rather than only
     * on 31..35 as originally sketched. Behaviourally equivalent for a single BLE device
     * filter; revisit once compileSdk moves past 34.
     */
    fun startObserving(context: Context): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return 0
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return 0
        var started = 0
        for (a in associations(context)) {
            val address = a.address
            if (address == null) {
                Timber.w("startObservingDevicePresence: no MAC address for association ${a.id}")
                continue
            }
            try {
                @Suppress("DEPRECATION")
                mgr.startObservingDevicePresence(address)
                started++
            } catch (e: DeviceNotAssociatedException) {
                Timber.w(e, "startObservingDevicePresence: not associated ($address)")
            } catch (e: Exception) {
                Timber.w(e, "startObservingDevicePresence failed ($address)")
            }
        }
        return started
    }

    fun stopObserving(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        for (a in associations(context)) {
            val address = a.address
            if (address == null) {
                Timber.w("stopObservingDevicePresence: no MAC address for association ${a.id}")
                continue
            }
            try {
                @Suppress("DEPRECATION")
                mgr.stopObservingDevicePresence(address)
            } catch (e: DeviceNotAssociatedException) {
                Timber.w(e, "stopObservingDevicePresence: not associated ($address)")
            } catch (e: Exception) {
                Timber.w(e, "stopObservingDevicePresence failed ($address)")
            }
        }
    }

    fun disassociateAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        val mgr = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        for (a in associations(context)) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && a.id != null) {
                    // Static type Int so this binds to disassociate(int), not the
                    // deprecated disassociate(String) overload.
                    val id: Int = a.id
                    mgr.disassociate(id)
                } else if (a.address != null) {
                    @Suppress("DEPRECATION")
                    mgr.disassociate(a.address)
                } else {
                    Timber.w("disassociate: association with neither id nor address")
                }
            } catch (e: Exception) {
                Timber.w(e, "disassociate failed ($a)")
            }
        }
    }
}
