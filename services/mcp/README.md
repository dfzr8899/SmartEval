# SmartEval MCP Server

SmartEval MCP 使用官方 Python SDK，通过 stdio 或 Streamable HTTP 提供四个工具。服务只调用 SmartEval 公开的 `/api/v2`。

## 安装

在仓库根目录创建 Python 3.11+ 虚拟环境，然后使用环境里的 Python 运行 `python -m pip install ./services/mcp`。从源码运行时，可在 `services/mcp` 内执行 `python -m pip install -r requirements.txt`，再执行 `python -m smarteval_mcp`。

从教师工作台的「API 接入」获取 API Key。将下面的 JSON 加入 Claude Desktop 的 `claude_desktop_config.json` 或 Cursor 的 `.cursor/mcp.json`。将命令改为实际虚拟环境内 Python 的绝对路径，Windows 示例为 `C:/path/to/.venv/Scripts/python.exe`。

~~~json
{
  "mcpServers": {
    "smarteval": {
      "command": "/absolute/path/to/.venv/bin/python",
      "args": ["-m", "smarteval_mcp"],
      "env": {
        "SMARTEVAL_API_URL": "http://127.0.0.1:8088",
        "SMARTEVAL_API_KEY": "替换为真实的 API Key"
      }
    }
  }
}
~~~

## 工具

| 工具 | 输入和输出 |
| --- | --- |
| `submit_grading_job` | 输入标题、题目数组及可选的 `auto_confirm`（默认 `true`）和 `idempotency_key`。`TEXT` 提供 `rubric`，`EXACT` 提供 `accepted`，`CODE` 提供 `tests` 与非空代码的 `language`。返回任务状态与 ID。 |
| `get_grading_status` | 输入 `job_id`。返回状态、各逐题状态计数、`terminal`、`needs_teacher` 和 `report_available`。待确认和待复核都需要教师处理。 |
| `get_verified_report` | 输入 `job_id` 与可选的 `run`。返回每题分数、评分理由、原文引用和定位、代码点评及相似度告警。等待查重时返回 `similarity_status=PENDING`，可以再次读取。 |
| `list_grading_templates` | 无参数。返回当前工作空间模板的编号、版本和名称。 |

每次提交需要 `grading:submit`，其余工具需要 `grading:read`。客户端可读取自己创建的任务和所在工作空间的模板。提交重试时复用原来的幂等键与请求内容。

## Streamable HTTP

Docker Compose 将 MCP 服务接在 Nginx 后，对外使用 `/mcp`。独立部署时运行 `smarteval-mcp-http`，或执行 `python -m smarteval_mcp.http`。使用 `SMARTEVAL_API_URL` 设置上游 Business 地址，监听地址和端口可通过 `SMARTEVAL_MCP_HOST` 和 `SMARTEVAL_MCP_PORT` 调整。

远程客户端在每个请求发送自己的 `X-API-Key`。服务通过 Business 验证密钥，并把同一个请求的密钥提供给该次工具调用。各工具的权限由 Business 检查。缺少有效密钥时返回 HTTP 401。若客户端发送 `Origin`，它须与部署配置的 `SMARTEVAL_PUBLIC_ORIGIN` 一致。

~~~json
{
  "mcpServers": {
    "smarteval": {
      "url": "https://smarteval.example.com/mcp",
      "headers": { "X-API-Key": "替换为自己的 API Key" }
    }
  }
}
~~~

## 验证

~~~bash
python -m unittest discover -s services/mcp/tests -v
python services/mcp/tests/stdio_roundtrip.py
~~~

第二条命令从当前进程读取 `SMARTEVAL_API_URL` 与 `SMARTEVAL_API_KEY`，会创建并完成一份客观题试验作业。输出包含真实工具参数和结构化返回。
