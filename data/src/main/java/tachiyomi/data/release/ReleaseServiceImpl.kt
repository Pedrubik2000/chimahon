package tachiyomi.data.release

import android.os.Build
import eu.kanade.tachiyomi.network.GET
import eu.kanade.tachiyomi.network.NetworkHelper
import eu.kanade.tachiyomi.network.awaitSuccess
import eu.kanade.tachiyomi.network.parseAs
import kotlinx.serialization.json.Json
import tachiyomi.domain.release.interactor.GetApplicationRelease
import tachiyomi.domain.release.model.Release
import tachiyomi.domain.release.service.ReleaseService

class ReleaseServiceImpl(
    private val networkService: NetworkHelper,
    private val json: Json,
) : ReleaseService {

    override suspend fun latest(arguments: GetApplicationRelease.Arguments): Release? {
        val release = with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases/latest"))
                .awaitSuccess()
                .parseAs<GithubRelease>()
        }

        val downloadLink = getDownloadLink(release = release, isFoss = arguments.isFoss) ?: return null

        return Release(
            version = release.version,
            info = release.info.substringBeforeLast("<!-->").replace(gitHubUsernameMentionRegex) { mention ->
                "[${mention.value}](https://github.com/${mention.value.substring(1)})"
            },
            releaseLink = release.releaseLink,
            downloadLink = downloadLink,
        )
    }

    // KMK -->
    override suspend fun releaseNotes(arguments: GetApplicationRelease.Arguments): List<Release> {
        return with(json) {
            networkService.client
                .newCall(GET("https://api.github.com/repos/${arguments.repository}/releases"))
                .awaitSuccess()
                .parseAs<List<GithubRelease>>()
                .mapNotNull { release ->
                    val downloadLink = getDownloadLink(release = release, isFoss = arguments.isFoss) ?: return@mapNotNull null

                    Release(
                        version = release.version,
                        info = release.info.replace(gitHubUsernameMentionRegex) { mention ->
                            "[${mention.value}](https://github.com/${mention.value.substring(1)})"
                        }
                            .substringBeforeLast("<!-->")
                            // KMK -->
                            .replace(getHubDownloadBadgeRegex, "")
                            .replace(gitHubCommitsCompareRegex) { matchResult ->
                                val owner = matchResult.groups["owner"]!!.value
                                val repo = matchResult.groups["repo"]!!.value
                                val from = matchResult.groups["from"]!!.value
                                val to = matchResult.groups["to"]!!.value
                                "[$owner/$repo@$from...$to](https://github.com/$owner/$repo/compare/$from...$to)"
                            },
                        // KMK <--
                        releaseLink = release.releaseLink,
                        downloadLink = downloadLink,
                        // KMK -->
                        preRelease = release.preRelease,
                        draft = release.draft,
                        // KMK <--
                    )
                }
        }
    }
    // KMK <--

    private fun getDownloadLink(release: GithubRelease, isFoss: Boolean): String? =
        // chimahon: pure part split out so it can be unit tested
        // every ABI the device runs, best first: an x86_64 device that also runs arm64 gets the arm64 APK
        // (and its release notes) instead of no release at all
        Build.SUPPORTED_ABIS.firstNotNullOfOrNull { pickDownloadLink(release.assets, it, isFoss) }

    companion object {
        /**
         * The APK for [abi] (e.g. "kumahon-1.2.0-arm64-v8a.apk"), else a universal APK (no ABI in its name),
         * else null: a release without a fitting APK is not offered.
         */
        internal fun pickDownloadLink(assets: List<GitHubAsset>, abi: String, isFoss: Boolean): String? {
            val map = assets.filter { it.name.endsWith(".apk") }.associate { asset ->
                BUILD_TYPES.find { "-$it" in asset.name } to asset.downloadLink
            }

            return if (!isFoss) {
                map[abi] ?: map[null]
            } else {
                map[FOSS]
            }
        }

        private const val FOSS = "foss"
        private val BUILD_TYPES = listOf(FOSS, "arm64-v8a", "armeabi-v7a", "x86_64", "x86")

        /**
         * Regular expression that matches a mention to a valid GitHub username, like it's
         * done in GitHub Flavored Markdown. It follows these constraints:
         *
         * - Alphanumeric with single hyphens (no consecutive hyphens)
         * - Cannot begin or end with a hyphen
         * - Max length of 39 characters
         *
         * Convert '(@cuong-tran)' to '([@cuong-tran](https://github.com/cuong-tran))'
         *
         * Reference: https://stackoverflow.com/a/30281147
         */
        private val gitHubUsernameMentionRegex = """\B@([a-z0-9](?:-(?=[a-z0-9])|[a-z0-9]){0,38}(?<=[a-z0-9]))"""
            .toRegex(RegexOption.IGNORE_CASE)

        // KMK -->
        private val getHubDownloadBadgeRegex = """\[!\[GitHub downloads]\(.*\)]\(.*\)"""
            .toRegex(RegexOption.IGNORE_CASE)

        /**
         * Convert from: https://github.com/komikku-app/komikku/compare/23d862d17...48fb4a2e6
         * to: [komikku-app/komikku@23d862d17...48fb4a2e6](https://github.com/komikku-app/komikku/compare/23d862d17...48fb4a2e6)
         */
        private val gitHubCommitsCompareRegex = """(\[[^]]+]\()?https://github\.com/(?<owner>[^/]+)/(?<repo>[^/]+)/compare/(?<from>[0-9a-f.rv]+)\.\.\.(?<to>[0-9a-f.rv]+)\)?"""
            .toRegex(RegexOption.IGNORE_CASE)
        // KMK <--
    }
}
