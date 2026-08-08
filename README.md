# 嘻嘻出行（Xixi Travel Agent）

一个面向网约车场景的智能出行 Agent 项目。用户可以用自然语言提出出行需求，Agent 根据任务自主调用知识检索、车型询价、创建订单、查询状态、取消订单、通知查询和发票资格查询等 MCP 工具。

- 使用 LibreChat 承载用户、会话、模型接入和 Agent 对话。
- 使用 Spring AI MCP 将出行业务能力封装为模型可调用工具。
- 通过有界 ReAct 循环组织“判断—工具执行—结果反馈”，让 RAG 证据和业务工具结果共同驱动下一步动作。
- 使用 Spring Boot、MySQL 和 Redis 实现报价、订单、缓存、状态机、数据归属与幂等控制。
- 将记忆按是否跨 Session 使用划分：LibreChat 管理当前会话上下文，Redis 保存短期任务状态；MySQL 保存用户确认的长期偏好，Milvus 提供按用户隔离的语义召回。
- 使用 RocketMQ 异步处理模拟派单、超时取消、通知和发票资格。
- 使用 Milvus 语义召回、BM25 关键词召回、加权分数融合和 CrossEncoder 精排构建出行领域 RAG 知识库。
- 提供 React + MapLibre 的独立交互演示，方便直接查看产品效果。

![嘻嘻出行界面](./public/og.png)

## 在线体验

