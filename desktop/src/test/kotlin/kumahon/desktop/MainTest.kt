package kumahon.desktop

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MainTest {
    @Test fun sharedCoreLinks() = assertTrue(firstGoodInterval() >= 1)
}
