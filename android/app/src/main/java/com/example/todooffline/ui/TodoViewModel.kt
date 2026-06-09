package com.example.todooffline.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.todooffline.data.CATEGORY_OTHER
import com.example.todooffline.data.CircleInfo
import com.example.todooffline.data.FeedIdea
import com.example.todooffline.data.FriendRequest
import com.example.todooffline.data.IdeaComment
import com.example.todooffline.data.PRIORITY_MEDIUM
import com.example.todooffline.data.PublicUser
import com.example.todooffline.data.ReminderSettings
import com.example.todooffline.data.STATUS_TODO
import com.example.todooffline.data.TodoTask
import com.example.todooffline.data.VISIBILITY_PRIVATE
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

enum class MainTab {
    IDEAS,
    FEED,
}

data class TodoUiState(
    val authenticated: Boolean = false,
    val username: String = "",
    val circleId: String = "",
    val tab: MainTab = MainTab.IDEAS,
    val tasks: List<TodoTask> = emptyList(),
    val feed: List<FeedIdea> = emptyList(),
    val friends: List<CircleInfo> = emptyList(),
    val incomingFriendRequests: List<FriendRequest> = emptyList(),
    val outgoingFriendRequests: List<FriendRequest> = emptyList(),
    val selectedFeedCircleId: String? = null,
    val selectedIdea: FeedIdea? = null,
    val comments: List<IdeaComment> = emptyList(),
    val search: String = "",
    val statusFilter: String? = null,
    val reminder: ReminderSettings = ReminderSettings(updatedAt = nowIso()),
    val baseUrl: String = "",
    val offline: Boolean = false,
    val syncing: Boolean = false,
    val syncMessage: String = "未同步",
    val authError: String? = null,
    val socialError: String? = null,
)

private data class SocialSnapshot(
    val myCircle: CircleInfo,
    val friends: List<CircleInfo>,
    val incoming: List<FriendRequest>,
    val outgoing: List<FriendRequest>,
    val feed: List<FeedIdea>,
)

