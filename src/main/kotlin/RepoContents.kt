package io.samoylenko.tools

import kotlinx.serialization.Serializable

@Serializable
data class RepoContents(
    val name: String,
    val path: String
)
