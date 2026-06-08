package com.example.todooffline.data

import java.util.UUID

const val STATUS_TODO = "想做"
const val STATUS_DOING = "进行中"
const val STATUS_DONE = "完成"
const val STATUS_ABANDONED = "放弃"

const val CATEGORY_GAME = "游戏"
const val CATEGORY_PROGRAM = "程序"
const val CATEGORY_SKILL = "技能"
const val CATEGORY_OTHER = "其他"

const val PRIORITY_HIGH = "高"
const val PRIORITY_MEDIUM = "中"
const val PRIORITY_LOW = "低"

val taskStatuses = listOf(STATUS_TODO, STATUS_DOING, STATUS_DONE, STATUS_ABANDONED)
val taskCategories = listOf(CATEGORY_GAME, CATEGORY_PROGRAM, CATEGORY_SKILL, CATEGORY_OTHER)
val taskPriorities = listOf(PRIORITY_HIGH, PRIORITY_MEDIUM, PRIORITY_LOW)

data class TodoTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val content: String = "",
    val status: String = STATUS_TODO,
    val category: String = CATEGORY_OTHER,
    val priority: String = PRIORITY_MEDIUM,
    val createdAt: String,
    val updatedAt: String,
    val version: Int = 1,
    val deletedAt: String? = null,
    val syncState: SyncState = SyncState.SYNCED,
)

enum class SyncState {
    SYNCED,
    PENDING,
    CONFLICT,
}

data class ReminderSettings(
    val enabled: Boolean = false,
    val frequencySeconds: Int = 3600,
    val updatedAt: String = "",
)

data class PendingOperation(
    val operationId: String,
    val taskId: String,
    val operationType: String,
    val payloadJson: String,
    val baseVersion: Int,
    val localUpdatedAt: String,
    val retryCount: Int = 0,
)

data class AuthSession(
    val token: String,
    val username: String,
)

fun nowIso(): String {
    return java.time.Instant.now().toString()
}
