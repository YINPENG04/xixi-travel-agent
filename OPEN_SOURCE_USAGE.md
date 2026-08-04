# 嘻嘻出行智能 Agent 开源项目使用说明

更新时间：2026-08-02

本文说明嘻嘻出行项目使用或参考的开源项目、代码归属、许可证及使用边界。版本信息以当前项目源码和容器编排文件为准。

## 一、产品名称与关系声明

本项目正式名称为“嘻嘻出行”，英文名称为“Xixi Travel”，智能出行 Agent 名称为“嘻嘻”或“xixi”。

## 二、代码归属总览

| 类别 | 项目或模块 | 与本项目的关系 | 是否包含其源码 |
|---|---|---|---|
| 直接 Fork 基线 | LibreChat | 对话、鉴权、会话、流式响应、语音和 Agent/MCP 框架的基础工程 | 由固定基线脚本检出 |
| 业务参考 | Namma Yatri | 仅参考网约车业务流程、订单生命周期和领域建模思路 | 否 |
| 自行开发 | Spring Boot 出行业务服务 | 报价、订单、行程、安全、权限、幂等和状态机 | 是 |
| 自行开发 | MCP 工具层 | 将出行业务能力封装为 Agent 可调用工具 | 是 |
| 自行开发 | 嘻嘻出行界面 | 路线地图、车型报价卡、订单卡和行程状态卡 | 是 |
| 自行开发 | RAG 知识检索服务 | 地点别名、车型说明、规则和政策的混合检索、精排及 MCP 接入 | 是 |

## 三、直接 Fork 的开源项目

### LibreChat

- 上游项目：https://github.com/danny-avila/LibreChat
- 当前基线提交：`8e5ef1fb31e9d63b735c089b21cbc82c50acce46`
- 许可证：MIT License
- 使用方式：通过 `scripts/bootstrap-librechat.ps1` 检出固定提交后进行二次开发。
- 保留能力：用户与会话管理、聊天界面、流式响应、语音能力、模型接入及 Agent/MCP 调用框架。
- 本项目改造：新增嘻嘻出行场景组件、地图和报价卡片，并接入自行开发的 Spring Boot MCP 服务。

发布或分发修改版时，应保留 LibreChat 原项目的 MIT 版权和许可证声明。LibreChat 源码与自行开发代码可通过 Git 提交记录、文件路径和本文的归属说明进行区分。

## 四、仅作业务参考的开源项目

### Namma Yatri

- 上游项目：https://github.com/nammayatri/nammayatri
- 许可证：AGPL-3.0
- 使用方式：仅参考其公开的网约车业务流程、领域概念和订单生命周期设计。
- 当前项目没有复制、修改或打包 Namma Yatri 的源代码。

因此，Namma Yatri 不属于当前项目的源码依赖。如果未来复制或改写其受版权保护的实现代码，需要重新评估 AGPL-3.0 的开源义务。

## 五、自行开发的代码

| 路径 | 技术 | 内容 |
|---|---|---|
| `services/ride-service/` | Java、Spring Boot、Spring AI、JPA、Spring Data Redis、RocketMQ Spring | 路线、报价、订单、MySQL 持久化、Redis 缓存、订单事件及 MCP 工具 |
| `services/ride-service/src/main/java/` | Java | 订单状态机、用户权限校验、报价有效期、数据库幂等、Transactional Outbox 和消息消费者 |
| `app/` | TypeScript、React、MapLibre | 地图、车型报价卡、订单卡和状态展示 |
| `knowledge/` | Python、FastAPI、PyMilvus | Milvus 与 BM25 混合召回、加权分数融合、CrossEncoder 精排、集合初始化和知识数据样例 |
| `docker-compose.xixi.yml` | Docker Compose | MySQL、Redis、RocketMQ、Milvus、RAG、etcd、MinIO 和业务服务编排 |
| `librechat.xixi.yaml` | YAML | LibreChat 的 Agent 与 MCP 接入配置 |

项目主体不是纯 Python 项目：核心交易后端是 Java，前端是 TypeScript/React；Python 用于知识库初始化、向量数据处理和 RAG 语义检索服务。

## 六、主要运行时依赖

