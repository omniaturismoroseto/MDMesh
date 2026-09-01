package com.mdmesh.core.store

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.desiredDataStore: DataStore<Preferences> by preferencesDataStore(name = "mdm_desired")

/**
 * The revision of the desired state this device has already applied.
 *
 * Without it the agent would re-apply the whole desired state at every check-in — and
 * re-entering kiosk every few seconds is not a wasted cycle, it is a screen that flickers
 * under the hands of whoever is using the device.
 *
 * It survives restarts on purpose: after a reboot the device must not redo work it had
 * already done, or every power cut would produce a burst of re-application.
 */
interface AppliedRevisionStore {
    suspend fun load(): Long
    suspend fun save(revision: Long)
}

class DataStoreAppliedRevisionStore(private val context: Context) : AppliedRevisionStore {

    override suspend fun load(): Long =
        context.desiredDataStore.data.map { it[KEY] }.first() ?: 0L

    override suspend fun save(revision: Long) {
        context.desiredDataStore.edit { it[KEY] = revision }
    }

    private companion object {
        val KEY = longPreferencesKey("applied_revision")
    }
}

/** In-memory [AppliedRevisionStore] for unit tests. */
class InMemoryAppliedRevisionStore(initial: Long = 0L) : AppliedRevisionStore {
    private var revision: Long = initial

    override suspend fun load(): Long = revision

    override suspend fun save(revision: Long) {
        this.revision = revision
    }
}
