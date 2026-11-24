package chimahon.srs

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteConstraintException
import android.database.sqlite.SQLiteDatabase
import android.content.SharedPreferences
import java.io.File

/** [SqlDb] over Android's SQLiteDatabase. */
class AndroidSqlDb(private val db: SQLiteDatabase) : SqlDb {
    override fun exec(sql: String, args: Array<Any?>) {
        try { if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args) } catch (e: SQLiteConstraintException) { throw SqlConstraintException(e) }
    }

    override fun <T> query(sql: String, args: Array<Any?>, row: (SqlRow) -> T): List<T> =
        db.rawQuery(sql, Array(args.size) { args[it].toString() }).use { c ->
            val r = CursorRow(c)
            buildList { while (c.moveToNext()) add(row(r)) }
        }

    override fun <T> tx(block: () -> T): T {
        db.beginTransaction()
        try { return block().also { db.setTransactionSuccessful() } } finally { db.endTransaction() }
    }

    override fun close() = db.close()

    private class CursorRow(val c: Cursor) : SqlRow {
        override val columnCount get() = c.columnCount
        override fun name(i: Int) = c.getColumnName(i)
        override fun isNull(i: Int) = c.isNull(i)
        override fun long(i: Int) = c.getLong(i)
        override fun int(i: Int) = c.getInt(i)
        override fun double(i: Int) = c.getDouble(i)
        override fun string(i: Int): String = c.getString(i)
        override fun value(i: Int): Any? = when (c.getType(i)) {
            Cursor.FIELD_TYPE_NULL -> null
            Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
            Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
            else -> c.getString(i)
        }
    }

    companion object {
        /** The store's file, where it has always been: `filesDir/srs/ja.db`. */
        fun open(context: Context): AndroidSqlDb =
            File(context.filesDir, "srs").apply { mkdirs() }
                .let { AndroidSqlDb(SQLiteDatabase.openOrCreateDatabase(File(it, "${Srs.LANG}.db"), null)) }
    }
}

/** [PeerPrefs] over the SharedPreferences file "kumahon_peer", with the types the pairing has always stored (paired = boolean, last_time = long, the rest strings). */
class AndroidPeerPrefs(context: Context) : PeerPrefs {
    private val p: SharedPreferences = context.applicationContext.getSharedPreferences("kumahon_peer", Context.MODE_PRIVATE)

    override fun get(k: String): String? = p.all[k]?.toString()

    override fun put(k: String, v: String?) {
        p.edit().apply {
            when {
                v == null -> remove(k)
                k == PeerSync.PAIRED -> putBoolean(k, v == "true")
                k == PeerSync.LAST_TIME -> putLong(k, v.toLong())
                else -> putString(k, v)
            }
        }.apply()
    }
}