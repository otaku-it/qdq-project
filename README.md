# 智灵飞书启动器 Demo

一个用于验证“飞书扫码登录 → 钉钉知识库同步 → Agent Skills 预置或初始化”的 Vue 3 + Spring Boot Demo。

流程图：[登录、授权与角色分流](diagrams/feishu-launcher-entry.workflow.html)；[管理员与普通用户任务树](diagrams/feishu-launcher-task-tree.workflow.html)。

## 运行

先启动后端：

```bash
cd backend
./mvnw spring-boot:run
```

再启动前端：

```bash
cd frontend
npm install
npm run dev
```

打开 <http://localhost:5173>。

## 演示路径

- 租户注册和飞书组织架构同步是智灵中台已有的手动能力，启动器不重复执行。
- 以企业管理员身份启动：扫码登录后可同步钉钉企业知识库，并通过浏览器插件向飞书企业管理平台上传选中的 Skills。
- 管理员上传成功的 Skills 会记录为当前租户的可用目录。
- 以普通用户身份启动：扫码登录后可同步钉钉个人知识库；仅当所选 Skills 已被企业管理员预置时，才会在飞书豆包工作台初始化 Agent。
- 未满足租户 Skills 前置条件时，任务树会暂停，交付卡片显示失败原因并允许后续重试。

## API

- `GET /api/v1/launcher/blueprint`：读取初始化方案。
- `POST /api/v1/launcher/jobs`：创建任务。
- `GET /api/v1/launcher/jobs/{id}`：查询任务。
- `POST /api/v1/launcher/jobs/{id}/continue`：完成用户授权后继续。
- `POST /api/v1/launcher/jobs/{id}/retry`：重试失败步骤。

当前后端仍使用内存任务仓库，但 Agent Skills 已提供 `mock` 和 `playwright` 两种上传适配器。接入真实环境时，应以持久化任务和幂等键替换内存状态，并按以下边界实现适配器：

- 飞书登录：后端生成一次性二维码，并以扫码回调更新任务状态；不要在浏览器或日志中固化会话凭据。
- 钉钉/飞书知识库同步：记录源文档、目标文档、同步游标、版本冲突和失败重试信息。
- Skills 上传：Playwright Worker 在受控登录会话中进入豆包企业管理后台“内置技能管理”，上传根目录包含 `SKILL.md` 的 `.skill` 文件，并在列表中按 Skill ID 验证回显。只有真实回显成功后才写入租户级 Skills 目录。
- 普通用户初始化：校验 `tenant_id + skill_id + version` 已预置，再调用豆包工作台的初始化适配器。
- 任务运行：将 `LauncherJobService` 的内存状态替换为持久化任务、幂等键、审计日志和可恢复的工作队列。

## 开启 Playwright 真实上传

安装 Worker 依赖：

```bash
cd backend/automation
npm install
```

方式一，推荐给本地技术验证：准备一个专用 Chrome 用户目录，首次启动时扫码登录豆包企业后台，后续由 Worker 复用该目录。

```bash
cd backend
export LAUNCHER_SKILL_UPLOAD_MODE=playwright
export DOUBAO_USER_DATA_DIR="$PWD/.doubao-profile"
./mvnw spring-boot:run
```

方式二，连接已使用 `--remote-debugging-port` 启动的受控 Chrome：

```bash
cd backend
export LAUNCHER_SKILL_UPLOAD_MODE=playwright
export DOUBAO_CDP_ENDPOINT=http://127.0.0.1:9222
./mvnw spring-boot:run
```

可上传 Skill 源文件位于 `backend/skills/<skill-id>/SKILL.md`。Java 适配器在每次任务中生成临时 `.skill` 压缩包，任务结束后清理；Cookie、Token 和二维码凭据不会写入工程或任务日志。

豆包后台入口和 DOM 选择器集中在 `backend/automation/upload-agent-skills.mjs`。如果页面改版，只需调整 Worker，不影响 Spring Boot 编排接口。

官方飞书 Aily OpenAPI 当前提供技能调用、技能信息和技能列表查询，但官方公开文档中未找到“将本地 SKILL.md 上传到豆包企业提供栏”的接口。因此现阶段 Playwright 适合做技术验证；若后续开放正式上传 API，应新增 API Adapter 并优先替换 UI 自动化。
