# SmartEval

SmartEval 是开源、可自部署的**证据锚定式智能作业批改与核验平台**，支持结构化作业、PDF、Word、手写答卷照片以及五语言源码（Python、Java、C、C++、JavaScript）。

核心设计采用**双阶段语义评审 + Java 确定性程序核验**架构：初评角色按教师确认的评分细则逐项打分并摘录学生原话，独立复核角色审查证据支撑并修订存疑分项，最终由确定性核验引擎校验细则双射覆盖、整数边界、声明总分守恒以及跨平台换行对齐的 UTF-16 原文锚点；自动放行集合保证零伪造逐字引文，存在风险的答卷连同结构化风险标签转交教师终审。

~~~mermaid
flowchart LR
    Teacher[教师工作台 / 收卷端] --> Gateway[Vue 3 / Nginx]
    CF[可选 Cloudflare Tunnel] --> Gateway
    Agent[外部 AI Agent] -->|stdio 或 Streamable HTTP| MCP[SmartEval MCP]
    MCP -->|公开 API v2 / X-API-Key| Gateway
    Gateway --> Business[Java 21 Business / Worker]
    Business --> MySQL[(MySQL 8.4 / Flyway)]
    Business --> Storage[(文件卷或 S3)]
    Business --> Intelligence[Python Intelligence]
    Intelligence --> Storage
    Intelligence --> AST[Tree-sitter 五语言 AST 查重]
    Intelligence --> Model[OpenAI 兼容模型 / BYOK]
    Business --> Judge[内置五语言 Judge 沙箱 / 外部 Judge0 CE]
    Business -->|HMAC 签名事件| Receiver[Webhook 接收端]
~~~

## 核心能力

- **主观题双阶段评审与确定性原文锚定**：Grader 分项评阅 + EvidenceReviewer 独立修订复核（支持 `revise` / `veto` 协议）+ Java 引擎校验细则双射、总分守恒与 UTF-16 字符区间定位，支持 PDF/图片原件区域高亮溯源。
- **教师自备模型密钥（BYOK）**：每位教师可在工作台顶部「模型与 API Key」自行填入 DeepSeek、通义千问、硅基流动、智谱、OpenAI 等兼容接口密钥（服务端 AES-256-GCM 加密存储、仅脱敏回显、内置连通性测试与 SSRF 防护），宿主机可不配置公共 Key，实现零宿主机模型账单。
- **五语言代码沙箱判题与 AST 结构查重**：内置 Judge0 协议兼容的五语言隔离沙箱容器（Python 3 / Java / C / C++17 / Node.js，无外网、只读根文件系统、非 root、超时与资源硬限流），同步支持接入外部 Judge0 CE；判分后异步执行 Tree-sitter 代码结构相似度分析。
- **收卷、学情看板与多角色治理**：支持免登录分享收卷链接、ZIP 批量导卷、班级与单任务学情统计面板、脱敏评测集一键导出、UTF-8 成绩单导出、成员角色权限治理与操作审计日志追溯。
- **开放 API 与 MCP 协议**：全部 57 个业务接口通过 OpenAPI 3.1 自动化契约一致性测试，提供 HMAC-SHA256 签名 Webhook 与 MCP Server（stdio / Streamable HTTP）。

## 3 分钟配置并启动

需要 Docker 和 Compose v2。首次拉取镜像和构建的耗时取决于网络与设备。

1. Linux/macOS 执行 `cp .env.example .env`，Windows PowerShell 执行 `Copy-Item .env.example .env`。设置 `SMARTEVAL_DATABASE_PASSWORD`、彼此独立且至少 32 字节的 `SMARTEVAL_SESSION_SECRET` 与 `SMARTEVAL_INTERNAL_TOKEN`，再设定初始管理员 `SMARTEVAL_BOOTSTRAP_USER` 和至少 12 字符的 `SMARTEVAL_BOOTSTRAP_PASSWORD`。
2. **模型配置（二选一）**：
   - **推荐（BYOK 模式）**：留空 `.env` 中的 `SMARTEVAL_MODEL_API_KEY`，登录工作台后点击顶部 **「配置 API Key」** 由每位教师填入自己的密钥并测试连通性；
   - **服务端全局默认**：在 `.env` 中配置 `SMARTEVAL_MODEL_BASE_URL`、`SMARTEVAL_MODEL_NAME`、`SMARTEVAL_MODEL_API_KEY`。
