# 待更新：AI 拆解与语音输入功能

## 目标

下一版为 Todo Offline App 增加两个辅助能力：

1. AI 自动拆解 idea
   用户输入一个较大的 idea 后，可以让大模型拆成多个更具体、可执行的小 idea。

2. 语音转文字记录
   用户可以直接说出想法，由 Android 系统语音识别填入 idea 内容输入框。

## 初步方案

### AI 拆解

- Android 端提供“AI 拆解”按钮。
- 用户主动点击后，客户端把当前 idea 的标题、内容、分类发送给后端。
- 后端读取服务器 `.env` 中的大模型配置，调用云端模型。
- 模型返回多个候选 idea。
- Android 展示候选列表，用户确认后才批量创建为本地 idea。
- 生成的 idea 默认设为私密，避免误公开到好友圈。

### 语音输入

- Android 端使用系统内置语音识别。
- 在 idea 编辑器中增加麦克风按钮。
- 用户授权录音权限后，可以语音输入。
- 识别结果填入或追加到内容输入框，用户可编辑后保存。

## 配置原则

- 大模型 API key 只保存在服务器 `.env`。
- 不把 API key 写入 Android。
- 不把真实 API key 提交到 GitHub。
- `.env.example` 只写变量名和示例占位符。

## 计划新增配置

```env
AI_API_BASE_URL=
AI_API_KEY=
AI_MODEL=
AI_TIMEOUT_SECONDS=30
```

## 计划新增接口

```http
POST /api/ai/breakdown
```

请求需要登录。

响应返回候选 idea 列表，不直接写数据库。

## 明天实现顺序

1. 后端增加 AI 配置读取和模型调用封装。
2. 后端增加 `/api/ai/breakdown` 接口。
3. 增加后端测试，使用模拟响应，不真实调用模型。
4. Android 增加 AI 拆解按钮和候选 idea 预览。
5. Android 增加语音输入按钮和录音权限。
6. 更新 README、API 文档和中文 CHANGELOG。
7. 构建 debug APK 验证。
