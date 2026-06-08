package com.example.todooffline

import android.Manifest
import android.os.Build
import android.os.Bundle
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
import com.example.todooffline.data.PRIORITY_MEDIUM
import com.example.todooffline.data.STATUS_TODO
import com.example.todooffline.data.SyncState
import com.example.todooffline.data.TodoTask
import com.example.todooffline.data.taskCategories
import com.example.todooffline.data.taskPriorities
import com.example.todooffline.data.taskStatuses
import com.example.todooffline.reminder.ensureChannel
import com.example.todooffline.ui.TodoUiState
import com.example.todooffline.ui.TodoViewModel
import com.example.todooffline.ui.TodoViewModelFactory

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
        TaskScreen(state, viewModel)
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
        Text("离线 ToDo", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("服务端不在线时，也可以继续使用本地任务。", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
private fun TaskScreen(state: TodoUiState, viewModel: TodoViewModel) {
    var editorTask by remember { mutableStateOf<TodoTask?>(null) }
    var creating by remember { mutableStateOf(false) }
    var settingsOpen by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("离线 ToDo")
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
            ExtendedFloatingActionButton(
                onClick = { creating = true },
                icon = { Icon(Icons.Default.Add, contentDescription = null) },
                text = { Text("新建") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            if (state.offline) OfflineBanner()
            SearchAndFilters(state, viewModel)
            if (state.tasks.isEmpty()) {
                EmptyState()
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.tasks, key = { it.id }) { task ->
                        TaskCard(task, onEdit = { editorTask = task }, onDelete = { viewModel.deleteTask(task) })
                    }
                }
            }
        }
    }

    if (creating) {
        TaskEditorDialog(
            task = null,
            onDismiss = { creating = false },
            onSave = { title, content, status, category, priority ->
                viewModel.createTask(title, content, status, category, priority)
                creating = false
            },
        )
    }
    editorTask?.let { task ->
        TaskEditorDialog(
            task = task,
            onDismiss = { editorTask = null },
            onSave = { title, content, status, category, priority ->
                viewModel.updateTask(task, title, content, status, category, priority)
                editorTask = null
            },
        )
    }
    if (settingsOpen) {
        SettingsDialog(state, viewModel, onDismiss = { settingsOpen = false })
    }
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
            "离线模式：读写都已保存到本地，网络恢复后自动同步。",
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            color = Color(0xFF9A3412),
        )
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
private fun EmptyState() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("还没有任务", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TaskCard(task: TodoTask, onEdit: () -> Unit, onDelete: () -> Unit) {
    val priorityColor = when (task.priority) {
        "高" -> Color(0xFFDC2626)
        "低" -> Color(0xFF64748B)
        else -> Color(0xFF2563EB)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
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
                if (task.syncState == SyncState.PENDING) LabelChip("待同步", Color(0xFFCA8A04))
                if (task.syncState == SyncState.CONFLICT) LabelChip("冲突", MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(6.dp))
            Text("更新于 ${task.updatedAt}", style = MaterialTheme.typography.labelSmall)
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
private fun TaskEditorDialog(
    task: TodoTask?,
    onDismiss: () -> Unit,
    onSave: (String, String, String, String, String) -> Unit,
) {
    var title by remember(task?.id) { mutableStateOf(task?.title ?: "") }
    var content by remember(task?.id) { mutableStateOf(task?.content ?: "") }
    var status by remember(task?.id) { mutableStateOf(task?.status ?: STATUS_TODO) }
    var category by remember(task?.id) { mutableStateOf(task?.category ?: CATEGORY_OTHER) }
    var priority by remember(task?.id) { mutableStateOf(task?.priority ?: PRIORITY_MEDIUM) }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            Button(onClick = { onSave(title, content, status, category, priority) }) {
                Text("保存")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        },
        title = { Text(if (task == null) "新建任务" else "编辑任务") },
        text = {
            Column {
                OutlinedTextField(title, { title = it.take(200) }, label = { Text("标题") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(content, { content = it.take(5000) }, label = { Text("内容") }, modifier = Modifier.fillMaxWidth(), minLines = 3)
                Spacer(Modifier.height(10.dp))
                OptionRow("状态", taskStatuses, status) { status = it }
                OptionRow("分类", taskCategories, category) { category = it }
                OptionRow("优先级", taskPriorities, priority) { priority = it }
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun OptionRow(title: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    Text(title, style = MaterialTheme.typography.labelMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        values.forEach { value ->
            FilterChip(selected = selected == value, onClick = { onSelect(value) }, label = { Text(value) })
        }
    }
    Spacer(Modifier.height(6.dp))
}

@Composable
private fun SettingsDialog(state: TodoUiState, viewModel: TodoViewModel, onDismiss: () -> Unit) {
    var reminderEnabled by remember(state.reminder) { mutableStateOf(state.reminder.enabled) }
    var frequency by remember(state.reminder) { mutableStateOf(state.reminder.frequencySeconds.toString()) }
    var baseUrl by remember(state.baseUrl) { mutableStateOf(state.baseUrl) }

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
