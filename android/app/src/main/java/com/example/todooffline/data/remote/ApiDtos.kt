package com.example.todooffline.data.remote

import com.example.todooffline.data.ReminderSettings
import com.example.todooffline.data.CircleInfo
import com.example.todooffline.data.FeedIdea
import com.example.todooffline.data.IdeaComment
import com.example.todooffline.data.PublicUser
import com.example.todooffline.data.SyncState
import com.example.todooffline.data.TodoTask
import com.google.gson.JsonObject

data class ApiResponse<T>(
    val code: Int,
    val message: String,
    val data: T?,
)

data class AuthRequest(
    val username: String,
    val password: String,
)

data class RegisterRequest(
    val username: String,
    val email: String,
    val password: String,
)

data class UserDto(
    val id: String,
    val username: String,
    val email: String,
    val circleId: String,
    val createdAt: String,
    val updatedAt: String,
)

data class PublicUserDto(
    val id: String,
    val username: String,
    val circleId: String,
) {
    fun toPublicUser(): PublicUser = PublicUser(id, username, circleId)
}

data class AuthResponse(
    val user: UserDto,
    val token: String,
    val expiresAt: String,
)

data class TaskDto(
    val id: String,
    val title: String,
    val content: String,
    val status: String,
    val category: String,
    val priority: String,
    val visibility: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val deletedAt: String?,
) {
    fun toTask(): TodoTask = TodoTask(
        id = id,
        title = title,
        content = content,
        status = status,
        category = category,
        priority = priority,
        visibility = visibility,
        createdAt = createdAt,
        updatedAt = updatedAt,
        version = version,
        deletedAt = deletedAt,
        syncState = SyncState.SYNCED,
    )
}

data class ReminderDto(
    val enabled: Boolean,
    val frequencySeconds: Int,
    val updatedAt: String,
) {
    fun toReminder(): ReminderSettings = ReminderSettings(enabled, frequencySeconds, updatedAt)
}

data class PullResponse(
    val tasks: List<TaskDto>,
    val reminderSettings: ReminderDto?,
    val nextCursor: String,
    val isFullSync: Boolean,
)

data class PushRequest(
    val operations: List<OperationDto>,
)

data class OperationDto(
    val operationId: String,
    val taskId: String,
    val operationType: String,
    val baseVersion: Int,
    val localUpdatedAt: String,
    val payload: JsonObject,
)

data class PushResponse(
    val accepted: List<String>,
    val conflicts: List<ConflictDto>,
    val nextCursor: String,
)

data class CircleDto(
    val circleId: String,
    val owner: PublicUserDto,
    val joinedAt: String?,
    val memberCount: Int?,
) {
    fun toCircleInfo(): CircleInfo = CircleInfo(
        circleId = circleId,
        owner = owner.toPublicUser(),
        joinedAt = joinedAt,
        memberCount = memberCount,
    )
}

data class JoinedCirclesResponse(
    val items: List<CircleDto>,
)

data class JoinCircleRequest(
    val circleId: String,
)

data class FeedIdeaDto(
    val id: String,
    val title: String,
    val content: String,
    val status: String,
    val category: String,
    val priority: String,
    val visibility: String,
    val createdAt: String,
    val updatedAt: String,
    val version: Int,
    val deletedAt: String?,
    val author: PublicUserDto,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
) {
    fun toFeedIdea(): FeedIdea = FeedIdea(
        task = TodoTask(
            id = id,
            title = title,
            content = content,
            status = status,
            category = category,
            priority = priority,
            visibility = visibility,
            createdAt = createdAt,
            updatedAt = updatedAt,
            version = version,
            deletedAt = deletedAt,
            syncState = SyncState.SYNCED,
        ),
        author = author.toPublicUser(),
        likeCount = likeCount,
        commentCount = commentCount,
        likedByMe = likedByMe,
    )
}

data class FeedResponse(
    val items: List<FeedIdeaDto>,
    val page: Int,
    val pageSize: Int,
    val total: Int,
)

data class LikeResponse(
    val ideaId: String,
    val likeCount: Int,
    val commentCount: Int,
    val likedByMe: Boolean,
)

data class CommentDto(
    val id: String,
    val ideaId: String,
    val author: PublicUserDto,
    val content: String,
    val createdAt: String,
    val deletedAt: String?,
) {
    fun toComment(): IdeaComment = IdeaComment(
        id = id,
        ideaId = ideaId,
        author = author.toPublicUser(),
        content = content,
        createdAt = createdAt,
        deletedAt = deletedAt,
    )
}

data class CommentsResponse(
    val items: List<CommentDto>,
)

data class CommentRequest(
    val content: String,
)

data class ConflictDto(
    val operationId: String,
    val taskId: String,
    val serverTask: TaskDto?,
    val message: String?,
)
