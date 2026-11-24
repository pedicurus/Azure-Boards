package com.example.data.repository

import com.example.data.api.AzureDevOpsClient
import com.example.data.model.AddWorkItemCommentRequest
import com.example.data.model.JsonPatchOperation
import com.example.data.model.RawWorkItemComment
import com.example.data.model.WorkItemComment
import com.example.data.model.WorkItemCommentsResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import retrofit2.Response
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Domain repository responsible for fetching and posting work item discussions and comments.
 */
class AzureCommentsRepository {

    private data class CommentsRequestVariant(val expand: String?, val apiVersion: String)

    /** Index into [COMMENTS_REQUEST_VARIANTS] of the request style that last succeeded. */
    @Volatile
    private var preferredCommentsVariant = 0

    private companion object {
        val COMMENTS_REQUEST_VARIANTS = listOf(
            CommentsRequestVariant(expand = "all", apiVersion = "7.1-preview.4"),
            CommentsRequestVariant(expand = "renderedText", apiVersion = "7.1-preview.4"),
            CommentsRequestVariant(expand = null, apiVersion = "7.0-preview.3")
        )
        val NON_RETRYABLE_COMMENT_CODES = setOf(401, 403, 404)
    }

    fun formatCommentTimestamp(isoDate: String?): String =
        AzureIdentityRegistry.formatCommentTimestamp(isoDate)

    fun serializeCommentForAzureMarkdown(rawComment: String): String =
        AzureIdentityRegistry.serializeCommentForAzureMarkdown(rawComment)

    fun serializeCommentForHtmlFallback(rawComment: String): String =
        AzureIdentityRegistry.serializeCommentForHtmlFallback(rawComment)

    fun normalizeCommentTextFromAzure(rawText: String?, renderedText: String? = null): String? =
        AzureIdentityRegistry.normalizeCommentTextFromAzure(rawText, renderedText)

    private fun mapRawCommentToDomain(
        raw: RawWorkItemComment,
        fallbackWorkItemId: Int
    ): WorkItemComment? {
        if (raw.isDeleted == true) return null
        val authorId = raw.createdBy?.id?.trim()?.takeIf { it.isNotBlank() }
        val authorEmail = raw.createdBy?.uniqueName?.trim()?.takeIf { it.isNotBlank() }
        val authorAvatar = raw.createdBy?.imageUrl?.trim()?.takeIf { it.isNotBlank() }
        val authorName = raw.createdBy?.displayName?.trim()?.takeIf { it.isNotBlank() }
            ?: authorEmail
            ?: "User"

        AzureIdentityRegistry.registerKnownIdentity(
            id = authorId,
            displayName = raw.createdBy?.displayName,
            email = authorEmail,
            avatarUrl = authorAvatar
        )

        val extractedMentionIds = buildSet {
            raw.mentions.orEmpty().forEach { mention ->
                mention.targetId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
                mention.artifactId?.trim()?.takeIf { it.isNotEmpty() }?.let { add(it.lowercase()) }
            }
            AzureIdentityRegistry.extractMentionedIdsFromRawComment(raw.text, raw.renderedText)
                .forEach { add(it) }
        }.toList()

        val cleanedText = normalizeCommentTextFromAzure(raw.text, raw.renderedText)?.trim().orEmpty()
        if (cleanedText.isBlank()) return null
        val rawDate = raw.createdDate?.trim().orEmpty()
        return WorkItemComment(
            id = raw.id ?: rawDate.hashCode(),
            workItemId = raw.workItemId ?: fallbackWorkItemId,
            text = cleanedText,
            authorName = authorName,
            authorId = authorId,
            authorEmail = authorEmail,
            authorAvatar = authorAvatar,
            createdDate = rawDate,
            formattedDate = formatCommentTimestamp(rawDate),
            mentionedIds = extractedMentionIds
        )
    }

