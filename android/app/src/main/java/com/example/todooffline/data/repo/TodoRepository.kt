package com.example.todooffline.data.repo

import android.content.Context
import android.content.SharedPreferences
import com.example.todooffline.data.AuthSession
import com.example.todooffline.data.CircleInfo
import com.example.todooffline.data.FeedIdea
import com.example.todooffline.data.IdeaComment
import com.example.todooffline.data.PendingOperation
import com.example.todooffline.data.ReminderSettings
import com.example.todooffline.data.SyncState
import com.example.todooffline.data.TodoTask
import com.example.todooffline.data.VISIBILITY_PRIVATE
import com.example.todooffline.data.local.TodoLocalStore
import com.example.todooffline.data.nowIso
import com.example.todooffline.data.remote.ApiClient
import com.example.todooffline.data.remote.AuthRequest
import com.example.todooffline.data.remote.CommentRequest
import com.example.todooffline.data.remote.JoinCircleRequest
import com.example.todooffline.data.remote.OperationDto
import com.example.todooffline.data.remote.PushRequest
import com.example.todooffline.data.remote.RegisterRequest
import com.example.todooffline.data.remote.ReminderDto
import com.example.todooffline.data.remote.TodoApi
import com.example.todooffline.data.remote.executeData
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

class TodoRepository(context: Context) {
    private val appContext = context.applicationContext
    private val prefs: SharedPreferences = appContext.getSharedPreferences("todo-session", Context.MODE_PRIVATE)
    private val gson = Gson()

    val localStore: TodoLocalStore = TodoLocalStore(appContext)

    private fun api(): TodoApi {
        return ApiClient.create(prefs.getString("baseUrl", ApiClient.DEFAULT_BASE_URL) ?: ApiClient.DEFAULT_BASE_URL)
    }

    fun baseUrl(): String = prefs.getString("baseUrl", ApiClient.DEFAULT_BASE_URL) ?: ApiClient.DEFAULT_BASE_URL

    fun saveBaseUrl(value: String) {
        val normalized = if (value.endsWith("/")) value else "$value/"
        prefs.edit().putString("baseUrl", normalized).apply()
    }

    fun session(): AuthSession? {
        val token = prefs.getString("token", null) ?: return null
        val username = prefs.getString("username", "已登录") ?: "已登录"
        val circleId = prefs.getString("circleId", "") ?: ""
        return AuthSession(token, username, circleId)
    }

    fun register(username: String, email: String, password: String): AuthSession {
        val response = api().register(RegisterRequest(username, email, password)).executeData()
        val session = AuthSession(response.token, response.user.username, response.user.circleId)
        saveSession(session)
        localStore.clearAllUserData()
        sync()
        return session
    }

    fun login(username: String, password: String): AuthSession {
        val response = api().login(AuthRequest(username, password)).executeData()
        val session = AuthSession(response.token, response.user.username, response.user.circleId)
        saveSession(session)
        localStore.clearAllUserData()
        sync()
        return session
    }

    fun logout() {
        session()?.let { current ->
            runCatching { api().logout("Bearer ${current.token}").executeData() }
        }
        prefs.edit().remove("token").remove("username").remove("circleId").apply()
        localStore.clearAllUserData()
    }

    fun tasks(search: String = "", status: String? = null, category: String? = null, priority: String? = null): List<TodoTask> {
        return localStore.visibleTasks(search, status, category, priority)
    }

    fun createTask(
        title: String,
        content: String,
        status: String,
        category: String,
        priority: String,
        visibility: String = VISIBILITY_PRIVATE,
    ): TodoTask {
        val now = nowIso()
        val task = TodoTask(
            title = title.trim(),
            content = content,
            status = status,
            category = category,
            priority = priority,
            visibility = visibility,
            createdAt = now,
            updatedAt = now,
            syncState = SyncState.PENDING,
        )
        localStore.upsertTask(task)
        enqueueTaskOperation("create", task, baseVersion = 0)
        return task
    }

    fun updateTask(
        task: TodoTask,
        title: String,
        content: String,
        status: String,
        category: String,
        priority: String,
        visibility: String = task.visibility,
    ): TodoTask {
        val now = nowIso()
        val updated = task.copy(
            title = title.trim(),
            content = content,
            status = status,
            category = category,
            priority = priority,
            visibility = visibility,
            updatedAt = now,
            version = task.version + 1,
            syncState = SyncState.PENDING,
        )
        localStore.upsertTask(updated)
        enqueueTaskOperation("update", updated, baseVersion = task.version)
        return updated
    }

    fun deleteTask(task: TodoTask): TodoTask {
        val now = nowIso()
        val deleted = task.copy(
            deletedAt = now,
            updatedAt = now,
            version = task.version + 1,
            syncState = SyncState.PENDING,
        )
        localStore.upsertTask(deleted)
        enqueueTaskOperation("delete", deleted, baseVersion = task.version)
        return deleted
    }

    fun reminderSettings(): ReminderSettings = localStore.reminderSettings()

    fun saveReminderSettings(settings: ReminderSettings) {
        localStore.saveReminderSettings(settings.copy(updatedAt = nowIso()))
        localStore.setReminderDirty(true)
        runCatching { sync() }
    }

