# API 文档

基础地址示例：

```text
http://127.0.0.1:8000
```

统一响应格式：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

认证方式：

```http
Authorization: Bearer <token>
```

token 有效期为 1 小时。除注册、登录、健康检查外，其他接口都需要认证。

## 健康检查

### GET /api/health

响应：

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "ok": true
  }
}
```

## 用户

### POST /api/auth/register

请求：

```json
{
  "username": "alice",
  "email": "alice@example.com",
  "password": "secret123"
}
```

响应：

```json
{
  "user": {
    "id": "uuid",
    "username": "alice",
    "email": "alice@example.com",
    "circleId": "ID-ABCDEFGH",
    "createdAt": "2026-06-08T12:00:00Z",
    "updatedAt": "2026-06-08T12:00:00Z"
  },
  "token": "token",
  "expiresAt": "2026-06-08T13:00:00Z"
}
```

### POST /api/auth/login

请求：

```json
{
  "username": "alice",
  "password": "secret123"
}
```

响应同注册。

### POST /api/auth/logout

响应：

```json
{
  "loggedOut": true
}
```

## Idea

后端仍兼容旧路径 `/api/tasks`，新客户端优先使用 `/api/ideas`。两组路径返回同一类数据。

idea 字段：

```json
{
  "id": "uuid",
  "title": "做一个好友圈 feed",
  "content": "朋友可以点赞和评论",
  "status": "进行中",
  "category": "程序",
  "priority": "高",
  "visibility": "circle",
  "createdAt": "2026-06-08T12:00:00Z",
  "updatedAt": "2026-06-08T12:00:00Z",
  "version": 1,
  "deletedAt": null
}
```

可选枚举：

- `status`：`想做`、`进行中`、`完成`、`放弃`
- `category`：`游戏`、`程序`、`技能`、`其他`
- `priority`：`高`、`中`、`低`
- `visibility`：`private` 私密、`circle` 公开到自己的好友圈

### GET /api/ideas

查询参数：

- `page`：默认 `1`
- `pageSize`：默认 `20`，最大 `100`
- `status`
- `category`
- `priority`
- `search`
- `sortBy`：`createdAt`、`updatedAt`、`title`、`status`、`category`、`priority`
- `sortOrder`：`asc` 或 `desc`

响应：

```json
{
  "items": [],
  "page": 1,
  "pageSize": 20,
  "total": 0
}
```

### GET /api/ideas/random

随机返回一个未完成、未放弃、未删除的 idea。

响应：

```json
{
  "task": null
}
```

### GET /api/ideas/{id}

响应为 idea 详情。本人可以查看自己的所有未删除 idea；好友可以查看彼此 `circle` 公开 idea。

```json
{
  "id": "idea_uuid",
  "title": "做一个好友圈 feed",
  "content": "朋友可以点赞和评论",
  "status": "进行中",
  "category": "程序",
  "priority": "高",
  "visibility": "circle",
  "createdAt": "2026-06-08T12:00:00Z",
  "updatedAt": "2026-06-08T12:00:00Z",
  "version": 1,
  "deletedAt": null,
  "author": {
    "id": "user_uuid",
    "username": "alice",
    "circleId": "ID-ABCDEFGH"
  },
  "likeCount": 1,
  "commentCount": 2,
  "likedByMe": true
}
```

### POST /api/ideas

请求：

```json
{
  "id": "uuid",
  "title": "做一个好友圈 feed",
  "content": "朋友可以点赞和评论",
  "status": "进行中",
  "category": "程序",
  "priority": "高",
  "visibility": "circle"
}
```

`id` 可由客户端生成；不传时服务端生成。

### PUT /api/ideas/{id}

请求必须带当前 `version`：

```json
{
  "version": 1,
  "title": "写后端和测试",
  "status": "完成"
}
```

版本不匹配时返回 HTTP 409：

```json
{
  "code": 409,
  "message": "task version conflict",
  "data": {
    "serverTask": {}
  }
}
```

### DELETE /api/ideas/{id}

请求可带版本号：

```json
{
  "version": 2
}
```

服务端执行软删除，返回带 `deletedAt` 的任务对象。

## 好友

每个用户注册时会自动获得一个唯一 `circleId`，也就是好友 ID。别人通过这个 ID 发送好友申请，申请通过后双方互加好友，可以互相看到 `circle` 公开 idea。

### GET /api/me/circle

响应：

```json
{
  "circleId": "ID-ABCDEFGH",
  "owner": {
    "id": "user_uuid",
    "username": "alice",
    "circleId": "ID-ABCDEFGH"
  },
  "memberCount": 3
}
```

### POST /api/friends/requests

发送好友申请。`introduction` 用来介绍自己是谁。

请求：

```json
{
  "circleId": "ID-ABCDEFGH",
  "introduction": "我是 bob，想互相看看公开 idea"
}
```

响应：

```json
{
  "id": "request_uuid",
  "requester": {
    "id": "bob_uuid",
    "username": "bob",
    "circleId": "ID-HJKLMNPQ"
  },
  "target": {
    "id": "alice_uuid",
    "username": "alice",
    "circleId": "ID-ABCDEFGH"
  },
  "introduction": "我是 bob，想互相看看公开 idea",
  "status": "pending",
  "createdAt": "2026-06-08T12:00:00Z",
  "decidedAt": null
}
```

### GET /api/friends/requests/incoming

查看收到的好友申请。

响应：

```json
{
  "items": []
}
```

### GET /api/friends/requests/outgoing

查看自己发出的好友申请。响应格式同上。

### POST /api/friends/requests/{id}/accept

通过好友申请。通过后双方互加好友。

响应为更新后的好友申请对象，`status` 为 `accepted`。

### POST /api/friends/requests/{id}/reject

拒绝好友申请。响应为更新后的好友申请对象，`status` 为 `rejected`。

### GET /api/friends

查看好友列表。

响应：

```json
{
  "items": [
    {
      "circleId": "ID-ABCDEFGH",
      "owner": {
        "id": "user_uuid",
        "username": "alice",
        "circleId": "ID-ABCDEFGH"
      },
      "joinedAt": "2026-06-08T12:00:00Z"
    }
  ]
}
```

### DELETE /api/friends/{circleId}

删除好友。删除后双方互相不可再看到对方公开 idea。

响应：

```json
{
  "removed": true,
  "circleId": "ID-ABCDEFGH",
  "removedAt": "2026-06-08T12:00:00Z"
}
```

### GET /api/friends/{circleId}/ideas

查看某个好友的公开 idea。响应格式同 `/api/feed`。

### 兼容旧接口

旧接口 `/api/circles/joined` 会返回好友列表。旧接口 `/api/circles/{circleId}/leave` 会删除双方好友关系。旧接口 `/api/circles/join` 不再直接加好友，只作为兼容入口创建一条默认介绍的好友申请。

## Feed

### GET /api/feed

查询参数：

- `circleId`：可选，只看某个好友或自己的公开 idea
- `page`：默认 `1`
- `pageSize`：默认 `20`

响应：

```json
{
  "items": [
    {
      "id": "idea_uuid",
      "title": "做一个好友圈 feed",
      "content": "朋友可以点赞和评论",
      "status": "进行中",
      "category": "程序",
      "priority": "高",
      "visibility": "circle",
      "createdAt": "2026-06-08T12:00:00Z",
      "updatedAt": "2026-06-08T12:00:00Z",
      "version": 1,
      "deletedAt": null,
      "author": {
        "id": "user_uuid",
        "username": "alice",
        "circleId": "ID-ABCDEFGH"
      },
      "likeCount": 1,
      "commentCount": 2,
      "likedByMe": true
    }
  ],
  "page": 1,
  "pageSize": 20,
  "total": 1
}
```

默认 feed 会返回自己的公开 idea 和好友的公开 idea。私密 idea 不会出现在 feed。

## 点赞和评论

### POST /api/ideas/{id}/like

点赞。重复点赞保持幂等。

响应：

```json
{
  "ideaId": "idea_uuid",
  "likeCount": 1,
  "commentCount": 0,
  "likedByMe": true
}
```

### DELETE /api/ideas/{id}/like

取消点赞。响应格式同上。

### GET /api/ideas/{id}/comments

响应：

```json
{
  "items": [
    {
      "id": "comment_uuid",
      "ideaId": "idea_uuid",
      "author": {
        "id": "user_uuid",
        "username": "bob",
        "circleId": "ID-HJKLMNPQ"
      },
      "content": "这个点子可以先做 MVP",
      "createdAt": "2026-06-08T12:00:00Z",
      "deletedAt": null
    }
  ]
}
```

### POST /api/ideas/{id}/comments

请求：

```json
{
  "content": "这个点子可以先做 MVP"
}
```

### DELETE /api/comments/{id}

只能删除自己的评论。

## 提醒

### GET /api/reminders

响应：

```json
{
  "enabled": false,
  "frequencySeconds": 3600,
  "updatedAt": "2026-06-08T12:00:00Z"
}
```

### PUT /api/reminders

请求：

```json
{
  "enabled": true,
  "frequencySeconds": 1800,
  "updatedAt": "2026-06-08T12:00:00Z"
}
```

`frequencySeconds` 范围：`300` 到 `86400`。

## 同步

### GET /api/sync/pull

用于首次同步和增量同步。

查询参数：

- `cursor`：上次同步成功后的游标；不传表示全量同步。

响应：

```json
{
  "tasks": [],
  "reminderSettings": {
    "enabled": false,
    "frequencySeconds": 3600,
    "updatedAt": "2026-06-08T12:00:00Z"
  },
  "nextCursor": "1",
  "isFullSync": true
}
```

增量同步会返回 `cursor` 之后新增、更新、软删除的记录。软删除记录通过 `deletedAt` 墓碑传递。

### POST /api/sync/push

用于推送客户端待同步队列。服务端按 `operationId` 做幂等处理。

请求：

```json
{
  "operations": [
    {
      "operationId": "uuid",
      "taskId": "uuid",
      "operationType": "create",
      "baseVersion": 0,
      "localUpdatedAt": "2026-06-08T12:00:00Z",
      "payload": {
        "title": "离线创建",
        "content": "",
        "status": "想做",
        "category": "其他",
        "priority": "中",
        "visibility": "private",
        "createdAt": "2026-06-08T12:00:00Z",
        "updatedAt": "2026-06-08T12:00:00Z",
        "version": 1,
        "deletedAt": null
      }
    }
  ]
}
```

`operationType` 可选：

- `create`
- `update`
- `delete`

响应：

```json
{
  "accepted": ["operation_uuid"],
  "conflicts": [
    {
      "operationId": "operation_uuid",
      "taskId": "task_uuid",
      "serverTask": {},
      "message": "task version conflict"
    }
  ],
  "nextCursor": "2"
}
```

客户端处理建议：

- `accepted`：删除本地队列中的对应操作。
- `conflicts`：标记本地任务冲突，展示服务端版本供用户选择。
- push 成功后再调用 pull，确保本地最终一致。
