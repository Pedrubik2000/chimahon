package chimahon.srs

/** A constraint (unique, primary key...) refused a write. */
class SqlConstraintException(cause: Throwable? = null) : RuntimeException(cause)

/** One row of a query: columns by index, as Android's Cursor and JDBC's ResultSet both give them. */
interface SqlRow {
    val columnCount: Int
    fun name(i: Int): String
    fun isNull(i: Int): Boolean
    fun long(i: Int): Long
    fun int(i: Int): Int
    fun double(i: Int): Double
    fun string(i: Int): String

    /** null, Long, Double or String. */
    fun value(i: Int): Any?
}

/**
 * The little of SQLite that [Srs] needs, so the same store runs on Android (`AndroidSqlDb`) and on the JVM ([JdbcSqlDb]).
 * Args: null, Int, Long, Double or String. A refused constraint throws [SqlConstraintException]. [tx] nests (the outermost commits;
 * an exception rolls back).
 */
interface SqlDb {
    fun exec(sql: String, args: Array<Any?> = emptyArray())
    fun <T> query(sql: String, args: Array<Any?> = emptyArray(), row: (SqlRow) -> T): List<T>
    fun <T> tx(block: () -> T): T
    fun close()
}
