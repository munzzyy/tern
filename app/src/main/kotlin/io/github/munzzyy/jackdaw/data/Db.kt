package io.github.munzzyy.jackdaw.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

internal class Db(context: Context, name: String) : SQLiteOpenHelper(context, name, null, VERSION) {
    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE apps (id TEXT PRIMARY KEY, package TEXT, config TEXT NOT NULL, state TEXT NOT NULL, updated INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE validators (key TEXT PRIMARY KEY, etag TEXT, last_modified TEXT)")
        db.execSQL(
            "CREATE TABLE events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, app_id TEXT, app_name TEXT, " +
                "kind TEXT NOT NULL, message TEXT NOT NULL)",
        )
        db.execSQL("CREATE TABLE inspections (key TEXT PRIMARY KEY, url TEXT NOT NULL, size INTEGER, sha256 TEXT, facts TEXT NOT NULL, at INTEGER NOT NULL)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    companion object {
        const val VERSION = 1
    }
}
