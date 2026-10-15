# Sunset AI 对话与文档问答平台

基于 Spring AI 和 Vue 3 的个人学习项目，提供流式聊天、多模态输入、PDF 问答、课程咨询和预约工具调用。

## 工作台与用户权限

前端采用暖白与浅绿色的统一工作台，支持桌面侧栏、手机导航、明暗主题、聊天空状态、快捷提问、附件提示、停止生成和页内错误提示。聊天与客服共用组件，PDF 页面支持原文与问答分栏，窄屏可切换查看原文。

- 支持注册、登录、退出和登录状态恢复，密码使用 BCrypt 保存。登录态由服务端 Session 和 HttpOnly Cookie 管理，12 小时无活动后过期，后端重启后需要重新登录。
- 所有 AI 接口需要登录；会话、历史、PDF 下载、文档问答及任务查询/重试均校验账号归属，无权限时返回 404。会话 ID 不能代替权限凭证。
- Spring Security 保留 CSRF 校验，前端先获取 `/auth/csrf`，写请求携带相应请求头。登录会轮换 Session ID，退出会使会话失效。
- 历史匿名会话和 PDF 原文件会保留，默认不对新账号开放。需要将自己的旧数据迁移到指定账号时，先注册账号，再检查并手动执行 `docs/assign-legacy-owner.sql`；不会自动将旧数据分配给首个注册用户。

## 功能与工程细节

- 聊天消息与会话列表保存到 MySQL。模型只读取最近 20 条消息，完整历史保留在数据库中，支持游标分页。
- 聊天、PDF、客服、游戏使用不同的会话命名空间，避免客户端 ID 相同导致上下文串用。
- PDF 上传统一由文件服务解析、分块和向量化，每次成功上传只执行一次入库。
- 短 PDF 直接提供规范化后的全文，避免“这是什么”“帮我概括”等泛问法被相似度门槛过滤。长文档在当前文档内检索，未命中时提供明确标注的有限概览节选。
- 同一会话重复上传相同内容会复用文档，避免重复向量化；不同内容需要新建会话，前端自动处理。
- 原始文件名仅用于展示；文件使用随机 ID 保存，检索按文档 ID 过滤。同名 PDF 不会相互覆盖或混入检索结果。
- PDF 文件、文档映射和本地向量快照在上传成功时持久化，不再依赖正常关闭应用才保存。
- 校验文件内容、大小、空文本及会话 ID；文件处理失败时回收本次写入的向量和文件。
- 前端检查上传业务状态，显示服务端错误；修复中文流式解码，聊天请求改用 POST 请求体。
- 代码高亮使用常用语言集合，减小聊天页面资源体积；不支持的语言仍显示原始代码。
- 课程工具对排序字段进行白名单校验，预约前校验参数及课程、校区是否存在。

## 启动

后端需要 Java 17、Maven 3.9、MySQL，以及可用的模型和嵌入服务。前端使用 Node.js 22。

1. 创建 MySQL 数据库 `sunset-ai`，配置 `DASHSCOPE_API_KEY` 环境变量。将 `sunset-ai/src/main/resources/application-local.example.yml` 复制为同目录的 `application-local.yml`，填写本机数据库设置；也可使用 `DB_URL`、`DB_USERNAME`、`DB_PASSWORD` 环境变量。`application-local.yml` 已加入 Git 忽略列表。

   检查 `application.yml` 中的模型地址与模型名称，确保当前账号可访问对应聊天和嵌入模型。项目不会随仓库分发真实密钥、个人 PDF、聊天数据或向量快照。
2. 从 `sunset-ai` 目录启动。在 Windows 中优先使用脚本，它会重新读取用户或系统中的 `DASHSCOPE_API_KEY`，避免 IDE、终端仍使用更新前的旧值：

```powershell
.\start.ps1
```

也可使用 `mvn clean spring-boot:run`。修改 Windows 环境变量后，需要重新打开运行后端的终端或 IDE；单独重启 Java 程序可能仍会继承 IDE 中的旧值。脚本支持 `-Port 18080` 指定端口，不会把密钥写入命令行或日志。

3. 从 `spring-ai-protal` 目录启动前端：

```powershell
npm install
npm run dev
```

后端默认端口为 8080。开发环境默认通过 Vite 的 `/api` 代理连接 `http://localhost:8080`，便于同源 Cookie 登录。后端端口不同时，在启动前设置 `VITE_BACKEND_URL`。

需要直连 API 时，前端可通过 `.env.local` 配置地址：

```text
VITE_API_BASE_URL=http://localhost:8080
```

## 数据保存

首次启动默认创建聊天、账号、会话归属和 PDF 任务表：`ai_chat_session`、`ai_chat_message`、`ai_user`、`ai_conversation_owner`、`ai_pdf_task`，不修改现有课程相关表。SQL 位于 `sunset-ai/src/main/resources/db/chat-schema.sql`。

如果数据库账号没有建表权限，先由有权限的账号执行上述 SQL，再设置 `APP_CHAT_INITIALIZE_SCHEMA=false`。聊天媒体及工具调用也会序列化保存，重启后可恢复模型上下文。历史接口返回数据库中的消息时间，而不是页面加载时间。

