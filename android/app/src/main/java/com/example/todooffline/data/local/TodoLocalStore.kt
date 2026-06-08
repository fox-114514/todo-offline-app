package com.example.todooffline.data.local

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.example.todooffline.data.PendingOperation
import com.example.todooffline.data.ReminderSettings
import com.example.todooffline.data.SyncState
import com.example.todooffline.data.TodoTask
import com.example.todooffline.data.VISIBILITY_PRIVATE

class TodoLocalStore(context: Context) : SQLiteOpenHelper(context, "todo-offline.db", null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE tasks (
                id TEXT PRIMARY KEY,
                title TEXT NOT NULL,
                content TEXT NOT NULL,
                status TEXT NOT NULL,
                category TEXT NOT NULL,
                priority TEXT NOT NULL,
                visibility TEXT NOT NULL DEFAULT 'private',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                version INTEGER NOT NULL,
                deleted_at TEXT,
                sync_state TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL("CREATE INDEX idx_tasks_visible ON tasks(deleted_at, updated_at)")
        db.execSQL(
            """
            CREATE TABLE pending_operations (
                operation_id TEXT PRIMARY KEY,
                task_id TEXT NOT NULL,
                operation_type TEXT NOT NULL,
                payload_json TEXT NOT NULL,
                base_version INTEGER NOT NULL,
                local_updated_at TEXT NOT NULL,
                retry_count INTEGER NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE reminder_settings (
                id INTEGER PRIMARY KEY CHECK(id = 1),
                enabled INTEGER NOT NULL,
                frequency_seconds INTEGER NOT NULL,
                updated_at TEXT NOT NULL
            )
            """.trimIndent(),
        )
        db.execSQL(
            """
            INSERT INTO reminder_settings(id, enabled, frequency_seconds, updated_at)
            VALUES(1, 0, 3600, '')
            """.trimIndent(),
        )
        db.execSQL(
            """
            CREATE TABLE app_state (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL
            )
            """.trimIndent(),
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            val columns = db.rawQuery("PRAGMA table_info(tasks)", emptyArray()).useRows { cursor ->
                cursor.getString(cursor.getColumnIndexOrThrow("name"))
            }.toSet()
            if ("visibility" !in columns) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN visibility TEXT NOT NULL DEFAULT 'private'")
            }
        }
    }

    fun visibleTasks(search: String = "", status: String? = null, category: String? = null, priority: String? = null): List<TodoTask> {
        val where = mutableListOf("deleted_at IS NULL")
        val args = mutableListOf<String>()
        if (search.isNotBlank()) {
            where += "(title LIKE ? OR content LIKE ?)"
            args += "%$search%"
            args += "%$search%"
        }
        if (!status.isNullOrBlank()) {
            where += "status = ?"
            args += status
        }
        if (!category.isNullOrBlank()) {
            where += "category = ?"
            args += category
        }
        if (!priority.isNullOrBlank()) {
            where += "priority = ?"
            args += priority
        }
        return readableDatabase.query(
            "tasks",
            null,
            where.joinToString(" AND "),
            args.toTypedArray(),
            null,
            null,
            "updated_at DESC",
        ).useRows { cursor -> cursor.toTask() }
    }

    fun allTasksIncludingDeleted(): List<TodoTask> {
        return readableDatabase.query("tasks", null, null, null, null, null, "updated_at DESC")
            .useRows { cursor -> cursor.toTask() }
    }

    fun randomIncompleteTask(): TodoTask? {
        return readableDatabase.rawQuery(
            """
            SELECT * FROM tasks
            WHERE deleted_at IS NULL AND status NOT IN ('完成', '放弃')
            ORDER BY RANDOM()
            LIMIT 1
            """.trimIndent(),
            emptyArray(),
        ).useRows { cursor -> cursor.toTask() }.firstOrNull()
    }

    fun getTask(id: String): TodoTask? {
        return readableDatabase.query("tasks", null, "id = ?", arrayOf(id), null, null, null)
            .useRows { cursor -> cursor.toTask() }
            .firstOrNull()
    }

    fun upsertTask(task: TodoTask) {
        writableDatabase.insertWithOnConflict(
            "tasks",
            null,
            task.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun upsertRemoteTask(task: TodoTask) {
        if (hasPendingOperationForTask(task.id)) return
        upsertTask(task.copy(syncState = SyncState.SYNCED))
    }

    fun enqueueOperation(operation: PendingOperation) {
        writableDatabase.insertWithOnConflict(
            "pending_operations",
            null,
            operation.toValues(),
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun pendingOperations(limit: Int = 100): List<PendingOperation> {
        return readableDatabase.query(
            "pending_operations",
            null,
            null,
            null,
            null,
            null,
            "created_at ASC",
            limit.toString(),
        ).useRows { cursor -> cursor.toOperation() }
    }

    fun deleteOperations(operationIds: List<String>) {
        if (operationIds.isEmpty()) return
        val placeholders = operationIds.joinToString(",") { "?" }
        writableDatabase.delete("pending_operations", "operation_id IN ($placeholders)", operationIds.toTypedArray())
    }

    fun markTaskSynced(taskId: String) {
        val values = ContentValues().apply {
            put("sync_state", SyncState.SYNCED.name)
        }
        writableDatabase.update("tasks", values, "id = ?", arrayOf(taskId))
    }

    fun markTaskConflict(taskId: String) {
        val values = ContentValues().apply {
            put("sync_state", SyncState.CONFLICT.name)
        }
        writableDatabase.update("tasks", values, "id = ?", arrayOf(taskId))
    }

    fun hasPendingOperations(): Boolean = pendingOperations(1).isNotEmpty()

    fun hasPendingOperationForTask(taskId: String): Boolean {
        return readableDatabase.query(
            "pending_operations",
            arrayOf("operation_id"),
            "task_id = ?",
            arrayOf(taskId),
            null,
            null,
            null,
            "1",
        ).use { cursor -> cursor.moveToFirst() }
    }

    fun reminderSettings(): ReminderSettings {
        return readableDatabase.query(
            "reminder_settings",
            null,
            "id = 1",
            emptyArray(),
            null,
            null,
            null,
        ).useRows { cursor -> cursor.toReminder() }.firstOrNull() ?: ReminderSettings()
    }

    fun saveReminderSettings(settings: ReminderSettings) {
        writableDatabase.insertWithOnConflict(
            "reminder_settings",
            null,
            ContentValues().apply {
                put("id", 1)
                put("enabled", if (settings.enabled) 1 else 0)
                put("frequency_seconds", settings.frequencySeconds)
                put("updated_at", settings.updatedAt)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }

    fun isReminderDirty(): Boolean = getState("reminderDirty") == "1"

    fun setReminderDirty(dirty: Boolean) = setState("reminderDirty", if (dirty) "1" else "0")

    fun syncCursor(): String? = getState("syncCursor")

    fun saveSyncCursor(cursor: String) = setState("syncCursor", cursor)

    fun clearAllUserData() {
        writableDatabase.transaction {
            delete("tasks", null, null)
            delete("pending_operations", null, null)
            delete("app_state", null, null)
            delete("reminder_settings", null, null)
            insert(
                "reminder_settings",
                null,
                ContentValues().apply {
                    put("id", 1)
                    put("enabled", 0)
                    put("frequency_seconds", 3600)
                    put("updated_at", "")
                },
            )
        }
    }

    private fun getState(key: String): String? {
        return readableDatabase.query("app_state", arrayOf("value"), "key = ?", arrayOf(key), null, null, null)
            .use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }

    private fun setState(key: String, value: String) {
        writableDatabase.insertWithOnConflict(
            "app_state",
            null,
            ContentValues().apply {
                put("key", key)
                put("value", value)
            },
            SQLiteDatabase.CONFLICT_REPLACE,
        )
    }
}

private fun TodoTask.toValues(): ContentValues = ContentValues().apply {
    put("id", id)
    put("title", title)
    put("content", content)
    put("status", status)
    put("category", category)
    put("priority", priority)
    put("visibility", visibility)
    put("created_at", createdAt)
    put("updated_at", updatedAt)
    put("version", version)
    put("deleted_at", deletedAt)
    put("sync_state", syncState.name)
}

private fun PendingOperation.toValues(): ContentValues = ContentValues().apply {
    put("operation_id", operationId)
    put("task_id", taskId)
    put("operation_type", operationType)
    put("payload_json", payloadJson)
    put("base_version", baseVersion)
    put("local_updated_at", localUpdatedAt)
    put("retry_count", retryCount)
    put("created_at", System.currentTimeMillis())
}

private fun Cursor.toTask(): TodoTask = TodoTask(
    id = getString(getColumnIndexOrThrow("id")),
    title = getString(getColumnIndexOrThrow("title")),
    content = getString(getColumnIndexOrThrow("content")),
    status = getString(getColumnIndexOrThrow("status")),
    category = getString(getColumnIndexOrThrow("category")),
    priority = getString(getColumnIndexOrThrow("priority")),
    visibility = if (hasColumn("visibility")) getString(getColumnIndexOrThrow("visibility")) else VISIBILITY_PRIVATE,
    createdAt = getString(getColumnIndexOrThrow("created_at")),
    updatedAt = getString(getColumnIndexOrThrow("updated_at")),
    version = getInt(getColumnIndexOrThrow("version")),
    deletedAt = getStringOrNull("deleted_at"),
    syncState = SyncState.valueOf(getString(getColumnIndexOrThrow("sync_state"))),
)

private fun Cursor.toOperation(): PendingOperation = PendingOperation(
    operationId = getString(getColumnIndexOrThrow("operation_id")),
    taskId = getString(getColumnIndexOrThrow("task_id")),
    operationType = getString(getColumnIndexOrThrow("operation_type")),
    payloadJson = getString(getColumnIndexOrThrow("payload_json")),
    baseVersion = getInt(getColumnIndexOrThrow("base_version")),
    localUpdatedAt = getString(getColumnIndexOrThrow("local_updated_at")),
    retryCount = getInt(getColumnIndexOrThrow("retry_count")),
)

private fun Cursor.toReminder(): ReminderSettings = ReminderSettings(
    enabled = getInt(getColumnIndexOrThrow("enabled")) == 1,
    frequencySeconds = getInt(getColumnIndexOrThrow("frequency_seconds")),
    updatedAt = getString(getColumnIndexOrThrow("updated_at")),
)

private fun Cursor.getStringOrNull(column: String): String? {
    val index = getColumnIndexOrThrow(column)
    return if (isNull(index)) null else getString(index)
}

private fun Cursor.hasColumn(column: String): Boolean = getColumnIndex(column) >= 0

private inline fun <T> Cursor.useRows(map: (Cursor) -> T): List<T> {
    val rows = mutableListOf<T>()
    use { cursor ->
        while (cursor.moveToNext()) rows += map(cursor)
    }
    return rows
}

private inline fun SQLiteDatabase.transaction(block: SQLiteDatabase.() -> Unit) {
    beginTransaction()
    try {
        block()
        setTransactionSuccessful()
    } finally {
        endTransaction()
    }
}
