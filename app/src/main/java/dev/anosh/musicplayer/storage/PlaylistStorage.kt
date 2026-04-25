package dev.anosh.musicplayer.storage

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import dev.anosh.musicplayer.audio.PlaylistEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class PlaylistStorage(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    companion object {
        private const val DATABASE_NAME = "playlist.db"
        private const val DATABASE_VERSION = 1
        private const val TABLE_NAME = "playlist_entries"
        private const val COLUMN_URI = "uri"
        private const val COLUMN_DISPLAY_NAME = "display_name"
        private const val COLUMN_ORDER = "order_index"
        private const val COLUMN_ADDED_AT = "added_at"
        private const val COLUMN_LAST_PLAYED = "last_played_at"
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $TABLE_NAME (
                $COLUMN_URI TEXT PRIMARY KEY,
                $COLUMN_DISPLAY_NAME TEXT NOT NULL,
                $COLUMN_ORDER INTEGER NOT NULL,
                $COLUMN_ADDED_AT INTEGER NOT NULL,
                $COLUMN_LAST_PLAYED INTEGER
            )
        """.trimIndent())
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion != newVersion) {
            db.execSQL("DROP TABLE IF EXISTS $TABLE_NAME")
            onCreate(db)
        }
    }

    suspend fun loadAll(): List<PlaylistEntry> = withContext(Dispatchers.IO) {
        val entries = mutableListOf<PlaylistEntry>()
        readableDatabase.query(
            TABLE_NAME,
            arrayOf(COLUMN_URI, COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
            null,
            "$COLUMN_ORDER ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val uriString = cursor.getString(0)
                val displayName = cursor.getString(1)
                entries += PlaylistEntry(
                    uri = Uri.parse(uriString),
                    displayName = displayName,
                )
            }
        }
        entries
    }

    suspend fun replaceAll(entries: List<PlaylistEntry>) = withContext(Dispatchers.IO) {
        writableDatabase.beginTransaction()
        try {
            writableDatabase.delete(TABLE_NAME, null, null)
            val now = System.currentTimeMillis()
            entries.forEachIndexed { index, playlistEntry ->
                val values = ContentValues().apply {
                    put(COLUMN_URI, playlistEntry.uri.toString())
                    put(COLUMN_DISPLAY_NAME, playlistEntry.displayName)
                    put(COLUMN_ORDER, index)
                    put(COLUMN_ADDED_AT, now)
                }
                writableDatabase.insertWithOnConflict(
                    TABLE_NAME,
                    null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE,
                )
            }
            writableDatabase.setTransactionSuccessful()
        } finally {
            writableDatabase.endTransaction()
        }
    }

    suspend fun markPlayed(uri: Uri) = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(COLUMN_LAST_PLAYED, System.currentTimeMillis())
        }
        writableDatabase.update(
            TABLE_NAME,
            values,
            "$COLUMN_URI = ?",
            arrayOf(uri.toString()),
        )
    }
}
