# Idea Board

这是按 [docs/requirements.md](docs/requirements.md) 实现的个人任务管理项目，包含：

- `backend/`：Python 标准库实现的 REST JSON API，SQLite 存储，无第三方依赖。
- `android/`：Kotlin + Jetpack Compose + SQLiteOpenHelper + Retrofit + WorkManager + AlarmManager 安卓客户端。
- `docs/API.md`：接口请求/响应说明。

核心体验：

- 个人 idea 离线优先，没网也能创建、编辑、删除。
- 每个用户自动拥有一个唯一好友圈 ID。
- 其他用户可以通过好友圈 ID 发送好友申请，并附上自我介绍。
- 好友申请通过后双方互加好友，可以互相查看公开 idea。
- idea 可以设为私密，也可以公开到自己的好友圈。
- 好友圈可以刷自己和好友的公开 idea、点赞、评论、查看详情。

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

## 后端服务器部署教程

推荐在服务器上使用 Docker Compose 部署后端。下面以 Ubuntu/Debian 服务器为例。

### 1. 登录服务器

```bash
ssh root@你的服务器公网IP
```

### 2. 安装 Docker 和 Git

```bash
apt update
apt install -y git docker.io docker-compose-plugin
systemctl enable --now docker
```

确认 Docker 可用：

```bash
docker --version
docker compose version
```

### 3. 拉取项目代码

```bash
cd /opt
git clone https://github.com/fox-114514/todo-offline-app.git
cd todo-offline-app
```

### 4. 配置环境变量

复制示例配置：

```bash
cp .env.example .env
```

默认后端对外端口是 `8000`。如果要改端口，编辑 `.env`：

```bash
nano .env
```

示例：

```text
TODO_BACKEND_PUBLIC_PORT=8000
```

### 5. 启动后端

```bash
docker compose up -d --build
```

查看容器状态：

```bash
docker compose ps
```

正常会看到 `todo-backend` 状态为 `Up`。

查看日志：

```bash
docker compose logs -f todo-backend
```

按 `Ctrl+C` 可以退出日志查看，不会停止服务。

### 6. 测试服务

在服务器本机测试：

```bash
curl http://127.0.0.1:8000/api/health
```

正常响应：

```json
{"code":200,"message":"success","data":{"ok":true}}
```

在你的电脑或手机网络测试：

```bash
curl http://你的服务器公网IP:8000/api/health
```

如果服务器本机可以访问，但公网访问失败，通常是防火墙或云服务商安全组没有开放 `8000` 端口。

Ubuntu 防火墙开放端口：

```bash
ufw allow 8000/tcp
```

还需要到云服务器控制台确认安全组/防火墙规则允许 TCP `8000` 入站。

### 7. 安卓客户端填写后端地址

真机安装 APK 后，登录页或设置页的后端地址填写：

```text
http://你的服务器公网IP:8000/
```

注意末尾 `/` 要保留。

当前 debug APK 已允许访问 HTTP 明文后端地址。正式长期使用建议给服务器配置域名和 HTTPS，然后在 App 里填写：

```text
https://你的域名/
```

### 8. 更新后端

本地代码推送到 GitHub 后，在服务器上执行：

```bash
cd /opt/todo-offline-app
git pull
docker compose up -d --build
```

### 9. 停止和重启

```bash
docker compose down
```

重新启动：

```bash
docker compose up -d
```

### 10. 数据位置和备份

SQLite 数据保存在 Docker volume `todo_backend_data` 中，更新镜像不会删除数据。

备份数据库：

```bash
cd /opt/todo-offline-app
mkdir -p backups
docker run --rm \
  -v todo-offline-app_todo_backend_data:/data \
  -v "$PWD/backups:/backup" \
  busybox cp /data/todo.sqlite3 /backup/todo.sqlite3
```

恢复数据库前建议先停止服务：

```bash
docker compose down
docker run --rm \
  -v todo-offline-app_todo_backend_data:/data \
  -v "$PWD/backups:/backup" \
  busybox cp /backup/todo.sqlite3 /data/todo.sqlite3
docker compose up -d
```

### 11. 常见排错

查看后端日志：

```bash
docker compose logs -f todo-backend
```

查看端口监听：

```bash
docker compose ps
ss -lntp | grep 8000
```

重新构建：

```bash
docker compose up -d --build
```

如果 App 提示 `CLEARTEXT communication not permitted`，说明安装的 APK 不是当前新版 debug 包，请重新安装最新的 `app-debug.apk`。

如果 App 提示连接超时，优先检查：

- 后端地址是否写成 `http://服务器公网IP:8000/`
- 服务器 `curl http://127.0.0.1:8000/api/health` 是否成功
- 云服务商安全组是否开放 `8000`
- 服务器防火墙是否开放 `8000/tcp`

### 12. 手动 Docker 命令

如果不用 Compose，也可以只在 `backend/` 目录手动运行：

```bash
cd backend
docker build -t todo-backend .
docker run --rm -p 8000:8000 -v "$PWD/data:/data" todo-backend
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

当前版本：

- `versionCode = 2`
- `versionName = 0.2.0`

覆盖安装新版 APK 时应保持同一个应用签名；本项目 debug APK 继续使用 Gradle 默认 debug 签名，通过提升版本号支持覆盖安装。

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
- idea 创建、列表、搜索、筛选、编辑、软删除、随机抽取。
- idea 可见性：私密 / 好友圈公开。
- 每个用户自动生成唯一好友圈 ID。
- 支持通过好友圈 ID 加入/退出别人的好友圈。
- 好友圈 feed：刷已加入好友圈的公开 idea。
- 点赞和取消点赞。
- 一级评论。
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
