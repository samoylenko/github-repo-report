package io.samoylenko.tools

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.context
import com.github.ajalt.clikt.output.MordantHelpFormatter
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.help
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.help
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.file
import com.github.ajalt.clikt.parameters.types.int
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.auth.*
import io.ktor.client.plugins.auth.providers.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.apache.commons.csv.CSVFormat
import org.apache.commons.csv.CSVPrinter
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import io.ktor.client.plugins.logging.Logger as KtorLogger

fun main(args: Array<String>) = GitHubRepoReportCli().main(args)

// TODO: cache raw responses to reparse cache instead of invalidating every time when using new fields.
class GitHubRepoReportCli : CliktCommand(
    help = """
        GitHub Repository Report Generator.
        Will enumerate all active repositories for an organization and capture required information in a csv report.
    """.trimIndent()
) {
    companion object {
        val logger: Logger = LoggerFactory.getLogger(GitHubRepoReportCli::class.java)

        val json = Json {
            isLenient = true
            ignoreUnknownKeys = true
            prettyPrint = true
        }
    }

    init {
        context {
            helpFormatter = { MordantHelpFormatter(it, showDefaultValues = true) }
        }
    }

    private val org: String by argument()
        .help("Target GitHub Organization")
    private val token: String by option(envvar = "GITHUB_TOKEN").required()
        .help("GitHub token. Uses GITHUB_TOKEN environment variable if nothing provided in the command line")
    private val reportFile: File by option().file().default(File("report.csv"))
        .help("Generated report file")
    private val repoListCacheFile: File by option().file().default(File("repolistcache.json"))
        .help("(Internal) Repository list cache file")
    private val repoInfoCacheFile: File by option().file().default(File("repoinfocache.json"))
        .help("(Internal) Repository information cache file")
    private val pageSize: Int by option().int().default(50)
        .help("(Internal) GitHub REST API page size")

    override fun run() {
        logger.info("Starting GitHubRepoReportCli...")
        logger.info("GitHub Organization: '{}'", org)
        logger.info("Report File: '{}'", reportFile.path)
        logger.info("Cache files: '{}', '{}'", repoListCacheFile.path, repoInfoCacheFile.path)

        HttpClient(CIO) {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    isLenient = true
                })
            }

            install(Auth) {
                bearer {
                    loadTokens {
                        BearerTokens(token, "")
                    }
                }
            }

            install(Logging) {
                logger = KtorLogger.DEFAULT
                level = LogLevel.NONE
                sanitizeHeader { header -> header == HttpHeaders.Authorization }
            }

            install(HttpRequestRetry) {
                retryOnServerErrors(maxRetries = 3)
                exponentialDelay()
            }

            defaultRequest {
                url("https://api.github.com/")

                headers {
                    append(HttpHeaders.Accept, "application/vnd.github+json")
                    append("X-GitHub-Api-Version", "2022-11-28")
                }
            }

        }.use { client ->

            val repoList: List<RepositoryInfo> = if (repoListCacheFile.exists()) {
                logger.info(
                    "Found exisint repolist cache. Delete this file to invalidate: '{}'",
                    repoListCacheFile.path
                )
                json.decodeFromString(repoListCacheFile.readText())
            } else {
                logger.info("Getting repository list from API...")
                getRepoList(client).also {
                    repoListCacheFile.writeText(json.encodeToString(it))
                    logger.info("Pausing for 15 seconds...")
                    Thread.sleep(1_000 * 15)
                }
            }
            logger.info("Repositories count: '{}'", repoList.size)

            val builder: CSVFormat = CSVFormat.Builder.create()
                .setHeader(
                    "Repository",
                    "Description",
                    "Uses Jenkins",
                    "Uses GitHub Actions",
                    "Gradle Build (root)",
                    "Languages",
                    "Private",
                    "Archived",
                    "Updated",
                )
                .build()

            val repoInfoCache: MutableMap<String, RepoAnalysisResult> =
                if (repoInfoCacheFile.exists()) json.decodeFromString(repoInfoCacheFile.readText())
                else mutableMapOf()

            logger.info("Writing report file...")
            reportFile.bufferedWriter().use { writer ->
                val printer = CSVPrinter(writer, builder)
                var processedSoFar = 0

                logger.warn("Skipping all archived repositories. Counts below may be smaller than total above.")
                repoList
                    .filterNot { it.archived }
                    .filter { repoInfoCache.keys.contains(it.fullName) }
                    .forEach { repo ->

                        val repoAnalysis = repoInfoCache[repo.fullName]
                            ?: throw Error("Cache error. Delete the ${repoInfoCacheFile.path} file")

                        writeReport(printer, repo, repoAnalysis)
                        logger.info(
                            "Repositories processed so far: '{}'. Cached: '{}'",
                            ++processedSoFar,
                            repo.fullName
                        )
                    }

                repoList
                    .filterNot { it.archived }
                    .filterNot { repoInfoCache.keys.contains(it.fullName) }
                    .forEach { repo ->

                        val repoAnalysis = analyseRepository(client, repo.name)

                        writeReport(printer, repo, repoAnalysis)
                        repoInfoCache[repo.fullName] = repoAnalysis
                        repoInfoCacheFile.writeText(json.encodeToString(repoInfoCache))
                        logger.info(
                            "Repositories processed so far: '{}'. Received: '{}'",
                            ++processedSoFar,
                            repo.fullName
                        )

                        Thread.sleep(1_000)
                    }
                logger.info("All done!")
            }
        }

    }

    private fun writeReport(
        printer: CSVPrinter,
        repo: RepositoryInfo,
        repoAnalysis: RepoAnalysisResult
    ) {
        printer.printRecord(
            repo.fullName,
            repo.description,
            repoAnalysis.hasJenkinsfile,
            repoAnalysis.usesGitHubActions,
            repoAnalysis.hasGradleBuildScriptInRoot,
            repoAnalysis.languages.joinToString(separator = ", "),
            repo.private,
            repo.archived,
            repo.updatedAt.toLocalDateTime(TimeZone.currentSystemDefault()).date,
        )

        printer.flush()
    }

    private fun analyseRepository(client: HttpClient, repoName: String): RepoAnalysisResult {
        val jenkinsCheckResponse =
            runBlocking { client.get("repos/$org/$repoName/contents/Jenkinsfile") }
        val ghaCheckResponse =
            runBlocking { client.get("repos/$org/$repoName/contents/.github/workflows") }
        val gradleCheckResponse =
            runBlocking { client.get("repos/$org/$repoName/contents/build.gradle") }
        val gradleKtCheckResponse =
            runBlocking { client.get("repos/$org/$repoName/contents/build.gradle.kts") }

        return RepoAnalysisResult(
            hasJenkinsfile = (jenkinsCheckResponse.status == HttpStatusCode.OK),
            usesGitHubActions = processGhaCheck(ghaCheckResponse),
            hasGradleBuildScriptInRoot = ((gradleCheckResponse.status == HttpStatusCode.OK) || (gradleKtCheckResponse.status == HttpStatusCode.OK)),
            languages = getLanguages(client, repoName)
        )
    }

    private fun processGhaCheck(ghaCheckResponse: HttpResponse): Boolean =
        if (ghaCheckResponse.status != HttpStatusCode.OK)
            false
        else
            runBlocking { ghaCheckResponse.body<List<RepoContents>>() }
                .any { it.path.startsWith(".github/workflows/", ignoreCase = true) }

    private fun getLanguages(client: HttpClient, repoName: String): Set<String> =
        runBlocking { client.get("repos/$org/$repoName/languages").body<Map<String, Int>>().keys }

    private fun getRepoList(client: HttpClient): List<RepositoryInfo> {
        val repoList = mutableListOf<RepositoryInfo>()
        var page = 1

        do {
            val repoListPage: List<RepositoryInfo> = runBlocking {
                client.get("https://api.github.com/orgs/$org/repos") {
                    url {
                        parameters.append("per_page", "$pageSize")
                        parameters.append("page", "${page++}")
                    }
                }.body()
            }

            repoList += repoListPage

            logger.info(
                "Page: '{}'. Current count: '{}'. Latest repository received: '{}'",
                (page - 1),
                repoList.size,
                repoListPage.last().fullName
            )

        } while (repoListPage.size >= pageSize)

        return repoList
    }
}
