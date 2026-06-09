package com.example.todooffline

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.todooffline.data.CATEGORY_OTHER
import com.example.todooffline.data.CircleInfo
import com.example.todooffline.data.FeedIdea
import com.example.todooffline.data.FriendRequest
import com.example.todooffline.data.IdeaComment
import com.example.todooffline.data.PRIORITY_MEDIUM
import com.example.todooffline.data.STATUS_TODO
import com.example.todooffline.data.SyncState
import com.example.todooffline.data.TodoTask
import com.example.todooffline.data.VISIBILITY_CIRCLE
import com.example.todooffline.data.VISIBILITY_PRIVATE
import com.example.todooffline.data.taskCategories
import com.example.todooffline.data.taskPriorities
import com.example.todooffline.data.taskStatuses
import com.example.todooffline.reminder.ensureChannel
import com.example.todooffline.ui.MainTab
import com.example.todooffline.ui.TodoUiState
import com.example.todooffline.ui.TodoViewModel
import com.example.todooffline.ui.TodoViewModelFactory

private enum class SocialPage {
    FEED,
    ADD_FRIEND,
    FRIENDS,
}

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= 33) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val factory = TodoViewModelFactory(LocalContext.current)
                    val viewModel: TodoViewModel = viewModel(factory = factory)
                    TodoApp(viewModel)
                }
            }
        }
    }
}

@Composable
private fun TodoApp(viewModel: TodoViewModel) {
    val state by viewModel.state.collectAsState()
    if (!state.authenticated) {
        AuthScreen(state, viewModel)
    } else {
        MainScreen(state, viewModel)
    }
}