- 在线演示：[https://yinpeng04.github.io/xixi-travel-agent/](https://yinpeng04.github.io/xixi-travel-agent/)
（仓库远端未接入大模型，无法连接Agent，仅为前段演示）
- GitHub 仓库：[https://github.com/YINPENG04/xixi-travel-agent](https://github.com/YINPENG04/xixi-travel-agent)


## 核心技术选型

| 模块 | 技术 | 在项目中的作用 |
|---|---|---|
| Agent 入口 | LibreChat | 用户登录、会话管理、模型接入和 MCP 工具调用 |
| MCP 工具服务 | Spring AI 1.0.1 | 将 Java 业务方法注册为 Agent 可调用工具 |
| 业务后端 | Java 21、Spring Boot 3.4.5 | 报价、订单、状态机、归属校验和事务编排 |
| 交易与记忆数据库 | MySQL 8.4、Spring Data JPA、Flyway | 持久化报价、订单、幂等键、长期用户偏好、Outbox 和消费记录 |
| 缓存与短期状态 | Redis 7.4、Spring Data Redis | 缓存报价、热点订单、记忆召回结果和当前 Session 任务状态 |
| 消息队列 | RocketMQ 5.5、RocketMQ Spring 2.3.5 | 延迟派单、超时检查、异步通知、重试和死信处理 |
| 向量检索 | Python、sentence-transformers、Milvus 2.5、BM25 | 分别维护公共领域知识和用户长期记忆两个 Collection |
| 交互演示 | TypeScript、React 19、MapLibre GL JS | 地图、车型报价卡、订单状态和行程界面 |


## 系统架构

```mermaid
flowchart LR
    U["用户"] --> LC["LibreChat<br/>会话与 Agent"]
    LC <--> LLM["用户配置的大模型"]
    LC -->|"MCP 工具调用"| MCP["Spring AI MCP Server"]

    MCP --> RS["Spring Boot Ride Service"]
    RS <--> DB["MySQL<br/>交易数据、长期记忆正文与版本"]
    RS <--> CACHE["Redis<br/>业务缓存、Session 状态与召回缓存"]
    RS -->|"发布订单事件"| MQ["RocketMQ"]
    MQ -->|"派单、通知、发票消费"| RS
    RS -->|"知识检索"| RAG["Python RAG Service<br/>BM25 + Score Fusion + Rerank"]
    RAG --> KNOWLEDGE["Milvus: xixi_travel_knowledge<br/>公共领域知识"]
    RAG --> MEMORIES["Milvus: xixi_user_memories<br/>按 user_id 隔离的记忆索引"]

    DEMO["React + MapLibre<br/>独立在线演示"] --> OSM["OpenStreetMap"]
    DEMO <--> LS["localStorage"]
```

### Agent 调用链路

1. 用户在 LibreChat 中描述出发地、目的地或咨询出行规则，Agent 先调用 `travelTaskStart` 获取结构化意图、缺失槽位和唯一允许的 `nextAction`。
2. 意图不明确时状态机停留在 `NEEDS_CLARIFICATION`；需要个性化或公共知识时，分别调用 `travelMemorySearch`、`travelKnowledgeSearch`。
3. 叫车任务按状态机依次进入路线收集、询价、报价选择、准备下单和等待确认阶段，每次工具结果都通过 `travelTaskObserve` 写回。
4. 用户明确确认后，状态机才允许调用 `rideCreate`；Spring Boot 同时校验一次性确认凭证，在订单事务中原子消费凭证并写入订单。
5. 创建成功后任务进入 `WAITING_FOR_DISPATCH`，RocketMQ 异步触发模拟派单、超时检查和用户通知；恢复会话时通过 `travelTaskGet` 继续任务。
6. Agent 查询到派单或终态后写回 Observation 并停止任务。取消订单同样经过准备、确认、执行的合法迁移。

### 任务级执行控制

任务状态按用户 ID 与 conversation ID 隔离并保存在 Redis，默认 TTL 为 30 分钟。后端状态中包含意图、阶段、下一动作、缺失业务资源、失败次数、任务版本和终止原因：

- 确定性意图识别覆盖知识、询价、下单、状态、取消、通知、发票和记忆操作，同时提取路线与订单 ID；多意图同分或未知意图必须先追问。
- 询价链路只能按 `READY_TO_QUOTE → WAITING_FOR_QUOTE_SELECTION → READY_TO_PREPARE_CREATE → WAITING_FOR_CREATE_CONFIRMATION → READY_TO_CREATE → WAITING_FOR_DISPATCH` 推进。
- `travelTaskObserve` 通过 Lua CAS 原子校验 `taskId + version` 并续期任务 TTL，拒绝陈旧或重复 Observation；非法阶段不能跳过确认直接进入写操作。
- 工具失败只允许重试一次，第二次失败进入 `FAILED` 终态；用户拒绝确认进入 `CANCELLED` 终态。

### ReAct 与 RAG 协作

ReAct 位于 Agent 调度层，RAG 是其中一个可调用动作。项目通过 MCP Server 的 `serverInstructions` 约束有界循环，并且不记录或展示模型的私有思维过程：

```mermaid
flowchart LR
    Q["用户问题"] --> D["判断缺少的知识或业务状态"]
    D --> A["Action: 调用 MCP 工具"]
    A --> O["Observation: 结构化工具结果"]
    O -->|"证据充分"| N["回答或执行下一业务动作"]
    O -->|"EMPTY / LOW_SCORE / AMBIGUOUS"| R["补充关键实体并改写问题"]
    R -->|"最多重试一次"| A
    O -->|"重试后仍不足"| F["明确说明知识库未命中"]
```

`travelKnowledgeSearch` 返回 `cycleId`、当前轮次、`retrievalStatus`、最高召回分、Top 2 分差、终止标记和 Top 3 证据。状态包括 `EVIDENCE_FOUND`、`LOW_SCORE`、`AMBIGUOUS` 和 `EMPTY`：首次证据不足时，Redis 按用户与会话保存 cycle，Agent 必须改写问题并携带同一 `cycleId` 重试；后端拒绝未改写的重复问题、并发重试和第三轮检索。cycle 默认保留 10 分钟，第二轮结束后无论是否命中都进入终态，避免无界工具循环和无依据回答。下单、取消等写操作则继续进入准备、用户确认和执行链路。

## Agent 与 MCP 工具

路径：`services/ride-service/src/main/java/cn/xixitravel/ride/mcp/`

| 工具 | 功能 | 关键约束 |
|---|---|---|
| `travelIntentRecognize` | 返回结构化意图、候选意图、槽位和缺失槽位 | 未知或歧义意图不得执行写操作 |
| `travelTaskStart` | 创建或继续当前会话任务 | 只允许执行返回的 `nextAction` |
| `travelTaskGet` | 恢复当前任务状态 | 按用户与 conversation ID 隔离 |
| `travelTaskObserve` | 将工具结果写回任务状态机 | Lua CAS 校验任务 ID、版本、合法迁移和失败上限 |
| `travelKnowledgeSearch` | 在有界 ReAct cycle 中检索地点、车型、规则、安全和发票知识 | Redis 记录用户会话轮次，最多两轮并拒绝重复问题 |
| `rideQuote` | 生成轻享、舒适、六座三类车型报价 | 创建订单前必须先询价 |
| `ridePrepareCreate` | 为待创建订单生成一次性确认凭证 | 绑定用户、会话、报价、路线和有效期 |
| `rideCreate` | 使用有效报价创建订单 | 原子消费确认凭证，并校验幂等键对应的请求内容 |
| `rideStatus` | 查询订单当前状态 | 校验订单所属用户 |
| `ridePrepareCancel` | 为待取消订单生成一次性确认凭证 | 绑定用户、会话、订单和有效期 |
| `rideCancel` | 取消尚未开始的订单 | 原子消费确认凭证，并校验订单归属和状态 |
| `rideNotifications` | 查询异步派单、取消和完成通知 | 校验订单所属用户 |
| `rideInvoiceEligibility` | 查询完成行程的发票申请资格 | 完成事件消费后生成 |
| `travelMemoryList` | 读取当前用户的长期出行偏好 | 只按用户 ID 返回该用户数据 |
| `travelMemorySearch` | 根据当前问题语义检索跨 Session 长期记忆 | Milvus 按用户召回 Top 5，MySQL 校验版本后返回 Top 3 |
| `travelMemoryRemember` | 保存或更新一条长期偏好 | 校验敏感信息、可信度和过期时间；冲突需明确选择保留、替换或合并 |
| `travelMemoryForget` | 软删除一条长期偏好 | 必须由用户明确确认并写入审计记录 |
| `travelMemoryAudit` | 查询最近 100 条记忆审计记录 | 不返回历史正文或正文哈希 |
| `travelSessionContextGet` | 读取当前会话的报价、订单、待确认操作和任务摘要 | 使用用户 ID 与 conversation ID 共同隔离 |
| `travelSessionContextSave` | 更新当前会话任务状态 | 只写入带 TTL 的 Redis，不作为跨 Session 长期记忆 |

Spring AI 将上述 20 个 Java 方法注册为同步 MCP 工具，并通过 SSE 暴露给 LibreChat。下单和取消不是只依赖提示词：任务状态机先返回并校验状态推进的合法下一动作，后端再签发随机一次性凭证，仅保存其 SHA-256 摘要，并在交易事务中使用悲观锁校验用户、会话、资源、请求指纹、有效期和使用状态。凭证不能跨会话或跨操作复用。

## Agent 记忆与上下文压缩

记忆按是否跨 Session 使用划分，而不是按存储组件划分。

### 短期记忆

- LibreChat 组装当前 Session 的最近消息、MCP 工具结果、RAG 证据和滚动摘要，并将它们作为工作上下文发送给模型。
- `travelSessionContextSave` 保存摘要所需的工作状态；`travelTaskStart/Get/Observe` 另外保存受控任务的意图、阶段、下一动作、资源 ID、版本和失败次数。两类 Redis 键都按用户 ID 与 conversation ID 隔离并默认保留 30 分钟。
- `librechat.xixi.yaml` 在上下文使用率达到 80% 时触发增量摘要，并保留最近 6 轮或 8000 Token；较早且超过 4000 字符的工具结果会先软裁剪，再按位置清除。
- 原始聊天记录不会因为压缩而从会话存储中删除；订单、报价和行程状态始终通过 MCP 从 MySQL/Redis 查询，不能由摘要替代。

### 长期记忆 Record

1. Agent 识别出稳定的跨 Session 偏好，例如“带大件行李时优先六座车型”。
2. Agent 先询问用户是否长期保存；`travelMemoryRemember` 只有收到 `confirmedByUser=true` 才允许写入。
3. 写入前由代码拦截密码、验证码、证件号、手机号、支付卡、API 凭证、报价 ID 和订单 ID；MySQL 保存记忆正文、类别、版本、可信度、状态和过期时间，作为更新、删除和校验的权威数据。
4. 同一数据库事务写入待处理的记忆索引任务；事务提交后生成向量并写入 Milvus 的 `xixi_user_memories` Collection，再使 Redis 列表缓存与召回缓存失效。

同一用户、类别和 key 出现不同值时不会静默覆盖：Agent 必须向用户展示冲突，并明确选择 `KEEP_EXISTING`、`REPLACE` 或 `MERGE`。合并采用去重后的稳定顺序；默认保留期为常用地点 180 天、其他分类 365 天，也可以由用户指定 1～3650 天或明确设为不过期。

删除同样需要用户确认：MySQL 将记录软删除并登记索引任务，事务提交后再删除对应向量索引并清除缓存。定时任务将到期记忆标为 `EXPIRED` 并删除向量索引；Milvus 暂时不可用时，任务保留在 MySQL 并按指数退避重试。创建、重新确认、冲突处理、合并、替换、过期和遗忘都写入只含摘要元数据的审计表，审计接口不暴露历史正文或哈希。

### 长期记忆 Retrieve

1. 首先按用户 ID、查询摘要和记忆版本检查 Redis 召回缓存。
2. 未命中时，在 `xixi_user_memories` 中使用 `user_id` 过滤并语义召回 Top 5，避免跨用户检索。
3. 回到 MySQL 校验记录处于 `ACTIVE`、尚未过期且版本与索引一致，过滤低于可信度阈值、已删除、已过期或旧版本向量。
4. 使用“语义分数 × 可信度”排序并返回 Top 3，作为“相关用户记忆”注入当前 Session 上下文。

公共知识与用户记忆使用不同 Collection：`xixi_travel_knowledge` 保存所有用户共享的地点、车型、安全和发票知识；`xixi_user_memories` 只保存长期记忆的向量索引并强制按用户过滤。长期记忆列表默认缓存 30 分钟，语义召回结果默认缓存 10 分钟。

自定义长期记忆不使用 MongoDB。MongoDB 仅由 LibreChat 用于账号和聊天会话，未启用 `librechat` Compose Profile 时不会启动；如果完全移除 MongoDB，需要替换或重写 LibreChat 的会话数据层，不属于当前项目范围。

## 业务后端设计

路径：`services/ride-service/`

### 报价与订单

- 报价根据基础价、里程单价和车型倍率计算，有效期为 5 分钟。
- 报价连同出发地、目的地、里程和时长快照写入 MySQL，再同步缓存到 Redis；缓存 TTL 与报价剩余有效期一致。下单路线必须与报价快照一致。
- 创建订单必须携带 `Idempotency-Key` 和一次性确认凭证。
- MySQL 使用“用户 ID + 幂等键”唯一索引防止重复下单。
- 相同幂等键只有在报价与路线都相同时才返回原订单；复用于不同请求会返回冲突，避免静默返回错误订单。
- 查询、取消和异步结果查询都会校验订单所属用户。
- 订单查询采用 Cache-Aside 模式缓存热点状态，默认 TTL 为 2 分钟；状态变更在数据库事务提交后刷新缓存。
- Redis 连接或序列化失败时自动回源 MySQL，不影响报价和订单主流程。
- 参数错误和业务异常使用 RFC 9457 `ProblemDetail` 返回。

### 订单状态机

```mermaid
stateDiagram-v2
    [*] --> CREATED
    CREATED --> DRIVER_ASSIGNED
    DRIVER_ASSIGNED --> DRIVER_ARRIVING
    DRIVER_ARRIVING --> DRIVER_ARRIVED
    DRIVER_ARRIVED --> IN_PROGRESS
    IN_PROGRESS --> COMPLETED

    CREATED --> CANCELLED
    DRIVER_ASSIGNED --> CANCELLED
    DRIVER_ARRIVING --> CANCELLED
    DRIVER_ARRIVED --> CANCELLED

    COMPLETED --> [*]
    CANCELLED --> [*]
```

状态更新使用数据库悲观行锁和版本字段控制并发。`COMPLETED`、`CANCELLED` 为终态，非法状态迁移会被拒绝。

## RocketMQ 事件驱动链路

路径：`services/ride-service/src/main/java/cn/xixitravel/ride/messaging/`

创建订单时，业务事务同时写入订单数据、`ORDER_CREATED` 和 `ORDER_TIMEOUT_CHECK` Outbox 事件。定时发布器读取待发送事件并投递到 RocketMQ：

- 派单消费者延迟处理新订单，仅将仍为 `CREATED` 的订单推进到 `DRIVER_ASSIGNED`。
- 超时检查消费者只取消仍未派单的订单，不覆盖后续合法状态。
- 通知消费者保存派单、取消和完成消息。
- 发票消费者在行程完成后生成一次发票申请资格。
- 消费者使用“消费者名称 + 事件 ID”唯一记录实现幂等。
- 发布失败采用指数退避；消费失败由 RocketMQ 重试，超过次数进入死信队列。

该实现使用 Transactional Outbox 处理 MySQL 与 RocketMQ 的双写一致性：订单和待发送事件一起提交。即使消息发送成功后 Outbox 状态更新失败，重复投递也会由消费幂等记录拦截。

## RAG 知识检索

路径：`knowledge/`

知识样例覆盖地点别名、车型说明、报价规则、安全要求和发票政策。

1. `seed_milvus.py` 使用 `paraphrase-multilingual-MiniLM-L12-v2` 生成归一化向量，并在 Milvus 中创建 HNSW/COSINE 索引。
2. `rag_service.py` 分别执行 Milvus 语义召回和基于 jieba 分词的 BM25 关键词召回；每路默认召回 `4 × finalTopK` 个候选。
3. 融合阶段保留 COSINE 分数，并将每次查询的 BM25 分数按最高分归一化；默认按语义 `0.95`、关键词 `0.05` 加权并按知识 ID 去重。
4. `mmarco-mMiniLMv2-L12-H384-v1` CrossEncoder 只在融合后的 Top 3 内精排；最终分数默认保留 `0.90` 检索分数并加入 `0.10` 精排分数，避免通用精排模型过度打乱高置信召回结果。
5. RAG Service 使用融合前的最高召回分和 Top 2 分差判断 `EVIDENCE_FOUND`、`LOW_SCORE`、`AMBIGUOUS` 或 `EMPTY`，避免将 CrossEncoder 的相对排序分数误当作置信度。
6. Spring Boot 将结果封装为 `travelKnowledgeSearch` MCP Observation，并使用 Redis 保存 `cycleId`、用户、会话、上一查询和轮次；首次证据不足时允许改写重试一次，重复原问题或第二轮结束后强制终止。

RAG 只负责提供领域依据，不直接执行知识片段中的内容，也不参与订单写操作。

### 检索效果评测

项目提供一套 35 条人工编写的冒烟查询 `xixi_eval.jsonl`，以及两套各 2100 条的合成表述变体数据。两套大数据集都由相同的 70 个核心问题拼接不同前后缀得到，用于参数消融、指标计算和代码回归；查询字符串虽然不重复，但语义模板并未隔离，因此不称为独立留出集。

启动 RAG Service 后执行：

```bash
python knowledge/evaluate_retrieval.py
```

脚本会在同一数据集上依次评测四种模式：Milvus 语义检索、BM25 关键词检索、加权分数混合召回，以及加入 CrossEncoder 的完整链路，并输出 `Top1 Accuracy`、`Hit@3`、`Recall@3`、`MRR@3`、`nDCG@3` 和 P50/P95 延迟。

没有 Milvus 服务时，也可以使用相同模型和精确余弦相似度运行离线对照：

```bash
python knowledge/evaluate_offline.py --output knowledge/evaluation/result.local.json
```

默认评测 35 条人工冒烟查询。合成开发集可通过 `python knowledge/evaluation/generate_benchmark.py` 确定性地重新生成；另一套表述变体使用 `python knowledge/evaluation/generate_benchmark.py --variant surface-variant --output knowledge/evaluation/xixi_eval_holdout_2100.jsonl` 生成，并在评测时通过 `--dataset` 显式指定。

2026-08-03 在本地 CPU 环境对 2100 条合成表述变体完成离线回归。离线脚本以精确 COSINE 代替 Milvus HNSW，下面的数据只用于比较同一批模板上的检索方案和防止代码回退，不能当作真实用户流量下的准确率；运行时间也不等同于容器服务的线上延迟。
机器可读结果见 [`knowledge/evaluation/benchmark_2100_result.json`](./knowledge/evaluation/benchmark_2100_result.json)。

| 检索模式 | 合成集 Top-1 | 合成集 Recall@3 | MRR@3 | nDCG@3 | 2100 条运行时间 |
|---|---:|---:|---:|---:|---:|
| 纯语义 | 89.43% | 99.86% | 94.17% | 95.63% | 14.87 s |
| 纯 BM25 | 64.24% | 84.43% | 73.02% | 75.94% | 1.00 s |
| 语义 + BM25 加权融合 | 90.29% | 100.00% | 95.02% | 96.32% | 15.88 s |
| 加权融合 + CrossEncoder | 90.62% | 100.00% | 95.19% | 96.44% | 78.55 s |

在这套合成回归集上，完整链路相对纯语义基线的 Top-1 提升 `1.19` 个百分点，Recall@3 提升 `0.14` 个百分点；口语噪声分组的 Top-1 反而从 83.02% 降至 82.06%。这说明混合检索在当前小样本上只有有限收益，仍需独立采集并人工标注真实查询后才能评价泛化效果。

当前知识库只有 7 条文档，2100 条数据不是 2100 个独立问题。机器可读结果已经标记为 `synthetic_surface_regression`；简历和项目介绍不使用其中的 90.62% 作为泛化准确率。扩充知识库时应同步建设与开发问题语义隔离的人工标注测试集。

## 项目目录

```text
.
├─ app/                              React + MapLibre 在线演示
├─ services/ride-service/            Spring Boot 业务与 MCP 服务
│  └─ src/main/java/cn/xixitravel/ride/
│     ├─ api/                        REST 接口
│     ├─ cache/                      Redis 报价、订单与记忆缓存
│     ├─ context/                    Redis Session 短期任务状态
│     ├─ domain/                     报价、订单与状态机
│     ├─ knowledge/                  RAG 客户端
│     ├─ memory/                     MySQL 长期记忆、Milvus 索引与召回缓存
│     ├─ mcp/                        MCP 工具
│     ├─ messaging/                  RocketMQ 与幂等消费者
│     ├─ persistence/                JPA 实体与 Repository
│     └─ service/                    业务规则和事务编排
├─ services/rocketmq/                RocketMQ Broker 配置
├─ knowledge/                        RAG 服务、初始化脚本与知识数据
├─ scripts/bootstrap-librechat.ps1   LibreChat 固定基线检出脚本
├─ docker-compose.xixi.yml           完整本地环境编排
└─ librechat.xixi.yaml               LibreChat MCP 配置
```

## 快速体验

### 运行纯前端演示

环境要求：Node.js 22.13 或更高版本。

```bash
git clone https://github.com/YINPENG04/xixi-travel-agent.git
cd xixi-travel-agent
npm ci
npm run dev
```

浏览器访问 [http://localhost:3000](http://localhost:3000)。

### 启动完整 Agent 环境

环境要求：Git、PowerShell、Docker 和 Docker Compose。

```powershell
git clone https://github.com/YINPENG04/xixi-travel-agent.git
cd xixi-travel-agent
Copy-Item .env.example .env
powershell -ExecutionPolicy Bypass -File .\scripts\bootstrap-librechat.ps1
docker compose -f docker-compose.xixi.yml --profile librechat up --build
```

启动后：

- LibreChat：[http://localhost:3080](http://localhost:3080)
- Ride Service：[http://localhost:8081](http://localhost:8081)
- MCP SSE：[http://localhost:8081/sse](http://localhost:8081/sse)
- RAG Service：[http://localhost:8090](http://localhost:8090)


## REST API

| 方法 | 路径 | 功能 |
|---|---|---|
| `POST` | `/api/v1/knowledge/search` | 检索出行知识库 |
| `POST` | `/api/v1/quotes` | 生成车型报价 |
| `POST` | `/api/v1/rides/confirmations/create` | 为下单生成一次性确认凭证 |
| `POST` | `/api/v1/rides` | 使用有效报价和确认凭证创建订单 |
| `GET` | `/api/v1/rides/{orderId}` | 查询订单状态 |
| `POST` | `/api/v1/rides/{orderId}/confirmations/cancel` | 为取消订单生成一次性确认凭证 |
| `POST` | `/api/v1/rides/{orderId}/cancel` | 使用确认凭证取消允许取消的订单 |
| `GET` | `/api/v1/rides/{orderId}/notifications` | 查询异步订单通知 |
| `GET` | `/api/v1/rides/{orderId}/invoice-eligibility` | 查询发票申请资格 |
| `PATCH` | `/api/v1/internal/rides/{orderId}/status/{status}` | 演示环境推进订单状态 |

除询价与知识检索外，订单接口通过 `X-Xixi-User` 请求头传递演示用户身份；创建订单还需要 `Idempotency-Key`。创建和取消必须先调用对应的 confirmations 接口，再在同一用户与会话下提交返回的一次性凭证。

## 当前边界

- 在线演示尚未连接 Spring Boot 后端，报价、路线和司机信息是前端演示数据。
- 项目没有接入真实地图路线规划、车辆调度、定位、支付、短信或发票开具服务。
- `X-Xixi-User` 是必填的演示身份头，但仍由调用方提供，不是生产级认证方案；当前“归属校验”不能表述为可信身份认证或完整防越权能力。
- 下单和取消已由后端强制执行一次性确认凭证校验；不过凭证的签发仍由 Agent 发起，尚未接入独立 UI 审批或真实账号签名，不能等同于生产级人机确认。
- ReAct 控制器只对单个 `cycleId` 强制两轮上限并记录结构化 Observation，不保存模型私有思维过程；当前尚未使用真实大模型完成端到端 Agent 行为评测。
- 长期记忆的写入和删除会校验 `confirmedByUser`，但演示环境的用户 ID 仍由工具参数传入，不是生产级身份绑定。
- 长期记忆通过 MySQL 索引任务补偿 Milvus 写入失败，但当前重试任务仍是单实例轮询，未实现多实例抢占租约和运维告警。
- RocketMQ 采用单 NameServer、单 Broker 的本地演示配置，不具备生产级高可用能力。
- Redis TTL 由真实容器测试覆盖，RocketMQ 真实 Broker 链路由 CI 中的显式集成用例覆盖；这只能证明本地编排链路，不代表生产级故障恢复和高并发能力。
- RAG 使用与内置 7 条知识样例配套的小规模评测集，尚未实现知识版本管理，也未在更大规模、语义独立的人工标注语料上验证效果。

## 开源与许可证

- LibreChat 固定基线：`8e5ef1fb31e9d63b735c089b21cbc82c50acce46`，MIT License。
- Namma Yatri 仅作为网约车业务流程参考，本项目未复制或打包其源码。
- 地图数据来自 OpenStreetMap，界面保留相应署名。
- 第三方依赖和许可证见 [OPEN_SOURCE_USAGE.md](./OPEN_SOURCE_USAGE.md) 与 [THIRD_PARTY_NOTICES.md](./THIRD_PARTY_NOTICES.md)。

本项目用于学习、作品展示和 Agent 工程实践，不代表可直接投入生产的网约车系统。
