package io.github.munzzyy.tern.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import io.github.munzzyy.tern.core.interop.AppConfigJson
import io.github.munzzyy.tern.core.interop.AppConfigJsonException
import io.github.munzzyy.tern.core.json.Json
import io.github.munzzyy.tern.core.json.JsonException
import io.github.munzzyy.tern.core.model.AppConfig
import io.github.munzzyy.tern.core.net.Validator
import io.github.munzzyy.tern.core.net.ValidatorStore
import io.github.munzzyy.tern.engine.Event
import io.github.munzzyy.tern.engine.EventKind
import io.github.munzzyy.tern.engine.EventLimits
import io.github.munzzyy.tern.engine.isOwn
import io.github.munzzyy.tern.log.TernLog
import java.io.Closeable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class StoredApp(val config: AppConfig, val state: AppState)

/** SQLite behind one writer thread. Reads may happen anywhere; every write is serialized. */
class Store(context: Context, name: String = DEFAULT_NAME) : ValidatorStore, Closeable {
    private val db = Db(context.applicationContext, name)
    private val writerThread = arrayOfNulls<Thread>(1)
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "tern-store").also { writerThread[0] = it } }
    val dispatcher = writer.asCoroutineDispatcher()

    private fun <T> write(block: (SQLiteDatabase) -> T): T {
        if (Thread.currentThread() === writerThread[0]) return block(db.writableDatabase)
        try {
            return writer.submit<T> { block(db.writableDatabase) }.get()
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        }
    }

    private val reader: SQLiteDatabase get() = db.readableDatabase

    fun apps(): List<StoredApp> = reader.rawQuery("SELECT id, config, state FROM apps", null).use { c ->
        buildList {
            while (c.moveToNext()) decodeApp(c.getString(0), c.getString(1), c.getString(2))?.let(::add)
        }
    }

    /**
     * The stored apps that cannot be read, which [apps] leaves out: each by the address it follows
     * where that much can be read, or by its id. They are kept as they are.
     */
    fun unreadableApps(): List<String> = reader.rawQuery("SELECT id, config, state FROM apps", null).use { c ->
        buildList {
            while (c.moveToNext()) {
                val id = c.getString(0)
                if (decodeApp(id, c.getString(1), c.getString(2)) == null) add(addressIn(c.getString(1)) ?: id)
            }
        }
    }

    private fun addressIn(config: String): String? = try {
        Json.parseObject(config).obj("source")?.string("url")?.take(MAX_ADDRESS)
    } catch (_: JsonException) {
        null
    }

    fun app(id: String): StoredApp? = reader.rawQuery("SELECT id, config, state FROM apps WHERE id = ?", arrayOf(id)).use { c ->
        if (c.moveToFirst()) decodeApp(c.getString(0), c.getString(1), c.getString(2)) else null
    }

    private val _configChanges = MutableStateFlow(0L)

    /** Counts every change to the list or to an app's settings, but not to what a check or an install learned. */
    val configChanges: StateFlow<Long> get() = _configChanges.asStateFlow()

    fun putApp(config: AppConfig, state: AppState) = write { db ->
        db.insertWithOnConflict("apps", null, appValues(config, state), SQLiteDatabase.CONFLICT_REPLACE)
        _configChanges.update { it + 1 }
        Unit
    }

    /** Read, change and write back on the writer thread, so two updates never lose each other. */
    fun update(id: String, change: (StoredApp) -> StoredApp): StoredApp? = write { db ->
        val current = app(id) ?: return@write null
        val next = change(current)
        db.update("apps", appValues(next.config, next.state), "id = ?", arrayOf(id))
        if (next.config != current.config) _configChanges.update { it + 1 }
        next
    }

    fun updateState(id: String, change: (AppState) -> AppState): StoredApp? = update(id) { it.copy(state = change(it.state)) }

    fun deleteApp(id: String) = write { db ->
        db.delete("apps", "id = ?", arrayOf(id))
        _configChanges.update { it + 1 }
        Unit
    }

    override fun get(key: String): Validator? =
        reader.rawQuery("SELECT etag, last_modified FROM validators WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) Validator(c.getString(0), c.getString(1)) else null
        }

    override fun put(key: String, validator: Validator) = write { db ->
        val values = ContentValues().apply {
            put("key", key)
            put("etag", validator.etag)
            put("last_modified", validator.lastModified)
        }
        db.insertWithOnConflict("validators", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        Unit
    }

    override fun remove(key: String) = write { db ->
        db.delete("validators", "key = ?", arrayOf(key))
        Unit
    }

    fun removeValidators(prefix: String) = write { db ->
        db.delete("validators", "substr(key, 1, ?) = ?", arrayOf(prefix.length.toString(), prefix))
        Unit
    }

    fun addEvent(atMs: Long, appId: String?, appName: String?, kind: EventKind, message: String): Event = write { db ->
        val values = ContentValues().apply {
            put("at", atMs)
            put("app_id", appId)
            put("app_name", appName)
            put("kind", kind.name)
            put("message", message.take(MAX_MESSAGE))
        }
        val id = db.insert("events", null, values)
        // Tern's own messages are counted apart, so that many of them never push out what happened to apps.
        val group = if (kind.isOwn) "kind IN ($OWN_KINDS)" else "kind NOT IN ($OWN_KINDS)"
        val cap = if (kind.isOwn) EventLimits.OWN else EventLimits.APPS
        db.execSQL("DELETE FROM events WHERE $group AND id NOT IN (SELECT id FROM events WHERE $group ORDER BY id DESC LIMIT $cap)")
        Event(id, atMs, appId, appName, kind, message.take(MAX_MESSAGE))
    }

    fun events(): List<Event> =
        reader.rawQuery("SELECT id, at, app_id, app_name, kind, message FROM events ORDER BY id DESC LIMIT ${EventLimits.APPS + EventLimits.OWN}", null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val kind = EventKind.entries.firstOrNull { it.name == c.getString(4) } ?: continue
                    add(Event(c.getLong(0), c.getLong(1), c.getString(2), c.getString(3), kind, c.getString(5)))
                }
            }
        }.let(EventLimits::kept)

    fun clearEvents() = write { db ->
        db.delete("events", null, null)
        Unit
    }

    fun facts(key: String): FileFacts? =
        reader.rawQuery("SELECT facts FROM inspections WHERE key = ?", arrayOf(key)).use { c ->
            if (c.moveToFirst()) StateJson.decodeFacts(c.getString(0)) else null
        }

    fun putFacts(key: String, url: String, size: Long?, sha256: String?, facts: FileFacts) = write { db ->
        val values = ContentValues().apply {
            put("key", key)
            put("url", url)
            put("size", size)
            put("sha256", sha256)
            put("facts", StateJson.encodeFacts(facts))
            put("at", System.currentTimeMillis())
        }
        db.insertWithOnConflict("inspections", null, values, SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("DELETE FROM inspections WHERE key NOT IN (SELECT key FROM inspections ORDER BY at DESC LIMIT $MAX_INSPECTIONS)")
        Unit
    }

    override fun close() {
        writer.shutdown()
        db.close()
    }

    private fun appValues(config: AppConfig, state: AppState) = ContentValues().apply {
        put("id", config.id)
        put("package", config.packageName)
        put("config", Json.write(AppConfigJson.encode(config)))
        put("state", StateJson.encode(state))
        put("updated", System.currentTimeMillis())
    }

    private fun decodeApp(id: String, config: String, state: String): StoredApp? = try {
        StoredApp(AppConfigJson.decode(Json.parseObject(config)), StateJson.decode(state))
    } catch (e: AppConfigJsonException) {
        TernLog.e(TAG, "Stored app $id is unreadable: ${e.message}")
        null
    } catch (e: JsonException) {
        TernLog.e(TAG, "Stored app $id is unreadable: ${e.message}")
        null
    }

    companion object {
        const val DEFAULT_NAME = "tern.db"
        const val MAX_INSPECTIONS = 2000
        private const val MAX_MESSAGE = 2000
        private const val MAX_ADDRESS = 300
        private const val TAG = "TernStore"

        /** The kinds of Tern's own messages, as the events table names them. */
        private val OWN_KINDS = EventKind.entries.filter { it.isOwn }.joinToString { "'${it.name}'" }
    }
}
