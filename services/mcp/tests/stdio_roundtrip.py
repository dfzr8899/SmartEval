"""Exercise the real MCP stdio protocol against a running public SmartEval API."""
import asyncio
import json
import os
import sys
from uuid import uuid4

from mcp import ClientSession, StdioServerParameters
from mcp.client.stdio import stdio_client


async def main():
    parameters = StdioServerParameters(command=sys.executable, args=["-m", "smarteval_mcp"], env=os.environ.copy())
    async with stdio_client(parameters) as (reader, writer), ClientSession(reader, writer) as client:
        await client.initialize()
        print(json.dumps({"tools": sorted(tool.name for tool in (await client.list_tools()).tools)}))

        async def call(name, arguments):
            response = await client.call_tool(name, arguments=arguments)
            if response.isError:
                raise RuntimeError(response.content)
            result = response.structuredContent
            if not isinstance(result, dict):
                raise RuntimeError(f"{name} returned no structured object: {response.content}")
            print(json.dumps({"tool": name, "arguments": arguments, "result": result}, ensure_ascii=False))
            return result

        job = await call("submit_grading_job", {
            "title": "MCP 实测", "auto_confirm": True, "idempotency_key": f"mcp-{uuid4()}",
            "questions": [{"id": "q1", "kind": "EXACT", "prompt": "2+3 等于多少？", "max_score": 5,
                           "accepted": ["5"], "answers": [{"submission_id": "答卷甲", "text": "5"}]}],
        })
        for _ in range(40):
            state = await call("get_grading_status", {"job_id": job["id"]})
            if state["terminal"]:
                break
            await asyncio.sleep(0.25)
        assert state["status"] == "COMPLETED"
        report = await call("get_verified_report", {"job_id": job["id"]})
        assert report["items"][0]["final_score"] == 5 and report["items"][0]["criteria"][0]["quote"] == "5"
        await call("list_grading_templates", {})


if __name__ == "__main__":
    asyncio.run(main())
