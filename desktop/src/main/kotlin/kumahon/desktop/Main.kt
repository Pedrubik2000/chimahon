package kumahon.desktop

import androidx.compose.material3.Text
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import chimahon.srs.Fsrs6

/** Proves the shared :kumahon-core links: interval in days after a first "good" rating. */
fun firstGoodInterval(): Int {
    val fsrs = Fsrs6()
    return fsrs.interval(fsrs.next(null, 0, 3).stability)
}

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "kumahon") {
        Text("kumahon for Windows")
    }
}