    /**
     * Fetches discussion comments for [workItemId] ordered newest-first.
     */
    suspend fun fetchWorkItemComments(
        organization: String,
        pat: String,
        project: String,
        workItemId: Int
    ): Result<List<WorkItemComment>> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
        if (normalizedOrg.isBlank() || pat.isBlank() || project.isBlank() || workItemId <= 0) {
            return@withContext Result.success(emptyList())
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val cleanProject = project.trim()

            // Try the request style that worked last time first; older servers only accept the
            // later ones. This avoids up to two failing requests per work item on every sync.
            var response: Response<WorkItemCommentsResponse>? = null
            for (index in preferredCommentsVariant until COMMENTS_REQUEST_VARIANTS.size) {
                val variant = COMMENTS_REQUEST_VARIANTS[index]
                val isLastVariant = index == COMMENTS_REQUEST_VARIANTS.lastIndex
                val attempt = if (isLastVariant) {
                    // Let a network failure on the final attempt surface as Result.failure.
                    service.getWorkItemComments(
                        organization = normalizedOrg,
                        project = cleanProject,
                        workItemId = workItemId,
                        top = 100,
                        order = "desc",
                        expand = variant.expand,
                        apiVersion = variant.apiVersion
                    )
                } else {
                    runCatching {
                        service.getWorkItemComments(
                            organization = normalizedOrg,
                            project = cleanProject,
                            workItemId = workItemId,
                            top = 100,
                            order = "desc",
                            expand = variant.expand,
                            apiVersion = variant.apiVersion
                        )
                    }.getOrNull()
                }
                response = attempt
                if (attempt != null && attempt.isSuccessful) {
                    preferredCommentsVariant = index
                    break
                }
                // Another request style cannot fix a missing item or a rejected token.
                if (attempt != null && attempt.code() in NON_RETRYABLE_COMMENT_CODES) break
            }
            if (response == null) {
                return@withContext Result.failure(Exception("Failed to load comments"))
            }

            if (!response.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to load comments (${response.code()}): ${response.message()}")
                )
            }
            val comments = response.body()?.comments.orEmpty()
                .mapNotNull { mapRawCommentToDomain(it, workItemId) }
                .sortedWith(
                    compareByDescending<WorkItemComment> { it.createdDate }
                        .thenByDescending { it.id }
                )
            Result.success(comments)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Posts a new comment on [workItemId] in Azure Boards using Markdown (`format=markdown`)
     * and `@<member>` mention syntax.
     */
    suspend fun addWorkItemCommentOnServer(
        organization: String,
        pat: String,
        project: String,
        workItemId: Int,
        commentText: String
    ): Result<WorkItemComment> = withContext(Dispatchers.IO) {
        val normalizedOrg = AzureAccountRepository.extractOrganization(organization)
        val trimmedText = commentText.trim()
        if (normalizedOrg.isBlank() || pat.isBlank() || workItemId <= 0 || trimmedText.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Comment text cannot be empty."))
        }
        try {
            val service = AzureDevOpsClient.createService(pat)
            val markdownBody = serializeCommentForAzureMarkdown(trimmedText)
            val htmlFallbackBody = serializeCommentForHtmlFallback(trimmedText)
            val normalizedLocalText = normalizeCommentTextFromAzure(trimmedText) ?: trimmedText

            if (project.isNotBlank()) {
                val cleanProject = project.trim()
                var commentResp = runCatching {
                    service.addWorkItemComment(
                        organization = normalizedOrg,
                        project = cleanProject,
                        workItemId = workItemId,
                        format = "markdown",
                        apiVersion = "7.1-preview.4",
                        request = AddWorkItemCommentRequest(text = markdownBody)
                    )
                }.getOrNull()

                if (commentResp == null || !commentResp.isSuccessful) {
                    commentResp = runCatching {
                        service.addWorkItemComment(
                            organization = normalizedOrg,
                            project = cleanProject,
                            workItemId = workItemId,
                            format = null,
                            apiVersion = "7.0-preview.3",
                            request = AddWorkItemCommentRequest(text = htmlFallbackBody)
                        )
                    }.getOrNull()
                }

                if (commentResp?.isSuccessful == true && commentResp.body() != null) {
                    val mapped = mapRawCommentToDomain(commentResp.body()!!, workItemId)
                    if (mapped != null) {
                        return@withContext Result.success(mapped)
                    }
                }
            }

            // Fallback: PATCH /fields/System.History
            val patchOps = listOf(
                JsonPatchOperation(
                    op = "add",
                    path = "/fields/System.History",
                    value = htmlFallbackBody
                )
            )
            val patchResp = service.updateWorkItem(
                organization = normalizedOrg,
                id = workItemId,
                patchBody = AzureDevOpsClient.createPatchRequestBody(patchOps)
            )
            if (!patchResp.isSuccessful) {
                return@withContext Result.failure(
                    Exception("Failed to post comment (${patchResp.code()}): ${patchResp.message()}")
                )
            }

            val nowIso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.format(Date())

            Result.success(
                WorkItemComment(
                    id = (System.currentTimeMillis() and 0x7FFFFFFF).toInt(),
                    workItemId = workItemId,
                    text = normalizedLocalText,
                    authorName = "You",
                    createdDate = nowIso,
                    formattedDate = formatCommentTimestamp(nowIso)
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