class TodoViewModel(
    private val appContext: Context,
    private val repository: TodoRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(TodoUiState(baseUrl = repository.baseUrl()))
    private val feedCache = mutableMapOf<String?, List<FeedIdea>>()
    val state: StateFlow<TodoUiState> = _state

    init {
        val session = repository.session()
        if (session != null) {
            _state.update {
                it.copy(
                    authenticated = true,
                    username = session.username,
                    circleId = session.circleId,
                    reminder = repository.reminderSettings(),
                    syncMessage = if (repository.hasPendingOperations()) "待同步" else "本地可用",
                )
            }
            refreshLocal()
            syncNow()
            loadSocial()
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
                        circleId = session.circleId,
                        offline = false,
                        syncing = false,
                        syncMessage = "已登录",
                    )
                }
                refreshLocal()
                SyncScheduler.scheduleNow(appContext)
                loadSocial()
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
                        circleId = session.circleId,
                        offline = false,
                        syncing = false,
                        syncMessage = "已注册",
                    )
                }
                refreshLocal()
                SyncScheduler.scheduleNow(appContext)
                loadSocial()
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

    fun setTab(tab: MainTab) {
        _state.update { it.copy(tab = tab) }
        if (tab == MainTab.FEED) loadSocial()
    }

    fun setSearch(value: String) {
        _state.update { it.copy(search = value) }
        refreshLocal()
    }

    fun setStatusFilter(value: String?) {
        _state.update { it.copy(statusFilter = value) }
        refreshLocal()
    }

    fun createTask(
        title: String,
        content: String,
        status: String = STATUS_TODO,
        category: String = CATEGORY_OTHER,
        priority: String = PRIORITY_MEDIUM,
        visibility: String = VISIBILITY_PRIVATE,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.createTask(title, content, status, category, priority, visibility)
            syncAfterLocalChange()
        }
    }

    fun updateTask(
        task: TodoTask,
        title: String,
        content: String,
        status: String,
        category: String,
        priority: String,
        visibility: String,
    ) {
        if (title.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            repository.updateTask(task, title, content, status, category, priority, visibility)
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
                    loadSocial()
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

    fun loadSocial() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val selectedCircleId = _state.value.selectedFeedCircleId
                val myCircle = repository.myCircle()
                val friends = repository.friends()
                val incoming = repository.incomingFriendRequests()
                val outgoing = repository.outgoingFriendRequests()
                val feed = repository.feed(selectedCircleId)
                selectedCircleId to SocialSnapshot(myCircle, friends, incoming, outgoing, feed)
            }.onSuccess { snapshot ->
                val selectedCircleId = snapshot.first
                val social = snapshot.second
                feedCache[selectedCircleId] = social.feed
                _state.update {
                    it.copy(
                        circleId = social.myCircle.circleId,
                        friends = social.friends,
                        incomingFriendRequests = social.incoming,
                        outgoingFriendRequests = social.outgoing,
                        feed = if (it.selectedFeedCircleId == selectedCircleId) social.feed else it.feed,
                        socialError = null,
                    )
                }
            }.onFailure { error ->
                _state.update {
                    it.copy(socialError = error.message ?: "好友圈加载失败")
                }
            }
        }
    }

    fun sendFriendRequest(circleId: String, introduction: String) {
        if (circleId.isBlank() || introduction.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createFriendRequest(circleId, introduction) }
                .onSuccess { loadSocial() }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "申请失败") } }
        }
    }

    fun acceptFriendRequest(requestId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.acceptFriendRequest(requestId) }
                .onSuccess { loadSocial() }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "通过失败") } }
        }
    }

    fun rejectFriendRequest(requestId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.rejectFriendRequest(requestId) }
                .onSuccess { loadSocial() }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "拒绝失败") } }
        }
    }

    fun removeFriend(circleId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.removeFriend(circleId) }
                .onSuccess {
                    feedCache.clear()
                    _state.update {
                        it.copy(selectedFeedCircleId = null)
                    }
                    loadSocial()
                }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "删除好友失败") } }
        }
    }

    fun selectFeedCircle(circleId: String?) {
        if (_state.value.selectedFeedCircleId == circleId) return
        _state.update {
            it.copy(
                selectedFeedCircleId = circleId,
                feed = cachedFeedFor(circleId),
            )
        }
        loadFeed(circleId)
    }

    fun loadFeed(circleId: String? = _state.value.selectedFeedCircleId) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.feed(circleId) }
                .onSuccess { feed ->
                    feedCache[circleId] = feed
                    _state.update {
                        if (it.selectedFeedCircleId == circleId) {
                            it.copy(feed = feed, socialError = null)
                        } else {
                            it
                        }
                    }
                }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "好友圈加载失败") } }
        }
    }

    fun toggleLike(idea: FeedIdea) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.setIdeaLiked(idea, !idea.likedByMe) }
                .onSuccess { updated ->
                    updateCachedIdea(updated)
                    _state.update { state ->
                        state.copy(
                            feed = state.feed.map { if (it.task.id == updated.task.id) updated else it },
                            selectedIdea = state.selectedIdea?.let { if (it.task.id == updated.task.id) updated else it },
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "点赞失败") } }
        }
    }

    fun openIdeaDetail(idea: FeedIdea) {
        _state.update { it.copy(selectedIdea = idea, comments = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val detail = repository.ideaDetail(idea.task.id)
                val comments = repository.comments(idea.task.id)
                detail to comments
            }.onSuccess { (detail, comments) ->
                    _state.update { it.copy(selectedIdea = detail, comments = comments, socialError = null) }
                }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "详情加载失败") } }
        }
    }

    fun openLocalIdeaDetail(task: TodoTask) {
        val author = PublicUser(id = "", username = _state.value.username, circleId = _state.value.circleId)
        openIdeaDetail(FeedIdea(task, author, likeCount = 0, commentCount = 0, likedByMe = false))
    }

    fun closeIdeaDetail() {
        _state.update { it.copy(selectedIdea = null, comments = emptyList()) }
    }

    fun createComment(content: String) {
        val idea = _state.value.selectedIdea ?: return
        if (content.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { repository.createComment(idea.task.id, content) }
                .onSuccess { comment ->
                    val updatedSelected = _state.value.selectedIdea?.let {
                        if (it.task.id == idea.task.id) it.copy(commentCount = it.commentCount + 1) else it
                    }
                    updatedSelected?.let { updateCachedIdea(it) }
                    _state.update { state ->
                        state.copy(
                            comments = state.comments + comment,
                            feed = state.feed.map {
                                if (it.task.id == idea.task.id) it.copy(commentCount = it.commentCount + 1) else it
                            },
                            selectedIdea = state.selectedIdea?.let {
                                if (it.task.id == idea.task.id) it.copy(commentCount = it.commentCount + 1) else it
                            },
                            socialError = null,
                        )
                    }
                }
                .onFailure { error -> _state.update { it.copy(socialError = error.message ?: "评论失败") } }
        }
    }

    private fun updateCachedIdea(idea: FeedIdea) {
        feedCache.keys.toList().forEach { key ->
            feedCache[key] = feedCache[key]?.map {
                if (it.task.id == idea.task.id) idea else it
            } ?: emptyList()
        }
    }

    private fun cachedFeedFor(circleId: String?): List<FeedIdea> {
        feedCache[circleId]?.let { return it }
        if (circleId != null) {
            return feedCache[null]?.filter { it.author.circleId == circleId } ?: emptyList()
        }
        return emptyList()
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
                loadSocial()
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
            category = null,
            priority = null,
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
                repository.tasks(current.search, current.statusFilter, null, null)
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