    fun sync(): SyncResult {
        val current = session() ?: return SyncResult(false, "未登录")
        val auth = "Bearer ${current.token}"
        val api = api()

        if (localStore.isReminderDirty()) {
            val reminder = localStore.reminderSettings()
            val remote = api.updateReminder(
                auth,
                ReminderDto(reminder.enabled, reminder.frequencySeconds, reminder.updatedAt),
            ).executeData()
            localStore.saveReminderSettings(remote.toReminder())
            localStore.setReminderDirty(false)
        }

        val pending = localStore.pendingOperations()
        if (pending.isNotEmpty()) {
            val request = PushRequest(
                operations = pending.map { operation ->
                    OperationDto(
                        operationId = operation.operationId,
                        taskId = operation.taskId,
                        operationType = operation.operationType,
                        baseVersion = operation.baseVersion,
                        localUpdatedAt = operation.localUpdatedAt,
                        payload = JsonParser.parseString(operation.payloadJson).asJsonObject,
                    )
                },
            )
            val pushed = api.push(auth, request).executeData()
            localStore.deleteOperations(pushed.accepted)
            pending.filter { it.operationId in pushed.accepted }.forEach { localStore.markTaskSynced(it.taskId) }
            pushed.conflicts.forEach { conflict ->
                conflict.serverTask?.let { localStore.upsertRemoteTask(it.toTask()) }
                localStore.markTaskConflict(conflict.taskId)
            }
            localStore.saveSyncCursor(pushed.nextCursor)
        }

        val pulled = api.pull(auth, localStore.syncCursor()).executeData()
        pulled.tasks.forEach { localStore.upsertRemoteTask(it.toTask()) }
        pulled.reminderSettings?.let {
            if (!localStore.isReminderDirty()) {
                localStore.saveReminderSettings(it.toReminder())
            }
        }
        localStore.saveSyncCursor(pulled.nextCursor)

        val pendingCount = localStore.pendingOperations(100).size
        val message = if (pendingCount == 0) "已同步" else "仍有 $pendingCount 条待同步"
        return SyncResult(true, message)
    }

    fun myCircle(): CircleInfo {
        val current = session() ?: throw IllegalStateException("未登录")
        return api().myCircle("Bearer ${current.token}").executeData().toCircleInfo()
    }

    fun joinedCircles(): List<CircleInfo> {
        val current = session() ?: throw IllegalStateException("未登录")
        return api().joinedCircles("Bearer ${current.token}").executeData().items.map { it.toCircleInfo() }
    }

    fun joinCircle(circleId: String): CircleInfo {
        val current = session() ?: throw IllegalStateException("未登录")
        return api().joinCircle("Bearer ${current.token}", JoinCircleRequest(circleId)).executeData().toCircleInfo()
    }

    fun leaveCircle(circleId: String) {
        val current = session() ?: throw IllegalStateException("未登录")
        api().leaveCircle("Bearer ${current.token}", circleId).executeData()
    }

    fun feed(circleId: String? = null): List<FeedIdea> {
        val current = session() ?: throw IllegalStateException("未登录")
        return api().feed("Bearer ${current.token}", circleId).executeData().items.map { it.toFeedIdea() }
    }

    fun setIdeaLiked(idea: FeedIdea, liked: Boolean): FeedIdea {
        val current = session() ?: throw IllegalStateException("未登录")
        val result = if (liked) {
            api().likeIdea("Bearer ${current.token}", idea.task.id).executeData()
        } else {
            api().unlikeIdea("Bearer ${current.token}", idea.task.id).executeData()
        }
        return idea.copy(
            likeCount = result.likeCount,
            commentCount = result.commentCount,
            likedByMe = result.likedByMe,
        )
    }

    fun comments(ideaId: String): List<IdeaComment> {
        val current = session() ?: throw IllegalStateException("未登录")
        return api().comments("Bearer ${current.token}", ideaId).executeData().items.map { it.toComment() }
    }

    fun createComment(ideaId: String, content: String): IdeaComment {
        val current = session() ?: throw IllegalStateException("未登录")
        return api().createComment("Bearer ${current.token}", ideaId, CommentRequest(content)).executeData().toComment()
    }

    fun hasPendingOperations(): Boolean = localStore.hasPendingOperations() || localStore.isReminderDirty()

    private fun saveSession(session: AuthSession) {
        prefs.edit()
            .putString("token", session.token)
            .putString("username", session.username)
            .putString("circleId", session.circleId)
            .apply()
    }

    private fun enqueueTaskOperation(operationType: String, task: TodoTask, baseVersion: Int) {
        val payload = task.toPayload()
        localStore.enqueueOperation(
            PendingOperation(
                operationId = UUID.randomUUID().toString(),
                taskId = task.id,
                operationType = operationType,
                payloadJson = gson.toJson(payload),
                baseVersion = baseVersion,
                localUpdatedAt = task.updatedAt,
            ),
        )
    }
}

data class SyncResult(
    val online: Boolean,
    val message: String,
)

private fun TodoTask.toPayload(): JsonObject {
    return JsonObject().apply {
        addProperty("id", id)
        addProperty("title", title)
        addProperty("content", content)
        addProperty("status", status)
        addProperty("category", category)
        addProperty("priority", priority)
        addProperty("visibility", visibility)
        addProperty("createdAt", createdAt)
        addProperty("updatedAt", updatedAt)
        addProperty("version", version)
        if (deletedAt == null) {
            add("deletedAt", null)
        } else {
            addProperty("deletedAt", deletedAt)
        }
    }
}
