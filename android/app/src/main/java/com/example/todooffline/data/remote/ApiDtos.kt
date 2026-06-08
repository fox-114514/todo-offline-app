package com.example.todooffline.data.remote

import com.example.todooffline.data.ReminderSettings
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
    val createdAt: String,
    val updatedAt: String,
)

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

data class ConflictDto(
    val operationId: String,
    val taskId: String,
    val serverTask: TaskDto?,
    val message: String?,
)
