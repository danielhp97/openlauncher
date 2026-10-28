package com.openlauncher.app.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.io.IOException

// Kept out of launcher_settings on purpose: SettingsRepository rewrites every
// settings key on each save, and OdometerService writes on its own schedule —
// sharing one file would let either side clobber the other's newest value.
private val Context.odometerDataStore: DataStore<Preferences> by preferencesDataStore(name = "odometer")

data class OdometerState(
    val odometerMeters: Double = 0.0,
    val tripStartMeters: Double = 0.0
) {
    val tripMeters: Double get() = (odometerMeters - tripStartMeters).coerceAtLeast(0.0)
}

class OdometerStore(private val context: Context) {

    private object Keys {
        val ODOMETER_METERS   = doublePreferencesKey("odometer_meters")
        val TRIP_START_METERS = doublePreferencesKey("trip_start_meters")
    }

    val flow: Flow<OdometerState> = context.odometerDataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { prefs ->
            OdometerState(
                odometerMeters  = prefs[Keys.ODOMETER_METERS]   ?: 0.0,
                tripStartMeters = prefs[Keys.TRIP_START_METERS] ?: 0.0
            )
        }

    suspend fun load(): OdometerState = flow.first()

    suspend fun saveOdometer(meters: Double) {
        context.odometerDataStore.edit { it[Keys.ODOMETER_METERS] = meters }
    }

    suspend fun setTripStart(meters: Double) {
        context.odometerDataStore.edit { it[Keys.TRIP_START_METERS] = meters }
    }
}
