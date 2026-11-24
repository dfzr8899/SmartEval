import io
import json
import os
import tempfile
import unittest
from unittest.mock import AsyncMock, patch
from uuid import uuid4
from zipfile import ZipFile

import httpx
import pymupdf
from fastapi.testclient import TestClient
from PIL import Image

from smarteval_ai import agents, documents, grading
from smarteval_ai.assets import Assets
from smarteval_ai.main import app
from smarteval_ai.vision import image_page


class GradingTest(unittest.TestCase):
    def setUp(self):
        self.files = tempfile.TemporaryDirectory()
        self.addCleanup(self.files.cleanup)
        self.local = Assets(self.files.name)
        self.client = TestClient(app)
        self.addCleanup(self.client.close)
        self.workspace = uuid4()
        self.secret = "test-grading-not-a-deployment-service-token"
        environment = patch.dict(os.environ, {"SMARTEVAL_INTERNAL_TOKEN": self.secret})
        environment.start()
        self.addCleanup(environment.stop)
        self.headers = {"Authorization": "Bearer " + self.secret}

    def prepare(self, material, questions=(), config=None):
        data, file_format, submission = material
        reference = self.local.write_bytes(self.workspace,data,"GRADING",file_format)
        reference.update(format=file_format, name="answer."+file_format, role="ANSWER", submission_id=submission)
        with patch.object(grading,"store",return_value=self.local):
            return self.client.post("/internal/v2/grading/prepare",headers=self.headers,json={
                "workspace":str(self.workspace), "config":config or {},
                "input":{"questions":list(questions), "submissions":[submission], "materials":[reference]},
            })

    def test_pdf_cross_page_source_and_rubric(self):
        with pymupdf.open() as doc:
            doc.new_page().insert_text((55,55),"1. Explain the water cycle. Water evaporates.")
            doc.new_page().insert_text((55,55),"It condenses and becomes rain.")
            raw=doc.tobytes()
        identify = {"questions":[{
            "id":"1", "prompt":"Explain the water cycle.", "kind":"TEXT", "source_ids":["b1"],
            "answers":[{"submission_id":"copy-1","text":"Water evaporates. It condenses and becomes rain.","source_ids":["b1","b2"]}],
        }]}
        standard = {"standards":[{"id":"1","max_score":5,
                                  "rubric":[{"id":"stages","points":5,"description":"Explains three stages."}]}]}
        call = AsyncMock(side_effect=[(identify,[]),(standard,[])])
        with patch.object(grading,"complete",call), patch.object(agents,"role_models",return_value={
                "QuestionReader": {}, "RubricDesigner": {}}):
            response=self.prepare((raw,"pdf","copy-1"))
        self.assertEqual(response.status_code,200,response.text)
        result=response.json()
        self.assertEqual([s["page"] for s in result["sources"]],[1,2])
        question=result["draft"]["questions"][0]
        self.assertEqual(question["answers"][0]["source_ids"], ["b1","b2"])
        self.assertEqual(question["rubric"][0]["points"],5)
        rubric_payload = str(call.await_args_list[1].args[3])
        self.assertNotIn("It condenses",rubric_payload)
        asset = self.local.write_bytes(self.workspace,raw,"GRADING","pdf")
        with patch.object(grading,"store",return_value=self.local):
            preview = self.client.post("/internal/v2/grading/preview",headers=self.headers,json={
                "workspace":str(self.workspace), "page":2, **asset,
            })
        self.assertEqual(preview.status_code,200,preview.text)
        self.assertTrue(preview.json()["jpeg"].startswith("/9j/"))

    def test_word_tables_and_source_file(self):
        with io.BytesIO() as data:
            with ZipFile(data,"w") as archive:
                archive.writestr("word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
                      <w:body><w:p><w:r><w:t>题目说明</w:t></w:r></w:p>
                       <w:tbl><w:tr><w:tc><w:p><w:r><w:t>表格作答</w:t></w:r></w:p></w:tc></w:tr></w:tbl>
                      </w:body>
                    </w:document>""")
            docx = data.getvalue()
        self.assertEqual([s["text"] for s in documents.blocks(docx,"docx")],["题目说明","表格作答"])
        response=self.prepare((docx,"docx","word-copy"))
        self.assertEqual(response.status_code,200,response.text)
        self.assertEqual(response.json()["sources"][1]["paragraph"],2)
        response=self.prepare((b"print(2)\n","py","code-copy"))
        self.assertEqual(response.status_code,200,response.text)
        question=response.json()["draft"]["questions"][0]
        self.assertEqual(question["kind"],"CODE")
        self.assertEqual(question["answers"][0]["language"],"PYTHON")
        self.assertEqual(question["answers"][0]["text"],"print(2)\n")

    def test_scan_and_photograph_keep_handwritten_evidence_regions(self):
        fixture = [{"text":"手写答案 [?]", "region":{"x":0.2,"y":0.3,"width":0.4,"height":0.15}}]
        with io.BytesIO() as image:
            Image.new("RGB",(180,90),"white").save(image,"PNG")
            raw=image.getvalue()
        with pymupdf.open() as doc:
            page = doc.new_page(width=180,height=90)
            page.insert_image(page.rect,stream=raw)
            scan = doc.tobytes()
        for data, file_format in [(raw,"png"),(scan,"pdf")]:
            with self.subTest(file_format=file_format):
                rows=documents.blocks(data,file_format,lambda image: fixture)
                self.assertEqual(rows[0]["diagnostic"],"VERIFY_TRANSCRIPTION")
                self.assertEqual(rows[1]["text"],"手写答案 [?]")
                self.assertEqual(rows[1]["page"],1)
                self.assertAlmostEqual(rows[1]["region"]["width"],0.4)

    def test_reviewer_failure_preserves_suggestion_for_teacher(self):
        config={"engine_version":"fixture"}
        proposal={"criteria":[{"id":"one","points":2,"quote":"相关原文","reason":"部分正确"}]}
        call=AsyncMock(side_effect=[(proposal,[]),TimeoutError()])
        with patch.object(grading,"complete",call):
            response=self.client.post("/internal/v2/grading/score",headers=self.headers,
                                       json={"item":{"answer":{"text":"相关原文"}},"config":config})
        self.assertEqual(response.status_code,200)
        self.assertFalse(response.json()["approved"])
        self.assertEqual(response.json()["criteria"][0]["points"],2)
        self.assertEqual(response.json()["trace"]["review_error"],"REVIEW_AGENT_UNAVAILABLE")

    def test_evidence_reviewer_revision_and_veto_modes(self):
        config = {"engine_version": "fixture"}
        proposal = {"criteria": [{"id": "c1", "points": 2, "quote": "关键定义", "reason": "漏算补充说明"}]}
        revised = {
            "approved": True,
            "review_summary": "学生答卷已涵盖补充要点，修订为 4 分",
            "total_score": 4,
            "criteria": [{"id": "c1", "points": 4, "quote": "关键定义与补充", "reason": "覆盖全部要点"}],
        }
        item = {"rubric": [{"id": "c1", "points": 4, "description": "完整说明"}], "answer": {"text": "关键定义与补充"}}
        call = AsyncMock(side_effect=[(proposal, []), (revised, [])])
        with patch.object(grading, "complete", call):
            response = self.client.post("/internal/v2/grading/score", headers=self.headers, json={"item": item, "config": config})
        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertTrue(body["approved"])
        self.assertTrue(body["reviewer_revised"])
        self.assertEqual(body["criteria"][0]["points"], 4)
        self.assertEqual(body["initial_criteria"][0]["points"], 2)
        self.assertEqual(body["declared_total_score"], 4)
        self.assertEqual(body["review_summary"], "学生答卷已涵盖补充要点，修订为 4 分")
        self.assertEqual(body["trace"]["review_mode"], "revise")

        veto_call = AsyncMock(side_effect=[(proposal, []), ({"approved": False}, [])])
        with patch.dict(os.environ, {"SMARTEVAL_REVIEW_MODE": "veto"}), patch.object(grading, "complete", veto_call):
            veto_resp = self.client.post("/internal/v2/grading/score", headers=self.headers, json={"item": item, "config": config})
        self.assertEqual(veto_resp.status_code, 200)
        veto_body = veto_resp.json()
        self.assertFalse(veto_body["approved"])
        self.assertFalse(veto_body["reviewer_revised"])
        self.assertEqual(veto_body["criteria"][0]["points"], 2)
        self.assertEqual(veto_body["trace"]["review_mode"], "veto")
        self.assertEqual(veto_call.await_args_list[1].kwargs.get("schema_name"), "EvidenceReviewerVeto")

    def test_internal_endpoints_require_service_credentials(self):
        response=self.client.post("/internal/v2/grading/score",json={"item":{},"config":{}})
        self.assertEqual(response.status_code,401)

    def test_role_endpoint_requires_its_own_key_and_can_fall_back_to_default_model(self):
        with patch.dict(os.environ, {"SMARTEVAL_MODEL_NAME": "default", "SMARTEVAL_MODEL_API_KEY": "default-key",
                "SMARTEVAL_MODEL_BASE_URL": "https://default.example/v1", "SMARTEVAL_MODEL_RESPONSE_FORMAT": "json_schema",
                "SMARTEVAL_GRADER_BASE_URL": "https://grader.example/v1"}, clear=True):
            self.assertEqual(agents.Model.configured("EvidenceReviewer").response_format, "json_schema")
            self.assertNotIn("Grader", agents.role_models())
            with patch.dict(os.environ, {"SMARTEVAL_GRADER_API_KEY": "separate-key"}):
                grader = agents.Model.configured("Grader")
                self.assertEqual((grader.name, grader.key, grader.response_format), ("default", "separate-key", "text"))
                self.assertNotIn("separate-key", str(grader.pin))

    def test_health_checks_actual_filesystem_and_reports_unconfigured_model(self):
        with patch.dict(os.environ, {}, clear=True), patch("smarteval_ai.main.store", return_value=self.local):
            live = self.client.get("/live")
            self.assertEqual(live.status_code, 204)
            resp = self.client.get("/health")
            self.assertEqual(resp.status_code, 503)
            health = resp.json()
        self.assertEqual(health["status"], "degraded")
        self.assertIn("DEFAULT_MODEL_NOT_CONFIGURED", health["reasons"])
        self.assertEqual(health["object_storage"], {"backend": "filesystem", "write": True, "read": True, "cleanup": True})
        self.assertEqual(list((self.local.root / "health").iterdir()), [])

    def test_large_phone_photo_and_preview_format_supported(self):
        with io.BytesIO() as buf:
            Image.new("RGB", (4032, 3024), "white").save(buf, "JPEG", quality=50)
            raw = buf.getvalue()
        fixture = [{"text": "12MP 答卷", "region": {"x": 0.1, "y": 0.1, "width": 0.5, "height": 0.2}}]
        rows = documents.blocks(raw, "jpg", lambda img: fixture)
        self.assertEqual(rows[1]["text"], "12MP 答卷")
        asset = self.local.write_bytes(self.workspace, raw, "GRADING", "jpg")
        with patch.object(grading, "store", return_value=self.local):
            preview = self.client.post("/internal/v2/grading/preview", headers=self.headers, json={
                "workspace": str(self.workspace), "page": 1, "format": "jpg", **asset,
            })
        self.assertEqual(preview.status_code, 200)
        self.assertTrue(preview.json()["jpeg"].startswith("/9j/"))

    def test_docx_absolute_image_target_and_tab_preserved(self):
        with io.BytesIO() as img_buf:
            Image.new("RGB", (120, 60), "white").save(img_buf, "PNG")
            png = img_buf.getvalue()
        with io.BytesIO() as data:
            with ZipFile(data, "w") as archive:
                archive.writestr("word/document.xml", """
                    <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
                                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
                                xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
                      <w:body><w:p><w:r><w:t>列1</w:t><w:tab/><w:t>列2</w:t></w:r><a:blip r:embed="rId1"/></w:p></w:body>
                    </w:document>""")
                archive.writestr("word/_rels/document.xml.rels", """
                    <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
                      <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="/word/media/image1.png"/>
                    </Relationships>""")
                archive.writestr("word/media/image1.png", png)
            docx = data.getvalue()
        rows = documents.blocks(docx, "docx", lambda img: [{"text": "插图文字", "region": {"x": 0, "y": 0, "width": 1, "height": 1}}])
        self.assertEqual(rows[0]["text"], "列1\t列2")
        self.assertTrue(any(r.get("text") == "插图文字" for r in rows))

    def test_parse_object_rubric_sync_language_and_cjk_join(self):
        self.assertEqual(agents.parse_object("Here is the JSON:\n```\n{\"ok\":true}\n```"), {"ok": True})
        self.assertEqual(agents.parse_object("```JSON\n{\"ok\":1}\n```"), {"ok": 1})
        with patch.dict(os.environ, {
            "SMARTEVAL_MODEL_BASE_URL": "https://api.example/v1",
            "SMARTEVAL_MODEL_API_KEY": "k",
            "SMARTEVAL_MODEL_NAME": "m",
            "SMARTEVAL_MODEL_TOKEN_LIMIT_FIELD": "max_completion_tokens",
            "SMARTEVAL_MODEL_EXTRA_BODY_JSON": '{"temperature":0}',
            "SMARTEVAL_VISION_MODEL": "v",
        }, clear=True):
            vis = agents.Model.vision()
            self.assertEqual(vis.token_limit_field, "max_completion_tokens")
            self.assertEqual(vis.extra_body, {"temperature": 0})
        from smarteval_ai.vision import _join_ocr_tokens
        self.assertEqual(_join_ocr_tokens(["水", "循", "环", "H2O", "cycle"]), "水循环 H2O cycle")

        q = {"id": "1", "kind": "TEXT", "prompt": "简述原理", "max_score": 10}
        grading.apply_standard(q, {
            "id": "1", "max_score": 20, "accepted": ["should-not-copy"],
            "rubric": [{"id": "a", "description": "要点一", "points": 8}, {"id": "b", "description": "要点二", "points": 12}],
        })
        self.assertEqual(q["max_score"], 20)
        self.assertNotIn("accepted", q)
        self.assertEqual(grading.normalize_language("c++"), "CPP")
        self.assertEqual(grading.normalize_language("python"), "PYTHON")


class LlmClientTest(unittest.IsolatedAsyncioTestCase):
    async def test_chat_completion_formats_and_usage(self):
        sdk = agents.AsyncOpenAI
        requests = []

        def provider(request):
            requests.append(json.loads(request.content))
            return httpx.Response(200, json={
                "id": "fixture", "created": 0, "object": "chat.completion", "model": "resolved-model",
                "choices": [{"index": 0, "finish_reason": "stop", "message": {
                    "role": "assistant", "content": '{"approved":true}',
                }}],
                "usage": {"prompt_tokens": 12, "completion_tokens": 4, "total_tokens": 16},
            })

        def client(**options):
            return sdk(**options, http_client=httpx.AsyncClient(transport=httpx.MockTransport(provider)))

        for mode in ("text", "json_object", "json_schema"):
            with self.subTest(mode=mode), patch.object(agents, "AsyncOpenAI", client):
                model = agents.Model("https://compatible.example/v1", "fixture-key", "configured-model", mode,
                                     "max_completion_tokens", {"thinking": {"type": "disabled"}})
                llm = agents.LlmClient(model)
                output = await llm.complete("EvidenceReviewer", "Review the grade.", {"score": 3}, 80, 3)
                self.assertEqual(agents.parse_object(output), {"approved": True})
                sent = requests[-1]
                self.assertEqual(sent["model"], "configured-model")
                self.assertEqual(sent["max_completion_tokens"], 80)
                self.assertEqual(sent["thinking"], {"type": "disabled"})
                response_format = sent.get("response_format", {})
                self.assertEqual(response_format.get("type"), None if mode == "text" else mode)
                if mode == "json_schema":
                    self.assertEqual(response_format["json_schema"]["schema"], agents.SCHEMAS["EvidenceReviewer"])
                usage, = llm.usage
                self.assertEqual((usage["model"], usage["input_tokens"], usage["output_tokens"]), ("resolved-model", 12, 4))
                self.assertGreaterEqual(usage["latency_ms"], 0)


if __name__ == "__main__":
    unittest.main()
