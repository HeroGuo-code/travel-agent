# 智能旅游助手 · AgentScope 2.0

> 基于 AgentScope 2.0-Java 的多智能体差旅助手，复刻阿里商旅 AliGo 场景。双模型模式、快慢车道意图路由、内置 RAG、全链路可观测，开箱即用。

## 在线演示

🔗 **[travel.guohaoai.com](https://travel.guohaoai.com)**

## 功能特性

- **双模型模式** — 配置 API Key 走真模型，无 Key 自动降级确定性 Mock 剧本，离网可完整演示
- **快慢车道意图路由** — 固定快捷话术零延迟直达目标 Agent，自由输入由模型拆分意图后多 Agent 协作
- **内置 RAG** — 硬编码差旅政策语料 + 关键词打分检索，无向量库依赖
- **全链路可观测** — 每次对话生成 Trace Span 链路，支持火焰图、Mermaid 流程图、Prompt 快照
- **上线防护** — IP 限流（4 次/分）、日 token 预算（超限自动降级 Mock）、故障演练模式
- **SSE 心跳** — 5s 心跳事件，规避 Cloudflare 100s 超时

## 技术栈

| 层 | 技术 |
|---|---|
| Agent 框架 | AgentScope 2.0-Java |
| Web 框架 | Spring Boot 3.x |
| 模型 | OpenRouter (DeepSeek V4.1 Flash) / DashScope / Mock |
| 前端 | 原生 HTML + CSS + JS（SSE EventSource） |
| 部署 | systemd + Nginx + Cloudflare CDN |

## 快速开始

### 环境要求

- JDK 17+
- Maven 3.9+

### 构建与运行

```bash
mvn -q package -DskipTests
export OPENROUTER_API_KEY="sk-or-v1-..."   # 可选，无 Key 自动降级 Mock
java -Djava.net.preferIPv4Stack=true -jar target/aligo-travel-agent-1.0.0.jar
```

浏览器打开 http://localhost:8080

### 服务器部署

`deploy/` 目录提供一键部署脚本和构建产物：

```bash
# 1. 上传 deploy/ 下的 install.sh 和 jar 到服务器
# 2. 修改 install.sh 中的 OPENROUTER_API_KEY 为你自己的 Key
# 3. 执行
bash install.sh
```

脚本自动完成：部署 jar → 检测 Java → 写入环境变量 → 注册 systemd 服务（开机自启 + 崩溃重启）→ 配置 Nginx（含 SSE 防超时三件套）→ 健康检查。

## API 文档

| 接口 | 方法 | 说明 |
|---|---|---|
| `/api/chat/stream` | GET | SSE 流式对话主入口（`query`、`sessionId` 参数） |
| `/api/health` | GET | 健康检查 |
| `/api/debug/stats` | GET | 统计面板（P50/P95 延迟、车道比例、工具调用计数） |
| `/api/debug/traces` | GET | Trace 列表 |
| `/api/debug/trace/{id}` | GET | Trace 详情（Span 链路） |
| `/api/debug/trace/{id}/flow` | GET | Mermaid 流程图 |
| `/api/debug/prompts/{traceId}` | GET | Prompt 快照 |
| `/api/debug/memory/{sessionId}` | GET | 上下文记忆三表 |
| `/api/debug/provider` | GET | 模型 / Provider 信息 |

## 仓库结构

```
deploy/
  install.sh                      一键部署脚本
  aligo-travel-agent-1.0.0.jar    构建产物（Spring Boot Fat Jar）
README.md
```

## License

MIT