@Composable
private fun AuthScreen(state: TodoUiState, viewModel: TodoViewModel) {
    var registerMode by remember { mutableStateOf(false) }
    var username by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Idea Board", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("私藏想法，也可以发到自己的好友圈。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(24.dp))
        OutlinedTextField(username, { username = it }, label = { Text("用户名或邮箱") }, modifier = Modifier.fillMaxWidth())
        if (registerMode) {
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(email, { email = it }, label = { Text("邮箱") }, modifier = Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            password,
            { password = it },
            label = { Text("密码") },
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("后端地址") }, modifier = Modifier.fillMaxWidth())
        state.authError?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                viewModel.saveBaseUrl(baseUrl)
                if (registerMode) viewModel.register(username, email, password) else viewModel.login(username, password)
            },
            enabled = !state.syncing,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (registerMode) "注册并登录" else "登录")
        }
        TextButton(onClick = { registerMode = !registerMode }, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text(if (registerMode) "已有账号，去登录" else "没有账号，去注册")
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreen(state: TodoUiState, viewModel: TodoViewModel) {
    var editorTask by remember { mutableStateOf<TodoTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(if (state.tab == MainTab.IDEAS) "我的 Idea" else "好友圈")
                        Text(state.syncMessage, style = MaterialTheme.typography.labelSmall)
                    }
                },
                actions = {
                    SyncIcon(state)
                    IconButton(onClick = { viewModel.syncNow() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "同步")
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        floatingActionButton = {
            if (state.tab == MainTab.IDEAS && state.selectedIdea == null) {
                ExtendedFloatingActionButton(
                    onClick = { creating = true },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    text = { Text("新建 Idea") },
                )
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.offline) OfflineBanner()
            val selectedIdea = state.selectedIdea
            if (selectedIdea != null) {
                IdeaDetailPage(
                    idea = selectedIdea,
                    comments = state.comments,
                    onDismiss = viewModel::closeIdeaDetail,
                    onLike = { viewModel.toggleLike(selectedIdea) },
                    onSend = viewModel::createComment,
                )
            } else {
                MainTabs(state, viewModel)
                if (state.tab == MainTab.IDEAS) {
                    IdeaListPanel(
                        state,
                        viewModel,
                        onEdit = { editorTask = it },
                        onOpen = viewModel::openLocalIdeaDetail,
                    )
                } else {
                    FeedPanel(state, viewModel)
                }
            }
        }
    }

    if (creating) {
        IdeaEditorDialog(
            task = null,
            onDismiss = { creating = false },
            onSave = { title, content, status, category, priority, visibility ->
                viewModel.createTask(title, content, status, category, priority, visibility)
                creating = false
            },
        )
    }
    editorTask?.let { task ->
        IdeaEditorDialog(
            task = task,
            onDismiss = { editorTask = null },
            onSave = { title, content, status, category, priority, visibility ->
                viewModel.updateTask(task, title, content, status, category, priority, visibility)
                editorTask = null
            },
        )
    }
    if (settingsOpen) {
        SettingsDialog(state, viewModel, onDismiss = { settingsOpen = false })
    }
}

@Composable
private fun MainTabs(state: TodoUiState, viewModel: TodoViewModel) {
    Row(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PrimaryTabChip(
            text = "我的 Idea",
            selected = state.tab == MainTab.IDEAS,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.setTab(MainTab.IDEAS) },
        )
        PrimaryTabChip(
            text = "好友圈",
            selected = state.tab == MainTab.FEED,
            modifier = Modifier.weight(1f),
            onClick = { viewModel.setTab(MainTab.FEED) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PrimaryTabChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(
                text,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.padding(vertical = 4.dp),
            )
        },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primary,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun SyncIcon(state: TodoUiState) {
    val icon = when {
        state.offline -> Icons.Default.CloudOff
        state.syncing -> Icons.Default.Refresh
        state.syncMessage.contains("待") -> Icons.Default.Warning
        else -> Icons.Default.CheckCircle
    }
    Icon(
        icon,
        contentDescription = "同步状态",
        tint = if (state.offline || state.syncMessage.contains("待")) MaterialTheme.colorScheme.error else Color(0xFF16A34A),
    )
}

@Composable
private fun OfflineBanner() {
    Surface(color = Color(0xFFFFF7ED), modifier = Modifier.fillMaxWidth()) {
        Text(
            "离线模式：个人 idea 已保存到本地，网络恢复后自动同步。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFF9A3412),
        )
    }
}

@Composable
private fun IdeaListPanel(
    state: TodoUiState,
    viewModel: TodoViewModel,
    onEdit: (TodoTask) -> Unit,
    onOpen: (TodoTask) -> Unit,
) {
    SearchAndFilters(state, viewModel)
    if (state.tasks.isEmpty()) {
        EmptyState("还没有 idea")
    } else {
        LazyColumn(
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(state.tasks, key = { it.id }) { task ->
                IdeaCard(
                    task,
                    onOpen = { onOpen(task) },
                    onEdit = { onEdit(task) },
                    onDelete = { viewModel.deleteTask(task) },
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun SearchAndFilters(state: TodoUiState, viewModel: TodoViewModel) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = state.search,
            onValueChange = viewModel::setSearch,
            label = { Text("搜索标题和内容") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
        )
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            FilterChip(
                selected = state.statusFilter == null,
                onClick = { viewModel.setStatusFilter(null) },
                label = { Text("全部状态") },
            )
            taskStatuses.forEach { value ->
                FilterChip(
                    selected = state.statusFilter == value,
                    onClick = { viewModel.setStatusFilter(value) },
                    label = { Text(value) },
                )
            }
        }
    }
}

@Composable
private fun EmptyState(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun IdeaCard(task: TodoTask, onOpen: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    val priorityColor = when (task.priority) {
        "高" -> Color(0xFFDC2626)
        "低" -> Color(0xFF64748B)
        else -> Color(0xFF2563EB)
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "编辑")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "删除")
                }
            }
            if (task.content.isNotBlank()) {
                Text(task.content, maxLines = 2, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(8.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                LabelChip(task.status)
                LabelChip(task.category)
                LabelChip(task.priority, priorityColor)
                LabelChip(if (task.visibility == VISIBILITY_CIRCLE) "好友圈公开" else "私密", if (task.visibility == VISIBILITY_CIRCLE) Color(0xFF16A34A) else Color(0xFF64748B))
                if (task.syncState == SyncState.PENDING) LabelChip("待同步", Color(0xFFCA8A04))
                if (task.syncState == SyncState.CONFLICT) LabelChip("冲突", MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Text("更新于 ${task.updatedAt}", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun FeedPanel(state: TodoUiState, viewModel: TodoViewModel) {
    var page by remember { mutableStateOf(SocialPage.FEED) }
    when (page) {
        SocialPage.FEED -> FeedHomePanel(
            state = state,
            viewModel = viewModel,
            onAddFriend = { page = SocialPage.ADD_FRIEND },
            onManageFriends = { page = SocialPage.FRIENDS },
        )
        SocialPage.ADD_FRIEND -> AddFriendPage(
            state = state,
            viewModel = viewModel,
            onBack = { page = SocialPage.FEED },
        )
        SocialPage.FRIENDS -> FriendManagementPage(
            state = state,
            viewModel = viewModel,
            onBack = { page = SocialPage.FEED },
        )
    }
}

@Composable
private fun FeedHomePanel(
    state: TodoUiState,
    viewModel: TodoViewModel,
    onAddFriend: () -> Unit,
    onManageFriends: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryActionChip("添加好友", Modifier.weight(1f), onAddFriend)
            SecondaryActionChip("好友管理", Modifier.weight(1f), onManageFriends)
        }
        state.socialError?.let {
            Text(
                it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        FeedFilters(state, viewModel)
        if (state.feed.isEmpty()) {
            EmptyState("添加好友后，就能刷到彼此公开的 idea")
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.feed, key = { it.task.id }) { item ->
                    FeedCard(
                        idea = item,
                        onOpen = { viewModel.openIdeaDetail(item) },
                        onLike = { viewModel.toggleLike(item) },
                        onComments = { viewModel.openIdeaDetail(item) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SecondaryActionChip(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    FilterChip(
        selected = false,
        onClick = onClick,
        label = { Text(text, modifier = Modifier.padding(vertical = 2.dp)) },
        modifier = modifier,
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

@Composable
private fun AddFriendPage(state: TodoUiState, viewModel: TodoViewModel, onBack: () -> Unit) {
    var joinId by remember { mutableStateOf("") }
    var introduction by remember { mutableStateOf("") }
    val context = LocalContext.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ChildPageHeader("添加好友", onBack)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    Text("我的好友 ID", style = MaterialTheme.typography.labelMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            state.circleId.ifBlank { "加载中" },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(
                            onClick = { copyCircleId(context, state.circleId) },
                            enabled = state.circleId.isNotBlank(),
                        ) {
                            Text("复制")
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp)) {
                    OutlinedTextField(
                        value = joinId,
                        onValueChange = { joinId = it.uppercase() },
                        label = { Text("输入好友 ID") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = introduction,
                        onValueChange = { introduction = it.take(500) },
                        label = { Text("介绍一下你是谁") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            viewModel.sendFriendRequest(joinId, introduction)
                            joinId = ""
                            introduction = ""
                        },
                        enabled = joinId.isNotBlank() && introduction.isNotBlank(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("发送好友申请")
                    }
                    state.socialError?.let {
                        Spacer(Modifier.height(6.dp))
                        Text(it, color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
        item {
            FriendRequestsSection(state, viewModel)
        }
    }
}

@Composable
private fun FriendManagementPage(state: TodoUiState, viewModel: TodoViewModel, onBack: () -> Unit) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            ChildPageHeader("好友管理", onBack)
        }
        if (state.friends.isEmpty()) {
            item {
                Text("还没有好友", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            items(state.friends, key = { it.circleId }) { friend ->
                FriendRow(
                    friend = friend,
                    onOpen = {
                        viewModel.selectFeedCircle(friend.circleId)
                        onBack()
                    },
                    onRemove = { viewModel.removeFriend(friend.circleId) },
                )
            }
        }
        state.socialError?.let { error ->
            item {
                Text(error, color = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Composable
private fun ChildPageHeader(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = onBack) {
            Text("返回")
        }
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun FriendRequestsSection(state: TodoUiState, viewModel: TodoViewModel) {
    val pendingIncoming = state.incomingFriendRequests.filter { it.status == "pending" }
    val recentOutgoing = state.outgoingFriendRequests.take(3)
    if (pendingIncoming.isEmpty() && recentOutgoing.isEmpty()) return

    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
        if (pendingIncoming.isNotEmpty()) {
            Text("收到的好友申请", style = MaterialTheme.typography.labelMedium)
            pendingIncoming.forEach { request ->
                FriendRequestRow(
                    request = request,
                    incoming = true,
                    onAccept = { viewModel.acceptFriendRequest(request.id) },
                    onReject = { viewModel.rejectFriendRequest(request.id) },
                )
            }
            Spacer(Modifier.height(6.dp))
        }
        if (recentOutgoing.isNotEmpty()) {
            Text("发出的申请", style = MaterialTheme.typography.labelMedium)
            recentOutgoing.forEach { request ->
                FriendRequestRow(request = request, incoming = false, onAccept = {}, onReject = {})
            }
        }
    }
}

@Composable
private fun FriendRequestRow(
    request: FriendRequest,
    incoming: Boolean,
    onAccept: () -> Unit,
    onReject: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        val user = if (incoming) request.requester else request.target
        Text("${user.username} (${request.status})", fontWeight = FontWeight.SemiBold)
        Text(request.introduction, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (incoming) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onReject) { Text("拒绝") }
                Button(onClick = onAccept) { Text("通过") }
            }
        }
    }
}

@Composable
private fun FriendRow(friend: CircleInfo, onOpen: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(friend.owner.username, fontWeight = FontWeight.SemiBold)
            Text(friend.circleId, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        OutlinedButton(onClick = onRemove) {
            Text("删除")
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun FeedFilters(state: TodoUiState, viewModel: TodoViewModel) {
    FlowRow(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        FilterChip(
            selected = state.selectedFeedCircleId == null,
            onClick = { viewModel.selectFeedCircle(null) },
            label = { Text("全部好友圈") },
        )
        if (state.circleId.isNotBlank()) {
            FilterChip(
                selected = state.selectedFeedCircleId == state.circleId,
                onClick = { viewModel.selectFeedCircle(state.circleId) },
                label = { Text("我的公开") },
            )
        }
        state.friends.forEach { circle ->
            FilterChip(
                selected = state.selectedFeedCircleId == circle.circleId,
                onClick = { viewModel.selectFeedCircle(circle.circleId) },
                label = { Text(circle.owner.username) },
            )
        }
    }
}

@Composable
private fun FeedCard(idea: FeedIdea, onOpen: () -> Unit, onLike: () -> Unit, onComments: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("${idea.author.username} 的 idea", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            Text(idea.task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (idea.task.content.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(idea.task.content, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LabelChip(idea.task.category)
                LabelChip(idea.task.status)
                LabelChip(idea.task.priority)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onLike) {
                    Text(if (idea.likedByMe) "已赞 ${idea.likeCount}" else "点赞 ${idea.likeCount}")
                }
                OutlinedButton(onClick = onComments) {
                    Text("评论 ${idea.commentCount}")
                }
            }
        }
    }
}

@Composable
private fun LabelChip(text: String, color: Color = MaterialTheme.colorScheme.primary) {
    Surface(color = color.copy(alpha = 0.12f), shape = MaterialTheme.shapes.small) {
        Text(text, color = color, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeaEditorDialog(
    task: TodoTask?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String, String) -> Unit,
) {
    var title by remember(task?.id) { mutableStateOf(task?.title ?: "") }
    var content by remember(task?.id) { mutableStateOf(task?.content ?: "") }
    var status by remember(task?.id) { mutableStateOf(task?.status ?: STATUS_TODO) }
    var category by remember(task?.id) { mutableStateOf(task?.category ?: CATEGORY_OTHER) }
    var priority by remember(task?.id) { mutableStateOf(task?.priority ?: PRIORITY_MEDIUM) }
    var visibility by remember(task?.id) { mutableStateOf(task?.visibility ?: VISIBILITY_PRIVATE) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(title, content, status, category, priority, visibility) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(if (task == null) "新建 Idea" else "编辑 Idea") },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(content, { content = it.take(5000) }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Spacer(Modifier.height(10.dp))
                OptionRow("可见性", listOf("私密" to VISIBILITY_PRIVATE, "好友圈公开" to VISIBILITY_CIRCLE), visibility) { visibility = it }
                OptionRow("状态", taskStatuses.map { it to it }, status) { status = it }
                OptionRow("分类", taskCategories.map { it to it }, category) { category = it }
                OptionRow("优先级", taskPriorities.map { it to it }, priority) { priority = it }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OptionRow(title: String, values: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEach { (label, value) ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun IdeaDetailPage(
    idea: FeedIdea,
    comments: List<IdeaComment>,
    onDismiss: () -> Unit,
    onLike: () -> Unit,
    onSend: (String) -> Unit,
) {
    var content by remember(idea.task.id) { mutableStateOf("") }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Text(
                idea.task.title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "关闭详情")
            }
        }
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LabelChip(idea.author.username)
            Text(idea.task.updatedAt, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(10.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text(
                    idea.task.content.ifBlank { "没有详细内容" },
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    minLines = 6,
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    LabelChip(idea.task.category)
                    LabelChip(idea.task.status)
                    LabelChip(idea.task.priority)
                    Spacer(Modifier.weight(1f))
                    OutlinedButton(onClick = onLike) {
                        Text(if (idea.likedByMe) "已赞 ${idea.likeCount}" else "点赞 ${idea.likeCount}")
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text("评论 ${idea.commentCount}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (comments.isEmpty()) {
                item {
                    Text("还没有评论", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(comments, key = { it.id }) { comment ->
                    CommentRow(comment)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(1000) },
                label = { Text("发表你的想法") },
                modifier = Modifier.weight(1f),
                minLines = 1,
                maxLines = 3,
            )
            Button(
                onClick = {
                    onSend(content)
                    content = ""
                },
                enabled = content.isNotBlank(),
            ) {
                Text("发送")
            }
        }
    }
}

@Composable
private fun CommentRow(comment: IdeaComment) {
    Column(Modifier.fillMaxWidth()) {
        Text(comment.author.username, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
        Text(comment.content, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SettingsDialog(state: TodoUiState, viewModel: TodoViewModel, onDismiss: () -> Unit) {
    var reminderEnabled by remember(state.reminder) { mutableStateOf(state.reminder.enabled) }
    var frequency by remember(state.reminder) { mutableStateOf(state.reminder.frequencySeconds.toString()) }
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }
    val context = LocalContext.current

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(
                onClick = {
                    viewModel.saveBaseUrl(baseUrl)
                    viewModel.saveReminder(reminderEnabled, frequency.toIntOrNull() ?: 3600)
                    onDismiss()
                },
            ) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                OutlinedButton(onClick = { viewModel.logout(); onDismiss() }) { Text("登出") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
        title = { Text("设置") },
        text = {
            Column {
                Text("账号：${state.username}")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "好友圈 ID：${state.circleId.ifBlank { "加载中" }}",
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(
                        onClick = { copyCircleId(context, state.circleId) },
                        enabled = state.circleId.isNotBlank(),
                    ) {
                        Text("复制")
                    }
                }
                Text("同步：${state.syncMessage}")
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(baseUrl, { baseUrl = it }, label = { Text("后端地址") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("开启提醒", modifier = Modifier.weight(1f))
                    Switch(checked = reminderEnabled, onCheckedChange = { reminderEnabled = it })
                }
                OutlinedTextField(
                    frequency,
                    { frequency = it.filter(Char::isDigit) },
                    label = { Text("提醒频率（秒，300-86400）") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(12.dp))
                Button(onClick = { viewModel.syncNow() }, enabled = !state.syncing) {
                    Text("立即同步")
                }
            }
        },
    )
}

private fun copyCircleId(context: Context, circleId: String) {
    if (circleId.isBlank()) return
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Idea Board 好友圈 ID", circleId))
    Toast.makeText(context, "已复制好友圈 ID", Toast.LENGTH_SHORT).show()
}
