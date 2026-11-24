package chimahon.srs

import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Types

/** [SqlDb] over a JDBC SQLite connection (org.xerial sqlite-jdbc on the classpath of the app or test that opens it). One connection, one thread at a time. */
class JdbcSqlDb(file: File) : SqlDb {
    private val con: Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").also { c -> c.createStatement().use { it.execute("PRAGMA busy_timeout = 5000") } }
    private var depth = 0

    @Synchronized
    override fun exec(sql: String, args: Array<Any?>) {
        try { con.prepareStatement(sql).use { it.bind(args).execute() } } catch (e: SQLException) { throw translate(e) }
    }

    @Synchronized
    override fun <T> query(sql: String, args: Array<Any?>, row: (SqlRow) -> T): List<T> =
        con.prepareStatement(sql).use { st ->
            st.bind(args).executeQuery().use { rs ->
                val r = JdbcRow(rs)
                buildList { while (rs.next()) add(row(r)) }
            }
        }

    @Synchronized
    override fun <T> tx(block: () -> T): T {
        if (depth > 0) return block()
        depth = 1
        con.autoCommit = false
        try {
            val out = block()
            con.commit()
            return out
        } catch (e: Throwable) {
            runCatching { con.rollback() }
            throw e
        } finally {
            depth = 0
            con.autoCommit = true
        }
    }

    @Synchronized
    override fun close() = con.close()

    private fun PreparedStatement.bind(args: Array<Any?>) = apply {
        args.forEachIndexed { i, a ->
            when (a) {
                null -> setNull(i + 1, Types.NULL)
                is Int -> setLong(i + 1, a.toLong())
                is Long -> setLong(i + 1, a)
                is Double -> setDouble(i + 1, a)
                else -> setString(i + 1, a.toString())
            }
        }
    }

    private fun translate(e: SQLException): Exception =
        if (e.errorCode and 0xFF == 19 || e.message.orEmpty().contains("SQLITE_CONSTRAINT")) SqlConstraintException(e) else e

    private class JdbcRow(val rs: ResultSet) : SqlRow {
        override val columnCount get() = rs.metaData.columnCount
        override fun name(i: Int) = rs.metaData.getColumnLabel(i + 1)
        override fun isNull(i: Int) = rs.getObject(i + 1) == null
        override fun long(i: Int) = rs.getLong(i + 1)
        override fun int(i: Int) = rs.getInt(i + 1)
        override fun double(i: Int) = rs.getDouble(i + 1)
        override fun string(i: Int): String = rs.getString(i + 1)
        override fun value(i: Int): Any? = when (val v = rs.getObject(i + 1)) {
            null -> null
            is Int, is Short, is Byte, is Long -> (v as Number).toLong()
            is Float, is Double -> (v as Number).toDouble()
            else -> v.toString()
        }
    }
}
