import unittest
from unittest.mock import AsyncMock, patch

from pydantic import ValidationError
from smarteval_mcp import http as mcp_http
from smarteval_mcp import server


class McpToolsTest(unittest.IsolatedAsyncioTestCase):
    async def test_protocol_schema_lists_four_tools(self):
        listed = await server.server.list_tools()
        self.assertTrue(all(tool.outputSchema and tool.outputSchema["type"] == "object" for tool in listed))
        tools = {tool.name: tool.inputSchema for tool in listed}
        self.assertEqual(set(tools), {"submit_grading_job", "get_grading_status", "get_verified_report", "list_grading_templates"})
        questions = tools["submit_grading_job"]["properties"]["questions"]["items"]
        self.assertEqual(questions["discriminator"]["propertyName"], "kind")
        self.assertEqual(len(questions["oneOf"]), 3)

    async def test_submit_preserves_idempotency_key_and_exact_input(self):
        question = server.ExactQuestion(id="q1", kind="EXACT", prompt="2+3?", max_score=1, accepted=["5"],
                                        answers=[server.Answer(submission_id="a", text="5")])
        with patch.object(server, "request", new_callable=AsyncMock) as api:
            api.return_value = {"status": "QUEUED", "run": 1, "counts": {"QUEUED": 1}}
            output = await server.submit_grading_job("作业", [question], idempotency_key="retry-example")
            self.assertEqual(api.await_args.args, ("POST", "/grading-jobs"))
            self.assertEqual(api.await_args.kwargs["headers"], {"Idempotency-Key": "retry-example"})
            self.assertTrue(api.await_args.kwargs["body"]["auto_confirm"])
            self.assertEqual(api.await_args.kwargs["body"]["questions"][0]["accepted"], ["5"])
            self.assertEqual(output["counts"], {"QUEUED": 1})
            self.assertFalse(output["terminal"])

    def test_report_preserves_verified_evidence_and_alerts(self):
        evidence = {"quote": "原文", "start": 2, "end": 4, "source_ids": ["b1"]}
        finding = {"quote": "return x", "reason": "代码点评", "line": 2}
        alert = {"score": 1.0, "left_submission_id": "a", "right_submission_id": "b"}
        report = server.compact_report({
            "job_id": "example", "run": 1, "latest_run": 1, "submissions": [],
            "items": [{"answer": "完整答卷", "result": {"final_score": None, "criteria": [evidence], "findings": [finding]}}],
            "sources": [{"id": "b1", "page": 2, "region": {"x": 0.2}}, {"id": "b2"}],
            "similarity_status": "COMPLETED", "similarity_alerts": [alert],
        })
        self.assertEqual(report["items"][0]["criteria"], [evidence])
        self.assertEqual(report["items"][0]["findings"], [finding])
        self.assertIsNone(report["items"][0]["final_score"])
        self.assertNotIn("answer", report["items"][0])
        self.assertEqual(report["sources"], [{"id": "b1", "page": 2, "region": {"x": 0.2}}])
        self.assertEqual(report["similarity_alerts"], [alert])

    async def test_awaiting_confirmation_templates_and_prompt_byte_limit(self):
        status = server.status_view({"id": "j1", "status": "AWAITING_CONFIRMATION", "run": 0})
        self.assertTrue(status["terminal"])
        self.assertTrue(status["needs_teacher"])

        with patch.object(server, "request", new_callable=AsyncMock) as api:
            api.return_value = [{"id": "t1", "version": 1, "title": "模板", "questions": [{"id": "q1"}]}]
            result = await server.list_grading_templates()
            self.assertEqual(result["templates"][0]["questions"], [{"id": "q1"}])

        with self.assertRaises(ValidationError):
            server.ExactQuestion(id="q1", kind="EXACT", prompt="汉" * 6000, max_score=1, accepted=["5"],
                                 answers=[server.Answer(submission_id="a", text="5")])

    async def test_http_health_and_key_probe_middleware(self):
        statuses = []

        async def send(msg):
            if msg["type"] == "http.response.start":
                statuses.append(msg["status"])

        async def dummy_app(scope, receive, send_fn):
            await send_fn({"type": "http.response.start", "status": 200, "headers": []})
            await send_fn({"type": "http.response.body", "body": b""})

        middleware = mcp_http.RequestAuthentication(dummy_app)
        await middleware({"type": "http", "path": "/health", "headers": []}, None, send)
        self.assertEqual(statuses[-1], 204)

        mock_client = AsyncMock()
        mock_client.get.return_value = type("Resp", (), {"status_code": 204})()
        with patch.object(mcp_http, "shared_client", return_value=mock_client):
            key = b"a" * 43
            await middleware({"type": "http", "path": "/mcp", "headers": [(b"x-api-key", key)]}, None, send)
            self.assertEqual(statuses[-1], 200)
            mock_client.get.assert_awaited_once_with("/api/v2/auth/key", headers={"X-API-Key": "a" * 43}, timeout=5)
