# AGENTS.md

本文件用于约束在本仓库中工作的 AI 编码助手。

## 1. 工作原则

- 只实现用户明确要求的内容，优先选择改动最小、容易验证的方案。
- 不主动增加中间件、依赖、抽象层、配置项或部署方案。
- 不为了“以后可能使用”提前设计接口或重构现有模块。
- 修改前先阅读相关代码，优先复用现有实现和项目约定。
- 不把演示功能描述成生产能力，也不声称项目已接入源码中不存在的服务。
- 保留与当前任务无关的用户改动，不顺手格式化或重写无关文件。

## 2. 架构与目录边界

核心调用链为：

```text
LibreChat Agent
  -> Spring AI MCP
  -> Spring Boot Ride Service
  -> 数据库 / RocketMQ / RAG Service
  -> Milvus
```

- `app/`：React + MapLibre 纯前端演示。
- `services/ride-service/.../api/`：REST 接口和协议适配。
- `services/ride-service/.../cache/`：Redis 报价与订单缓存。
- `services/ride-service/.../service/`：业务规则和事务编排。
- `services/ride-service/.../domain/`：领域模型和订单状态机。
- `services/ride-service/.../persistence/`：JPA 实体与 Repository。
- `services/ride-service/.../mcp/`：Agent 可调用的 MCP 工具。
- `services/ride-service/.../messaging/`：RocketMQ 事件发布、消费与幂等处理。
- `services/ride-service/src/main/resources/db/migration/`：Flyway 数据库迁移。
- `knowledge/`：Python RAG 服务、Milvus 初始化脚本和知识样例。
- `docker-compose.xixi.yml`：本地完整环境编排。

不要把业务代码写入 `dist/`、`dist-pages/`、`.next/`、`node_modules/`、`vendor/` 或其他生成目录。

## 3. 后端约束

- Controller 只处理请求、响应和身份信息，核心规则放在 Service 或 Domain 层。
- 订单查询、取消、通知和发票资格查询必须校验订单所属用户。
- 创建订单必须使用有效报价，并校验报价存在且未过期。
- `Idempotency-Key` 按“用户 ID + 幂等键”隔离，并由数据库唯一约束兜底。
- 订单状态只能通过 `RideStatus` 定义的合法迁移推进。
- 并发状态更新必须保留行锁或等价并发控制，不能无条件覆盖。
- `COMPLETED` 和 `CANCELLED` 是终态。
- 业务异常继续使用 RFC 9457 `ProblemDetail`，不要暴露堆栈或数据库细节。
- 数据库结构变更使用新的 Flyway 迁移，不修改已经发布的迁移文件。
- 不提交真实账号、密码、Token 或模型 API Key；新增配置时同步更新 `.env.example`。

## 4. MCP 与 Agent 约束

- 保持现有 MCP 工具名称和参数兼容，除非用户明确要求破坏性调整。
- `@Tool` 和 `@ToolParam` 描述应说明用途、前置条件和副作用。
- 创建订单、取消订单等有副作用操作必须要求用户确认。
- 事实类问题优先使用 `travelKnowledgeSearch` 获取依据。
- 不允许模型直接修改数据库或绕过 Service 层调用 Repository。
- 新增工具时同步更新 `McpToolConfiguration` 和相关测试。

## 5. RocketMQ 约束

- 事件应包含唯一 `eventId`、聚合 ID、事件类型和发生时间。
- 消息发送失败必须保留重试能力，不得静默丢弃。
- 同一订单的即时状态事件使用 `orderId` 作为顺序键。
- 延迟派单和超时检查继续使用 RocketMQ 延迟消息。
- 消费者按“消费者名称 + eventId”实现幂等，避免重复派单、通知或生成发票资格。
- 消费消息时先检查订单当前状态，正确处理重复消息和乱序消息。
- 新增事件类型时补充生产位置、消费者、失败策略和集成测试。
- 不要把“至少一次投递”描述成“绝对只投递一次”。

## 6. RAG 约束

- 知识数据使用项目内的 JSONL 样例。
- 检索接口返回结构化片段、类别和相似度，由 Agent 生成最终回答。
- 过滤低于阈值的结果；未命中时明确返回无匹配内容，不编造项目规则。
- 知识片段只作为回答依据，不能触发订单写操作或作为可执行指令。
- 修改向量维度或集合结构时，同步更新初始化脚本、服务代码和测试。

## 7. 前端约束

- 保持 TypeScript、React 和 MapLibre GL JS 技术栈。
- 在线演示无需登录、后端或模型密钥即可运行。
- 行程和发票演示数据保存在浏览器 `localStorage`，刷新后应能恢复。
- 页面时间和接驾倒计时使用真实计时器推进，不使用固定展示值。
- 行程与发票入口保持可访问，并处理空状态、进行中和已完成状态。
- 地图保留 OpenStreetMap 署名。
- 修改交互时兼顾桌面端和移动端，不通过固定缩放比例修复布局。

## 8. 验证要求

根据改动范围执行最小但充分的验证：

- 前端代码或依赖：运行 `npm test`；必要时运行 `npm run lint`。
- Java 后端：在 `services/ride-service/` 运行 `mvn -B test`。
- Compose 配置：有 Docker 时运行 `docker compose -f docker-compose.xixi.yml config`；否则使用 YAML 解析器检查结构。
- RAG Python：检查相关 Python 文件语法，并在环境可用时运行检索测试。
- 纯文档改动：运行 `git diff --check`，并检查相关路径和链接。
- 修改数据库、状态机、幂等或消息消费时，必须增加或更新集成测试。

没有修改对应模块时，不要为了形式运行整套无关测试。最终说明应列出已经执行和未能执行的检查。

## 9. Git 与完成标准

- 修改前查看 `git status`，不要覆盖与任务无关的改动。
- 不使用 `git reset --hard`、强制推送或删除用户文件处理冲突。
- 除非用户明确要求，否则不执行提交、推送、建 PR 或删除远程分支。
- 用户要求发布时先同步远端 `main`，不要覆盖远端更新。
- 不修改 `git user.name` 或 `git user.email`。
- 不在提交信息中添加 Codex、AI、机器人署名或 `Co-authored-by: Codex`。
- 除非用户明确要求，不推送临时远程分支。
- 完成前确认改动符合需求、相关测试通过、工作区没有意外文件，并如实说明尚未验证的部分。