3. 执行 `docker compose up -d --build` 和 `docker compose ps`。打开 [教师工作台](http://localhost:8088)，使用初始管理员登录即可开始使用客观题、五语言编程题及主观题批改。首次登录修改密码后，建议从 `.env` 清除 `SMARTEVAL_BOOTSTRAP_PASSWORD`。

### 通过 Cloudflare Tunnel 一键公网发布（可选）

无需公网 IP 或路由器端口映射，在 Cloudflare Zero Trust 创建 Tunnel 并将 Public Hostname 指向 `http://web:80`，在 `.env` 中配置以下三项：

~~~env
SMARTEVAL_PUBLIC_ORIGIN=https://your-domain.example.com
SMARTEVAL_SECURE_COOKIES=true
CLOUDFLARED_TOKEN=<你的 Tunnel Token>
~~~

执行以下命令即可随主服务一起启动隧道容器（Nginx 已内置 `CF-Connecting-IP` 真实访客 IP 限流透传与 `X-Forwarded-Proto` 转发）：

~~~bash
docker compose --profile cloudflare up -d --build
~~~

### 主观题模型参考配置

| 提供方 | Base URL | 示例模型 | 响应格式 / Token 字段 |
| --- | --- | --- | --- |
| DeepSeek | `https://api.deepseek.com` | `deepseek-chat` / `deepseek-flash` | `json_object` / `max_tokens` |
| 通义千问 DashScope | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-plus` | `json_object` / `max_tokens` |
| 硅基流动 SiliconFlow | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-72B-Instruct` | `json_object` / `max_tokens` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` | `json_schema` / `max_completion_tokens` |
| 本地 Ollama | `http://host.docker.internal:11434/v1` | 本机已安装标签 | `json_object` / `max_tokens` |

也可在 `.env` 中按角色（识题、细则设计、评分、复核、代码分析）配置不同模型。扫描件默认使用容器内置 Tesseract OCR（中英双语），也可额外配置视觉模型 `SMARTEVAL_VISION_MODEL`。

### 编程题沙箱与源文件存储

`compose.yaml` 默认内置 `judge` 五语言隔离沙箱（自动映射 `CPP=54,JAVA=62,PYTHON=71,C=50,JAVASCRIPT=63`），开箱即用；如需切换为独立部署的外部 Judge0 CE 节点，只需在 `.env` 填入 `SMARTEVAL_JUDGE_URL`、`SMARTEVAL_JUDGE_TOKEN` 与 `SMARTEVAL_JUDGE_LANGUAGES`，并用 [Judge0 连通检查](scripts/check-judge.ps1) 验证。

同题、同语言的非空源码在判分结束后异步比较 AST 结构相似度，默认告警阈值 `0.60`（`SMARTEVAL_SIMILARITY_THRESHOLD`），达到 `0.80` 标为 `HIGH`，其余达阈值标为 `MEDIUM`。源文件默认存入共享卷，也可通过 `SMARTEVAL_S3_*` 切换至 S3 兼容对象存储。

### 作业收卷与批量导入

在教师工作台「作业收卷」创建并分享免登录交卷链接，学生可直接提交文本及最多 5 个附件；教师可开关收卷、退回错误答卷并一键转为正式批改草案。已有文件可在「新建批改」批量导入 ZIP（压缩包上限 100 MiB，解压合计最多 200 MiB，单任务最多 100 份答卷与 1000 个判分项）。

## 开发者接入

### 30 秒提交批改

在教师工作台「API 与 MCP」创建密钥，在 shell 中设置为 `SMARTEVAL_API_KEY`（重复发送相同请求时复用 `Idempotency-Key`，提交新作业时换新值）：

~~~bash
BASE=http://localhost:8088
curl --fail-with-body "$BASE/api/v2/grading-jobs" \
  -H "X-API-Key: $SMARTEVAL_API_KEY" \
  -H 'Idempotency-Key: quickstart-001' \
  -H 'Content-Type: application/json' \
  --data '{"title":"快速体验","auto_confirm":true,"questions":[{"id":"q1","kind":"EXACT","prompt":"2+3 等于多少？","max_score":5,"accepted":["5"],"answers":[{"submission_id":"a","text":"5"}]}]}'
~~~

将返回的 `id` 设为 `JOB_ID`，即可轮询状态、获取核验报告或导出带 UTF-8 BOM 的 CSV 成绩单：

~~~bash
curl -fsS -H "X-API-Key: $SMARTEVAL_API_KEY" "$BASE/api/v2/grading-jobs/$JOB_ID"
curl -fsS -H "X-API-Key: $SMARTEVAL_API_KEY" "$BASE/api/v2/grading-jobs/$JOB_ID/report"
curl -fsS -H "X-API-Key: $SMARTEVAL_API_KEY" "$BASE/api/v2/grading-jobs/$JOB_ID/export?format=csv" -o grades.csv
~~~

完整接口定义见 [OpenAPI 3.1 契约](contracts/openapi/grading-v2.yaml) 与 [API 接入手册](docs/API-v2-接入.md)。

### Webhook HMAC-SHA256 验签

创建任务时附带 `webhook_url`，保存响应中的 `webhook_secret`；对原始请求体计算 HMAC 验签，并基于 `X-SmartEval-Delivery` 做幂等处理：

~~~python
import hashlib
import hmac

def verify_webhook(body: bytes, signature: str, webhook_secret: str) -> bool:
    digest = hmac.new(webhook_secret.encode("utf-8"), body, hashlib.sha256).hexdigest()
    return signature.isascii() and hmac.compare_digest(signature, "sha256=" + digest)
~~~

### 挂载 MCP Server

Compose 默认通过 Nginx 在 `/mcp` 提供 Streamable HTTP，客户端每次请求附加自己的 `X-API-Key`。本地 stdio 接入可在独立 Python 3.11+ 虚拟环境安装 `python -m pip install ./services/mcp`，详见 [MCP 安装与工具说明](services/mcp/README.md)。

## 升级与许可证

从旧版升级前请先停止旧版 Business 实例，Flyway 会自动顺序应用 `V1–V7` 数据库迁移（含 `V5` 收卷、`V6` 可靠性与安全增强、`V7` 教师自备模型加密配置）。开发与回归测试命令见 [CONTRIBUTING.md](CONTRIBUTING.md)。本项目采用 [Apache-2.0](LICENSE) 开源许可证。
