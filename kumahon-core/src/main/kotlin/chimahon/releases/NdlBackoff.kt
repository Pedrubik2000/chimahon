package chimahon.releases


/** NDL answers 429 / times out when asked fast: after [limit] such failures in a row it is not asked again for the rest of the run. */
class NdlBackoff(private val limit: Int = 2) {
    private var streak = 0

    val tripped: Boolean get() = streak >= limit

    fun succeeded() { streak = 0 }

    /** Any other failure ends the streak (a parse error is not throttling). */
    fun failed(e: Exception) {
        streak = if (e is HttpStatusException && e.code == 429 || e is java.io.InterruptedIOException) streak + 1 else 0
    }
}
