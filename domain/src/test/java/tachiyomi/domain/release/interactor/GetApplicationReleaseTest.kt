package tachiyomi.domain.release.interactor

import io.kotest.matchers.shouldBe
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import tachiyomi.core.common.preference.Preference
import tachiyomi.core.common.preference.PreferenceStore
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService
import java.time.Instant

class GetApplicationReleaseTest {

    private lateinit var getApplicationRelease: GetApplicationRelease
    private lateinit var releaseService: ReleaseService
    private lateinit var preference: Preference<Long>

    @BeforeEach
    fun beforeEach() {
        val preferenceStore = mockk<PreferenceStore>()
        preference = mockk()
        every { preferenceStore.getLong(any(), any()) } returns preference
        releaseService = mockk()

        getApplicationRelease = GetApplicationRelease(releaseService, preferenceStore)
    }

    @Test
    fun `When has update but is preview expect new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val releases = listOf(
            Release(
                "r2000",
                "info",
                "http://example.com/release_link",
                "http://example.com/release_link.apk",
            ),
        )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = true,
                commitCount = 1000,
                versionName = "",
                repository = "test",
            ),
        )

        // KMK: Don't cast, will throw exception if the result is different from expected
        result shouldBe GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `When has update expect new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val releases =
            listOf(
                Release(
                    "v2.0.0",
                    "info",
                    "http://example.com/release_link",
                    "http://example.com/release_link.apk",
                ),
            )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        // KMK: Don't cast, will throw exception if the result is different from expected
        result shouldBe GetApplicationRelease.Result.NewUpdate(releases.getLatest()!!)
    }

    @Test
    fun `When has no update expect no new update`() = runTest {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }

        val releases = listOf(
            Release(
                "v1.0.0",
                "info",
                "http://example.com/release_link",
                "http://example.com/release_link.apk",
            ),
        )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = "v2.0.0",
                repository = "test",
            ),
        )

        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    @Test
    fun `When now is before two days expect no new update`() = runTest {
        every { preference.get() } returns Instant.now().toEpochMilli()
        every { preference.set(any()) }.answers { }

        val releases = listOf(
            Release(
                "v2.0.0",
                "info",
                "http://example.com/release_link",
                "http://example.com/release_link.apk",
            ),
        )

        coEvery { releaseService.releaseNotes(any()) } returns releases

        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = "v1.0.0",
                repository = "test",
            ),
        )

        coVerify(exactly = 0) { releaseService.latest(any()) }
        coVerify(exactly = 0) { releaseService.releaseNotes(any()) }
        result shouldBe GetApplicationRelease.Result.NoNewUpdate
    }

    // chimahon: kumahon's tags (v1.2.0) against its version name (1.2.0)
    private suspend fun kumahonCheck(tag: String, versionName: String, preRelease: Boolean = false): Boolean {
        every { preference.get() } returns 0
        every { preference.set(any()) }.answers { }
        coEvery { releaseService.releaseNotes(any()) } returns listOf(
            Release(tag, "info", "http://example.com/release_link", "http://example.com/a.apk", preRelease = preRelease),
        )
        val result = getApplicationRelease.await(
            GetApplicationRelease.Arguments(
                isFoss = false,
                isPreview = false,
                commitCount = 0,
                versionName = versionName,
                repository = "Pedrubik2000/kumahon",
                forceCheck = true,
            ),
        )
        return result is GetApplicationRelease.Result.NewUpdate
    }

    @Test
    fun `kumahon v1_2_1 is newer than 1_2_0`() = runTest {
        kumahonCheck("v1.2.1", "1.2.0") shouldBe true
        kumahonCheck("v1.3.0", "1.2.0") shouldBe true
    }

    @Test
    fun `kumahon same or older tag is no update`() = runTest {
        kumahonCheck("v1.2.0", "1.2.0") shouldBe false
        kumahonCheck("v1.1.9", "1.2.0") shouldBe false
    }

    @Test
    fun `kumahon dev tags are never newer`() = runTest {
        kumahonCheck("dev-1.1.0-10997", "1.0.0") shouldBe false
        kumahonCheck("dev-1.2.0-10997", "1.2.0") shouldBe false
        kumahonCheck("dev-1.1.0-10997", "1.0.0", preRelease = true) shouldBe false
    }
}
