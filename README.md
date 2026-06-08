# 离线优先 ToDo App

这是按 [docs/requirements.md](docs/requirements.md) 实现的个人任务管理项目，包含：

- `backend/`：Python 标准库实现的 REST JSON API，SQLite 存储，无第三方依赖。
- `android/`：Kotlin + Jetpack Compose + SQLiteOpenHelper + Retrofit + WorkManager + AlarmManager 安卓客户端。
- `docs/API.md`：接口请求/响应说明。

## 后端启动

```bash
cd backend
python3 -m todo_backend --host 0.0.0.0 --port 8000 --db data/todo.sqlite3
```

健康检查：

```bash
curl http://127.0.0.1:8000/api/health
```

运行后端接口测试：

```bash
cd backend
python3 -m unittest discover -s tests
```

## Docker

项目根目录提供了 `docker-compose.yml`，推荐服务器部署时使用它。

```bash
cp .env.example .env
docker compose up -d --build
```

查看日志：

```bash
docker compose logs -f todo-backend
```

停止服务：

```bash
docker compose down
```

### 服务器 Git 部署

首次部署：

```bash
git clone <你的仓库地址> todo-offline-app
cd todo-offline-app
cp .env.example .env
docker compose up -d --build
curl http://127.0.0.1:8000/api/health
```

以后更新：

```bash
cd todo-offline-app
git pull
docker compose up -d --build
```

SQLite 数据保存在 Docker volume `todo_backend_data` 中，更新镜像不会删除数据。备份数据库：

```bash
mkdir -p backups
docker run --rm \
  -v todo-offline-app_todo_backend_data:/data \
  -v "$PWD/backups:/backup" \
  busybox cp /data/todo.sqlite3 /backup/todo.sqlite3
```

## 构建安卓 APK

本机已生成 Gradle wrapper：

```bash
cd android
./gradlew --offline :app:assembleDebug
```

APK 输出路径：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

安卓客户端默认后端地址是：

```text
http://10.0.2.2:8000/
```

这个地址适用于 Android 模拟器访问宿主机。真机安装时，在登录页或设置页把后端地址改成电脑的局域网地址，例如：

```text
http://192.168.1.10:8000/
```

当前 debug APK 已允许访问 HTTP 明文后端地址，例如：

```text
http://服务器公网IP:8000/
```

正式长期使用建议给服务器配置域名和 HTTPS，然后在 App 里填写 `https://你的域名/`。

如果换机器开发，需要按本机路径调整：

- `android/local.properties` 里的 `sdk.dir`
- 如果 Gradle 找不到完整 JDK，在命令前设置 `JAVA_HOME`，或在本机 `~/.gradle/gradle.properties` 配置 `org.gradle.java.home`

## 已实现功能

- 用户注册、登录、登出、Bearer token 鉴权。
- 任务创建、列表、搜索、筛选、编辑、软删除、随机任务。
- SQLite 本地完整副本，读操作只读本地。
- 本地写入后追加同步队列，网络可用时自动 push，再 pull 增量。
- 服务端 `operationId` 幂等处理。
- 版本冲突返回 409，安卓端标记冲突任务。
- `syncCursor` 增量同步，软删除墓碑不会被拉回。
- 提醒设置本地生效，AlarmManager 本地通知。
- WorkManager 周期同步和网络约束同步。

## 重要文件

- 后端入口：[backend/todo_backend/server.py](backend/todo_backend/server.py)
- 后端数据库逻辑：[backend/todo_backend/database.py](backend/todo_backend/database.py)
- 安卓主界面：[android/app/src/main/java/com/example/todooffline/MainActivity.kt](android/app/src/main/java/com/example/todooffline/MainActivity.kt)
- 安卓本地库：[android/app/src/main/java/com/example/todooffline/data/local/TodoLocalStore.kt](android/app/src/main/java/com/example/todooffline/data/local/TodoLocalStore.kt)
- 安卓同步仓库：[android/app/src/main/java/com/example/todooffline/data/repo/TodoRepository.kt](android/app/src/main/java/com/example/todooffline/data/repo/TodoRepository.kt)
