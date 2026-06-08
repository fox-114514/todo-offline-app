package com.example.todooffline.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.todooffline.data.CATEGORY_OTHER
import com.example.todooffline.data.PRIORITY_MEDIUM
import com.example.todooffline.data.ReminderSettings
import com.example.todooffline.data.STATUS_TODO
import com.example.todooffline.data.TodoTask
import com.example.todooffline.data.nowIso
import com.example.todooffline.data.repo.TodoRepository
import com.example.todooffline.reminder.ReminderScheduler
import com.example.todooffline.sync.SyncScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class TodoUiState(
    val authenticated: Boolean = false,
    val username: String = "",
    val tasks: List<TodoTask> = emptyList(),
    val search: String = "",
    val statusFilter: String? = null,
    val categoryFilter: String? = null,
    val priorityFilter: String? = null,
    val reminder: ReminderSettings = ReminderSettings(updatedAt = nowIso()),
    val baseUrl: String = "",
    val offline: Boolean = false,
    val syncing: Boolean = false,
    val syncMessage: String = "未同步",
    val authError: String? = null,
)

class TodoViewModel(
    private val appContext: Context,
    private val repository: TodoRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TodoUiState(baseUrl = repository.baseUrl()))
    val state: StateFlow<TodoUiState> = _state

    init {
        val session = repository.session()
        if (session != null) {
            _state.update {
                it.copy(
                    authenticated = true,
                    username = session.username,
                    reminder = repository.reminderSettings(),
                    syncMessage = if (repository.hasPendingOperations()) "待同步" else "本地可用",
                )
            }
            refreshLocal()
            syncNow()
        }
    }

    fun login(username: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, authError = null) }
            runCatching {
                withContext(Dispatchers.IO) { repository.login(username, password) }
            }.onSuccess { session ->
                _state.update {
                    it.copy(
                        authenticated = true,
                        username = session.username,
                        offline = false,
                        syncing = false,
                        syncMessage = "已登录",
                    )
                }
                refreshLocal()
                SyncScheduler.scheduleNow(appContext)
            }.onFailure { error ->
                _state.update {
                    it.copy(syncing = false, offline = true, authError = error.message ?: "登录失败")
                }
            }
        }
    }

    fun register(username: String, email: String, password: String) {
        viewModelScope.launch {
            _state.update { it.copy(syncing = true, authError = null) }
            runCatching {
                withContext(Dispatchers.IO) { repository.register(username, email, password) }
            }.onSuccess { session ->
                _state.update {
                    it.copy(
                        authenticated = true,
                        username = session.username,
                        offline = false,
                        syncing = false,
                        syncMessage = "已注册",
                    )
                }
                refreshLocal()
                SyncScheduler.scheduleNow(appContext)
            }.onFailure { error ->
                _state.update {
                    it.copy(syncing = false, offline = true, authError = error.message ?: "注册失败")
                }
            }
        }
    }

    fun logout() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.logout()
            withContext(Dispatchers.Main) {
                _state.value = TodoUiState(baseUrl = repository.baseUrl())
            }
        }
    }

    fun setSearch(value: String) {
        _state.update { it.copy(search = value) }
        refreshLocal()
    }

    fun setStatusFilter(value: String?) {
        _state.update { it.copy(statusFilter = value) }
        refreshLocal()
    }

    fun setCategoryFilter(value: String?) {
        _state.update { it.copy(categoryFilter = value) }
        refreshLocal()
    }

    fun setPriorityFilter(value: String?) {
        _state.update { it.copy(priorityFilter = value) }
        refreshLocal()
    }

    fun createTask(
        title: String,
        content: String,
        status: String = STATUS_TODO,
        category: String = CATEGORY_OTHER,
        priority: String = PRIORITY_MEDIUM,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.createTask(title, content, status, category, priority)
            syncAfterLocalChange()
        }
    }

    fun updateTask(task: TodoTask, title: String, content: String, status: String, category: String, priority: String) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateTask(task, title, content, status, category, priority)
            syncAfterLocalChange()
        }
    }

    fun deleteTask(task: TodoTask) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.deleteTask(task)
            syncAfterLocalChange()
        }
    }

    fun saveReminder(enabled: Boolean, frequencySeconds: Int) {
        val fixedFrequency = frequencySeconds.coerceIn(300, 86_400)
        viewModelScope.launch(Dispatchers.IO) {
            repository.saveReminderSettings(ReminderSettings(enabled, fixedFrequency, nowIso()))
            ReminderScheduler.scheduleNext(appContext)
            syncAfterLocalChange()
        }
    }

    fun saveBaseUrl(baseUrl: String) {
        repository.saveBaseUrl(baseUrl)
        _state.update { it.copy(baseUrl = repository.baseUrl()) }
    }

    fun syncNow() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.update { it.copy(syncing = true) }
            runCatching { repository.sync() }
                .onSuccess { result ->
                    refreshLocalOnMain(
                        offline = !result.online,
                        syncing = false,
                        message = result.message,
                    )
                }
                .onFailure { error ->
                    refreshLocalOnMain(
                        offline = true,
                        syncing = false,
                        message = error.message ?: "离线模式",
                    )
                }
        }
    }

    private fun syncAfterLocalChange() {
        SyncScheduler.scheduleNow(appContext)
        refreshLocalOnMain(
            offline = _state.value.offline,
            syncing = _state.value.syncing,
            message = "待同步",
        )
        runCatching { repository.sync() }
            .onSuccess { result ->
                refreshLocalOnMain(!result.online, false, result.message)
            }
            .onFailure {
                refreshLocalOnMain(true, false, "离线模式，稍后自动同步")
            }
    }

    private fun refreshLocal() {
        val current = _state.value
        val tasks = repository.tasks(
            search = current.search,
            status = current.statusFilter,
            category = current.categoryFilter,
            priority = current.priorityFilter,
        )
        _state.update {
            it.copy(
                tasks = tasks,
                reminder = repository.reminderSettings(),
                syncMessage = if (repository.hasPendingOperations()) "待同步" else it.syncMessage,
            )
        }
    }

    private fun refreshLocalOnMain(offline: Boolean, syncing: Boolean, message: String) {
        viewModelScope.launch(Dispatchers.Main) {
            val current = _state.value
            val tasks = withContext(Dispatchers.IO) {
                repository.tasks(current.search, current.statusFilter, current.categoryFilter, current.priorityFilter)
            }
            _state.update {
                it.copy(
                    tasks = tasks,
                    reminder = repository.reminderSettings(),
                    offline = offline,
                    syncing = syncing,
                    syncMessage = message,
                )
            }
        }
    }
}

class TodoViewModelFactory(
    private val context: Context,
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return TodoViewModel(context.applicationContext, TodoRepository(context)) as T
    }
}
