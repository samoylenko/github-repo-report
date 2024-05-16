package io.samoylenko.tools

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RepositoryInfo(
    val id: Int,
    val name: String,
    @SerialName("full_name") val fullName: String,
    val private: Boolean,
    @SerialName("html_url") val htmlUrl: String,
    val description: String? = null,
    val archived: Boolean,
    @SerialName("updated_at") val updatedAt: Instant
)