默认运行目录为后端工作目录下的 `data`，可通过 `APP_STORAGE_DIRECTORY` 修改：

```text
data/
  pdf/          原始 PDF 使用随机文件名保存
  documents/    会话与文档映射
  vectors.json  SimpleVectorStore 向量快照
  tasks/        异步任务暂存文件，成功后自动清理
```

应用会读取旧版 `chat-pdf.properties`、`chat-pdf.json` 并复制对应 PDF 到新目录，保留旧文件。旧数据默认从后端工作目录读取，也可以设置 `app.storage.legacy-directory`。迁移不会调用嵌入接口。

当前向量存储仍为 SimpleVectorStore，适合单进程演示。它支持文件快照，但不等同于 Redis 向量索引。用户权限由 Spring Security 与会话归属表执行。当前部署模型为单实例；后台任务恢复和本地向量快照不支持多实例共享运行。

## 上传限制

PDF 最大 20 MB、最多 300 页，按页读取后使用约 800 token 的文本块进行索引。空文件、伪造 PDF、无法解析或无可提取文字的文件会返回错误；扫描件需要先做 OCR。

文档全文不超过 `app.pdf.max-context-chars`（默认 16000 字符）时，回答时直接读取全文，不依赖向量检索命中。超过预算后按 `app.pdf.similarity-threshold`（默认 0.3）和 `app.pdf.top-k`（默认 6）检索；这两个值可以按所用嵌入模型调整。PDF 版式产生的连续空格会在分块和构造上下文前归一化。已有文件也会重新读取文本，无需为本次修复重新上传。

## PDF 后台处理

`POST /ai/pdf/upload/{chatId}` 提交表单字段 `file`，返回 **HTTP 202** 和任务信息，上传请求不再等待解析和嵌入服务完成：

```json
{
  "id": "任务 UUID",
  "chatId": "pdf_example",
  "filename": "课程介绍.pdf",
  "status": "QUEUED",
  "error": null,
  "attempts": 0,
  "result": null
}
```

任务状态为 `QUEUED` → `RUNNING` → `SUCCEEDED` / `FAILED`。成功时 `result` 包含原有的 `ok`、`documentId`、`chunkCount`、`filename` 与 `reused` 等字段。

- `GET /ai/pdf/tasks/{taskId}`：查询自己的任务。
- `GET /ai/pdf/tasks?chatId=...`：恢复某个会话的处理进度。
- `POST /ai/pdf/tasks/{taskId}/retry`：重新排队自己的失败任务，复用暂存原文。
- 重复提交同一会话的相同文件返回已有任务；不同文件返回 409。任务过多时返回 429，避免无限堆积。
- 默认 2 个工作线程、8 个线程池排队位、最多 20 个未完成任务，配置位于 `application.properties`。
- 任务先保存到数据库与本地磁盘，再由调度器派发；重启将中断的 RUNNING 任务重新排队。成功后清理任务暂存文件，保留正式 PDF。
- 切换页面会停止当前页面的轮询，后台处理继续运行；从会话列表重新打开可继续查询进度或重试。任务就绪后才可提问。
- 同一会话的索引操作串行执行，不同会话可并行调用嵌入服务；本地向量快照写入单独串行执行。

历史消息接口：`GET /ai/history/{type}/{chatId}?limit=200&beforeId=消息ID`。类型支持 `chat`、`pdf`、`service`、`game`；`limit` 范围为 1-500，返回当前页内按时间排列的消息。

## 验证

后端自动测试使用 H2 的 MySQL 兼容模式、临时文件和模拟模型，不需要真实 MySQL 或模型调用：

```powershell
cd sunset-ai
mvn clean test
```

覆盖注册/登录/退出、CSRF、跨账号会话和文档访问拒绝、匿名历史防认领、完整历史与上下文窗口分离、消息分页、序列化、上传去重、失败清理、任务重试、任务重启恢复、队列容量限制和 PDF 上下文构造。

原有需要真实模型及本地资料的演示测试标记为 `integration`，默认不执行。需要时显式运行：

```powershell
mvn test -Dtest=SunsetAiApplicationTests "-Dtest.excludedGroups="
```

前端验证：

```powershell
cd spring-ai-protal
npm test
npm run build
```


## 部署配置

开发环境允许的跨域来源默认仅包含 localhost / 127.0.0.1 的 5173、5174 端口，可用 `APP_WEB_ALLOWED_ORIGINS` 配置逗号分隔的实际前端来源。部署时让前端 `/api` 反向代理到后端，并配置 SPA 路由回退到 `index.html`；使用 HTTPS 时设置 `APP_COOKIE_SECURE=true`。

用户、归属与任务表也包含在 `db/chat-schema.sql` 中；关闭自动建表的环境需要先执行该脚本。完整聊天历史、账号和任务元数据依赖 MySQL，PDF 与向量快照仍依赖本地 `data`，备份时需要一并保存。前端 API 使用 Cookie 登录，自定义直连地址时请让前后端位于兼容 SameSite Cookie 的站点，并配置明确的跨域来源。
