package io.samoylenko.tools

import kotlinx.serialization.Serializable

@Serializable
data class RepoAnalysisResult(
    val hasJenkinsfile: Boolean,
    val usesGitHubActions: Boolean,
    val hasGradleBuildScriptInRoot: Boolean,
    val languages: Set<String>
)