| 开源项目 | 当前版本或镜像 | 用途 | 许可证或授权说明 |
|---|---|---|---|
| Spring Boot | 3.4.5 | Java Web、校验和业务服务基础框架 | Apache-2.0 |
| Spring AI | 1.0.1 | MCP 服务端和 AI 工具集成 | Apache-2.0 |
| Spring Data JPA | 3.4.5 | JPA Repository 与事务数据访问 | Apache-2.0 |
| Spring Data Redis | 3.4.5 | 报价和热点订单状态缓存 | Apache-2.0 |
| MySQL Community Server | `mysql:8.4` | 保存报价快照、订单、状态和幂等键 | GPL-2.0 |
| MySQL Connector/J | 9.1.0 | Spring Boot 访问 MySQL 的 JDBC 驱动 | GPL-2.0 with Universal FOSS Exception |
| Flyway | 10.20.1 | MySQL 数据库版本迁移 | Apache-2.0 |
| Apache RocketMQ | `apache/rocketmq:5.5.0` | 订单事件、延迟派单、异步通知、重试和死信队列 | Apache-2.0 |
| RocketMQ Spring | 2.3.5 | Spring Boot 生产者、顺序/延迟发送和消息监听器 | Apache-2.0 |
| Redis | `redis:7.4-alpine` | 报价 TTL、热点订单状态和 LibreChat 运行缓存 | RSALv2 或 SSPLv1，非 OSI 认可的开源许可证 |
| Milvus | `milvusdb/milvus:v2.5.12` | 地点别名、车型说明、规则和政策的向量检索 | Apache-2.0 |
| etcd | `quay.io/coreos/etcd:v3.5.18` | Milvus 元数据协调组件 | Apache-2.0 |
| MinIO | `minio/minio:RELEASE.2025-05-24T17-08-30Z` | Milvus 对象存储组件 | AGPL-3.0 |
| MapLibre GL JS | 5.6.0 | 前端地图、路线和车辆位置展示 | BSD-3-Clause |
| PyMilvus | 2.5.12 | Python 访问 Milvus | Apache-2.0 |
| sentence-transformers | 4.1.0 | 生成知识库文本向量并运行 CrossEncoder 精排模型 | Apache-2.0 |
| jieba | 0.42.1 | 中文关键词分词 | MIT License |
| rank-bm25 | 0.2.2 | BM25 关键词召回 | Apache-2.0 |
| mmarco-mMiniLMv2-L12-H384-v1 | Hugging Face 模型 | 多语言候选片段相关性精排 | Apache-2.0 |
| FastAPI | 0.116.1 | 提供 RAG 检索 HTTP API | MIT License |
| Uvicorn | 0.35.0 | 运行 RAG ASGI 服务 | BSD-3-Clause |

### Redis 授权说明

当前编排使用 Redis 7.4。该版本采用 RSALv2/SSPLv1 双重授权，不应把当前技术栈笼统描述为“全部采用 OSI 开源软件”。如需完全采用 OSI 认可许可证的技术栈，可替换为采用 BSD-3-Clause 的 Valkey，并完成兼容性测试。

### MinIO 授权说明

当前项目固定使用 2025 年发布的 MinIO 镜像作为 Milvus 对象存储。MinIO 使用 AGPL-3.0，公开部署或分发修改版本前应核对相应义务。生产环境可以根据授权策略替换为其他兼容 S3 的对象存储。

## 七、由 LibreChat 继承的依赖

LibreChat 自身还包含大量前端、Node.js 服务端及基础设施依赖，本项目没有逐一重新声明。主要包括 React、TypeScript、Node.js、MongoDB 和 Meilisearch。完整、精确的传递依赖版本应以检出的 LibreChat `package.json`、锁文件、Docker Compose 文件和上游许可证清单为准。

MongoDB 默认使用 SSPL-1.0，属于源码可用授权。发布前应对实际构建产物生成 SBOM 和第三方许可证清单。

## 八、地图数据与外部服务

嘻嘻出行演示界面使用 OpenStreetMap 地图底图和地理数据：

- 项目网站：https://www.openstreetmap.org/
- 数据许可证：Open Database License（ODbL）
- 界面保留 OpenStreetMap 署名。

直接使用公共瓦片服务时还应遵守其 Tile Usage Policy。生产环境不应把公共瓦片服务视为无配额、无保障的商业地图 CDN。OpenStreetMap 是数据和服务来源，不是被复制进项目仓库的业务源码。

## 九、发布前合规建议

1. 保留 LibreChat 的 MIT `LICENSE` 和原作者版权声明。
2. 在发行包中附带本说明和第三方许可证清单。
3. 保留地图界面的 OpenStreetMap 署名。
4. 不把 Namma Yatri 描述为源码依赖或复制代码；应准确表述为“业务流程参考”。
5. 对 Redis 7.4、MongoDB 和 MinIO 的授权单独评估；如需严格的 OSI 开源技术栈，优先替换相应组件。
6. 发布前使用 Maven、npm 和容器镜像扫描工具生成实际依赖树、SBOM、漏洞报告及许可证报告。
7. 若新增复制自其他项目的代码，在源码文件头或 `NOTICE` 中记录来源、提交版本、修改内容和许可证。

## 十、结论

嘻嘻出行以 LibreChat 为直接 Fork 基线，以 Namma Yatri 作为业务流程参考；出行业务后端、MCP 工具、订单状态机、MySQL 持久化适配、RocketMQ Outbox 与消费链路、地图与报价组件以及 RAG 知识检索服务均为自行开发。运行时同时依赖 Spring、MySQL、RocketMQ、Milvus、MapLibre 等第三方项目，并包含 Redis、MongoDB、MinIO 等需要额外关注授权条件的组件。

本文是工程归属和开源依赖说明，不构成法律意见。
