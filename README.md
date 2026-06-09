# Idea Board

离线优先的个人 idea 管理应用，包含安卓客户端和自部署后端。

## 需求

- 安卓端在后端离线或网络不可用时仍可创建、编辑、删除 idea。
- 本地数据恢复联网后自动同步到后端。
- 用户可以把 idea 设为私密或好友可见。
- 好友关系需要申请和审核，不能直接添加。
- 后端可用 Docker Compose 部署到服务器。

## 功能

- 用户注册、登录、登出。
- idea 创建、编辑、删除、搜索、状态筛选。
- idea 字段：标题、内容、状态、分类、优先级、可见性。
- 离线写入和后台同步队列。
- 本地提醒设置。
- 唯一好友 ID，可复制。
- 好友申请：输入好友 ID 和自我介绍，等待对方审核。
- 好友管理：查看好友、删除好友、查看好友公开 idea。
- 好友圈 feed：查看自己和好友公开 idea。
- idea 详情页：完整内容、点赞、评论。

## 后端部署

服务器需要安装 Git、Docker 和 Docker Compose 插件。

```bash
apt update
apt install -y git docker.io docker-compose-plugin
systemctl enable --now docker
```

拉取项目：

```bash
cd /opt
git clone https://github.com/fox-114514/todo-offline-app.git
cd todo-offline-app
```

创建环境配置：

```bash
cp .env.example .env
```

默认公开端口是 `8000`。如需修改，编辑 `.env`：

```text
TODO_BACKEND_PUBLIC_PORT=8000
```

启动后端：

```bash
docker compose up -d --build
```

检查服务：

```bash
docker compose ps
curl http://127.0.0.1:8000/api/health
```

公网访问地址示例：

```text
http://服务器公网IP:8000/
```

安卓登录页或设置页的后端地址填写该地址，末尾保留 `/`。

更新后端：

```bash
cd /opt/todo-offline-app
git pull
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
