package kumahon.desktop

import java.io.File

/** `%APPDATA%\kumahon`, or `~/.kumahon` when APPDATA is not set. */
fun dataDir(appData: String?, home: String): File =
    if (!appData.isNullOrBlank()) File(appData, "kumahon") else File(home, ".kumahon")
