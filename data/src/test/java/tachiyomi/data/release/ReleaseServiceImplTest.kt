package tachiyomi.data.release

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class ReleaseServiceImplTest {

    // The assets of kumahon v1.2.0
    private val assets = listOf(
        GitHubAsset("kumahon-1.2.0-arm64-v8a.apk", "https://example.com/arm64.apk"),
        GitHubAsset("kumahon-1.2.0-armeabi-v7a.apk", "https://example.com/v7a.apk"),
    )

    @Test
    fun `arm64 device gets the arm64 APK`() {
        ReleaseServiceImpl.pickDownloadLink(assets, "arm64-v8a", isFoss = false) shouldBe
            "https://example.com/arm64.apk"
    }

    @Test
    fun `armeabi-v7a device gets the v7a APK`() {
        ReleaseServiceImpl.pickDownloadLink(assets, "armeabi-v7a", isFoss = false) shouldBe
            "https://example.com/v7a.apk"
    }

    @Test
    fun `x86 device without a fitting APK gets nothing`() {
        ReleaseServiceImpl.pickDownloadLink(assets, "x86_64", isFoss = false) shouldBe null
        ReleaseServiceImpl.pickDownloadLink(assets, "x86", isFoss = false) shouldBe null
    }

    @Test
    fun `universal APK is the fallback`() {
        val withUniversal = assets + GitHubAsset("kumahon-1.2.0.apk", "https://example.com/universal.apk")
        ReleaseServiceImpl.pickDownloadLink(withUniversal, "x86_64", isFoss = false) shouldBe
            "https://example.com/universal.apk"
    }
}
