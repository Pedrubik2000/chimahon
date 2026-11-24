package chimahon.srs

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HubScanTest {
    @Test fun subnetListing() {
        val c = HubScan.candidates(listOf("192.168.1.5", "192.168.1.77", "10.0.0.2"))
        assertEquals(252 + 253, c.size) // the /24 of .5 and .77 once, minus both; 10.0.0.x minus .2
        assertTrue("192.168.1.6" in c && "192.168.1.5" !in c && "192.168.1.77" !in c && "10.0.0.1" in c && "10.0.0.2" !in c)
        assertEquals(emptyList<String>(), HubScan.candidates(emptyList()))
    }

    @Test fun scanFindsOpenPort() {
        val found = HubScan.find(listOf("192.168.1.5")) { ip, port, _ -> port == HubScan.PORT && ip == "192.168.1.9" }
        assertEquals(listOf("192.168.1.9:47823"), found)
    }
}
