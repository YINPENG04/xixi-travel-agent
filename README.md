# 嘻嘻出行（Xixi Travel）

一句话，轻松出发。

嘻嘻出行是面向打车、路线规划和行程服务场景的智能出行 Agent。用户可以用自然语言说明目的地或出行需求，由 Agent 完成路线理解、车型报价、订单确认和行程状态查询。

> 嘻嘻出行的产品形态可类比滴滴“AI 小滴”，但属于独立开发项目，与滴滴及“AI 小滴”不存在隶属、授权或合作关系。

## 在线体验

- 公开演示：[https://xixi-travel-agent.yinp05838.chatgpt.site](https://xixi-travel-agent.yinp05838.chatgpt.site)
- 源码仓库：[https://github.com/YINPENG04/xixi-travel-agent](https://github.com/YINPENG04/xixi-travel-agent)

## 当前能力

- 中文智能出行工作台：目的地输入、快捷地点、地图路线、车型报价和司机状态。
- MapLibre GL JS 地图与 OpenStreetMap 底图，保留数据署名。
- Spring Boot 报价和订单 API，包含五分钟报价有效期、用户隔离、幂等下单和显式订单状态机。
- Spring AI MCP 工具：`rideQuote`、`rideCreate`、`rideStatus` 和 `rideCancel`。
- Milvus 知识库初始化脚本及地点、车型、规则和政策样例。
- PostgreSQL、Redis、Milvus、etcd、MinIO、MongoDB 和 Meilisearch 的本地容器编排。
- LibreChat 固定基线检出脚本与嘻嘻出行 MCP 配置。

## 项目结构

```text
app/                         可部署的嘻嘻出行 React/MapLibre 体验
services/ride-service/       Spring Boot 出行业务与 MCP 服务
knowledge/                   Milvus 初始化和知识数据
scripts/                     上游基线初始化脚本
docker-compose.xixi.yml      本地基础设施与业务服务编排
librechat.xixi.yaml          LibreChat Agent/MCP 配置
OPEN_SOURCE_USAGE.md         代码归属与开源依赖说明
```

## 三分钟本地体验

本地界面无需 ChatGPT 账号、地图密钥或后端服务。安装 Git 和 Node.js 22.13 或更高版本后执行：

```bash
git clone https://github.com/YINPENG04/xixi-travel-agent.git
cd xixi-travel-agent
npm install
npm run dev
```

打开 `http://localhost:3000`。Windows、macOS 和 Linux 均可运行。

行程和发票演示数据保存在当前浏览器的 `localStorage` 中，不会上传到作者服务器；不同浏览器或设备的数据彼此独立。清除浏览器站点数据即可重置演示。

## 完整服务本地运行

### 1. 单独运行出行界面

要求 Node.js 22.13 或更高版本。

```bash
npm install
npm run dev
```

打开 `http://localhost:3000`。

### 2. 运行 Spring Boot 服务

要求 JDK 21 或更高版本、Maven 3.9。

```bash
cd services/ride-service
mvn test
mvn spring-boot:run
```

健康检查地址为 `http://localhost:8081/actuator/health`。

### 3. 检出 LibreChat 固定基线

```powershell
.\scripts\bootstrap-librechat.ps1
```

脚本会把 LibreChat 的 `8e5ef1fb31e9d63b735c089b21cbc82c50acce46` 提交检出到 `vendor/LibreChat/`。该目录保留上游 Git 历史和 MIT 许可证。

### 4. 启动完整依赖

复制 `.env.example` 为 `.env`，修改示例密码后执行：

```bash
docker compose -f docker-compose.xixi.yml up -d
```

如需同时启动 LibreChat：

```bash
docker compose -f docker-compose.xixi.yml --profile librechat up -d
```

启用 LibreChat 后打开 `http://localhost:3080`，可在本机注册独立账号并使用对话、会话和 MCP 工具框架。该账号及会话数据保存在本地 MongoDB 容器中，与在线演示站点互不相通。

## 验证

提交代码前可运行：

```bash
npm test
```

该命令会构建前端并检查首页、实时钟表、行程和发票等关键功能。

## API 示例

询价：

```bash
curl -X POST http://localhost:8081/api/v1/quotes \
  -H "Content-Type: application/json" \
  -d '{"origin":"故宫博物院","destination":"北京南站","distanceKilometers":12.6,"durationMinutes":28}'
```

创建订单时必须携带用户 ID 和幂等键：

```bash
curl -X POST http://localhost:8081/api/v1/rides \
  -H "Content-Type: application/json" \
  -H "X-Xixi-User: demo-user" \
  -H "Idempotency-Key: demo-order-001" \
  -d '{"quoteId":"Q-XXXXXXXX","origin":"故宫博物院","destination":"北京南站"}'
```

## 重要边界

- 这是可演示的 MVP，不包含真实车辆调度、支付、短信、实名认证或生产级风控。
- 创建和取消订单属于重要操作。Agent 在调用相应 MCP 工具前必须取得用户明确确认。
- 默认订单仓储为内存实现，便于本地验证；生产环境应接入 PostgreSQL 并把幂等键和报价有效期状态迁移到持久层与 Redis。
- 公共 OpenStreetMap 瓦片服务不应作为无配额、无保障的商业地图 CDN。

开源归属、许可证和分发注意事项见 [OPEN_SOURCE_USAGE.md](./OPEN_SOURCE_USAGE.md)。
