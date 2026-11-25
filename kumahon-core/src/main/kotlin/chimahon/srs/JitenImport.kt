package chimahon.srs

import chimahon.lang.JitenKnown
import java.io.IOException

/** One instance for the app: its 6 h backoff after a 403/418 must survive between taps. */
private val DEFAULT = JitenKnown()

/** Fetches top [top] + [sets], stores them as [srs]'s Jiten layer (and the list kumapie shares, see [Srs.replaceJiten]) and returns its state. Blocking: call off the main thread. Throws IOException with a readable message. */
@Throws(IOException::class)
fun importJiten(srs: Srs, top: Int, sets: List<String>, jiten: JitenKnown = DEFAULT): JitenState {
    srs.replaceJiten(jiten.import(top, sets), top, sets)
    return srs.jitenImport()
}
