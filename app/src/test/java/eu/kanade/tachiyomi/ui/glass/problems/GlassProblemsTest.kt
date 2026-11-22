package eu.kanade.tachiyomi.ui.glass.problems

import eu.kanade.tachiyomi.data.download.RealDebridImportJob.Account
import eu.kanade.tachiyomi.data.download.RealDebridQueue
import eu.kanade.tachiyomi.data.download.RealDebridQueue.State
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.util.UUID

class GlassProblemsTest {

    private fun row(mediaId: Long?, state: State, vararg volumes: Int, reason: String? = null) = RealDebridQueue.Row(
        workId = UUID.randomUUID(),
        mediaId = mediaId,
        volumes = volumes.toList(),
        sourceId = 1L,
        state = state,
        step = null,
        bytes = 0,
        total = 0,
        speed = 0,
        reason = reason ?: if (state == State.FAILED) "dead link: Rawxt" else null,
        servedBy = null,
        failures = emptyList(),
    )

    private fun build(
        account: Account? = Account(premium = true, daysLeft = 30),
        rows: List<RealDebridQueue.Row> = emptyList(),
        signedIn: Boolean = true,
        error: String? = null,
        hidden: Set<String> = emptySet(),
        here: Map<Long, Set<Int>> = emptyMap(),
    ) = GlassProblems.build(account, rows, here, mapOf(1L to "神のみぞ知るセカイ"), signedIn, error, hidden)

    @Test
    fun `nothing wrong, no key or no network for the key check gives no problems`() {
        build() shouldBe emptyList()
        build(account = null) shouldBe emptyList()
    }

    @Test
    fun `rejected key and free account are downloads problems with Change key and Hide`() {
        build(account = Account(false, 0, tokenRejected = true)).single().let {
            it.kind shouldBe ProblemKind.RD_REJECTED
            it.area shouldBe "downloads"
            it.actions shouldBe listOf(ProblemAction.CHANGE_KEY, ProblemAction.HIDE)
        }
        build(account = Account(false, 0)).single().kind shouldBe ProblemKind.RD_NOT_PREMIUM
        build(account = Account(false, 0), hidden = setOf(GlassProblems.RD)) shouldBe emptyList()
    }

    @Test
    fun `one problem per title with failed volumes, reasons joined, covered and cancelled and shared left out`() {
        val list = build(
            rows = listOf(
                row(1, State.FAILED, 1, 2),
                row(1, State.FAILED, 2, reason = "not on X3DL"),
                row(2, State.FAILED, 5),
                row(2, State.QUEUED, 5), // queued again: covered
                row(3, State.FAILED, 7, reason = RealDebridQueue.CANCELLED),
                row(null, State.FAILED),
            ),
        )
        list.single().let {
            it.id shouldBe "dl:1"
            it.kind shouldBe ProblemKind.DOWNLOAD_FAILED
            it.caption shouldBe "神のみぞ知るセカイ"
            it.volumes shouldBe listOf(1, 2)
            it.detail shouldBe "Dead link: Rawxt · Not on X3DL"
            it.actions shouldBe listOf(ProblemAction.OPEN_TITLE, ProblemAction.TRY_AGAIN)
        }
    }

    @Test
    fun `a failed title by what is on the tablet, an old done row hiding nothing`() {
        // MASHLE (as title 1): 3-18 here, the done 1-5 row from before jobs reported what they delivered
        val rows = listOf(
            row(1, State.DONE, 13, 14, 15, 16),
            row(1, State.DONE, 17, 18),
            row(1, State.DONE, 1, 2, 3, 4, 5),
            row(1, State.FAILED, 1, 2, 3, 4, reason = "Vol. 1, 2, 3, 4 were not found; dead link: Rawxt"),
        )
        build(rows = rows, here = mapOf(1L to (3..18).toSet())).single().let {
            it.mediaId shouldBe 1L
            it.volumes shouldBe listOf(1, 2)
            it.detail shouldBe "Dead link: Rawxt" // the label already says which volumes
        }
        // a reason that only restates the volumes: no detail line
        build(rows = listOf(row(1, State.FAILED, 1, 2, reason = "Vol. 1, 2 were not in the pack")), here = mapOf(1L to emptySet()))
            .single().detail shouldBe null
        build(rows = rows, here = mapOf(1L to (1..18).toSet())) shouldBe emptyList()
    }

    @Test
    fun `signed out wins over the refresh error, offline goes to Discover`() {
        build(signedIn = false, error = "Not logged in to AniList").single().let {
            it.kind shouldBe ProblemKind.ANILIST_SIGNED_OUT
            it.actions shouldBe listOf(ProblemAction.SIGN_IN)
        }
        build(error = "Unable to resolve host").single().let {
            it.kind shouldBe ProblemKind.ANILIST_OFFLINE
            it.area shouldBe "discover"
            it.detail shouldBe "Unable to resolve host"
        }
    }

    @Test
    fun `key first, then downloads, then AniList, and the rail areas`() {
        val list = build(
            account = Account(false, 0, tokenRejected = true),
            rows = listOf(row(1, State.FAILED, 3)),
            error = "timeout",
        )
        list.map { it.kind } shouldBe listOf(ProblemKind.RD_REJECTED, ProblemKind.DOWNLOAD_FAILED, ProblemKind.ANILIST_OFFLINE)
        GlassProblems.areas(list) shouldBe setOf("downloads", "discover")
    }
}
