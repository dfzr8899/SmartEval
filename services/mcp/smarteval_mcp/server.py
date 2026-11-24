"""MCP stdio server backed exclusively by the SmartEval public HTTP API."""
import asyncio
import os
from contextvars import ContextVar
from typing import Annotated, Any, Literal
from uuid import UUID, uuid4

import httpx
from mcp.server.fastmcp import FastMCP
from mcp.server.transport_security import TransportSecuritySettings
from pydantic import BaseModel, Field, StringConstraints, field_validator

server = FastMCP("SmartEval", stateless_http=True, streamable_http_path="/mcp",
                 transport_security=TransportSecuritySettings(enable_dns_rebinding_protection=False))
request_key: ContextVar[str | None] = ContextVar("smarteval_request_key",default=None)
TERMINAL = frozenset(("AWAITING_CONFIRMATION", "COMPLETED", "REVIEW_REQUIRED", "PREPARATION_FAILED", "CANCELLED"))
TEACHER_ACTION = frozenset(("AWAITING_CONFIRMATION", "REVIEW_REQUIRED"))
Identifier = Annotated[str, StringConstraints(min_length=1, max_length=80)]
Points = Annotated[int, Field(ge=1, le=10_000)]
_clients: dict[tuple[asyncio.AbstractEventLoop, str], httpx.AsyncClient] = {}


class Answer(BaseModel):
    submission_id: str = Field(min_length=1, max_length=100)
    text: str = Field(max_length=24_000)


class CodeAnswer(Answer):
    language: Literal["PYTHON", "JAVA", "CPP", "C", "JAVASCRIPT"] | None = None


class Criterion(BaseModel):
    id: Identifier
    description: str = Field(min_length=1, max_length=2000)
    points: Points


class TestCase(BaseModel):
    id: Identifier
    input: str = Field(max_length=8192)
    expected: str = Field(max_length=8192)
    points: Points


class BaseQuestion(BaseModel):
    id: Identifier
    prompt: str = Field(min_length=1, max_length=16_000)
    max_score: Points

    @field_validator("prompt")
    @classmethod
    def prompt_byte_limit(cls, value: str) -> str:
        if not value.strip():
            raise ValueError("prompt must not be blank")
        if len(value.encode("utf-8")) > 16_000:
            raise ValueError("prompt exceeds 16000 UTF-8 bytes")
        return value


class TextQuestion(BaseQuestion):
    kind: Literal["TEXT"]
    rubric: list[Criterion] = Field(min_length=1, max_length=24)
    reference_answer: str | None = Field(default=None, max_length=16_000)
    answers: list[Answer] = Field(min_length=1, max_length=100)


class ExactQuestion(BaseQuestion):
    kind: Literal["EXACT"]
    accepted: list[Annotated[str, StringConstraints(min_length=1, max_length=2000)]] = Field(min_length=1, max_length=20)
    answers: list[Answer] = Field(min_length=1, max_length=100)


class CodeQuestion(BaseQuestion):
    kind: Literal["CODE"]
    tests: list[TestCase] = Field(min_length=1, max_length=8)
    answers: list[CodeAnswer] = Field(min_length=1, max_length=100)


Question = Annotated[TextQuestion | ExactQuestion | CodeQuestion, Field(discriminator="kind")]


def api_origin():
    origin = httpx.URL(os.getenv("SMARTEVAL_API_URL", "http://127.0.0.1:8088"))
    if origin.scheme not in {"http", "https"} or not origin.host or origin.userinfo \
            or origin.path not in {"", "/"} or origin.query or origin.fragment:
        raise ValueError("Set SMARTEVAL_API_URL to the public Web/API origin.")
    return origin


def shared_client(origin: httpx.URL | None = None) -> httpx.AsyncClient:
    base = origin or api_origin()
    loop = asyncio.get_running_loop()
    key = (loop, str(base))
    client = _clients.get(key)
    if client is None or client.is_closed:
        client = httpx.AsyncClient(base_url=base, timeout=20, follow_redirects=False, trust_env=False)
        _clients[key] = client
    return client


async def close_clients():
    loop = asyncio.get_running_loop()
    to_close = [key for key in _clients if key[0] is loop]
    for key in to_close:
        client = _clients.pop(key, None)
        if client is not None and not client.is_closed:
            await client.aclose()


async def request(method, path, *, body=None, headers=None, params=None):
    key = request_key.get()
    if key is None:
        key = os.getenv("SMARTEVAL_API_KEY", "")
    if not key:
        raise ValueError("Configure a SmartEval API key.")
    try:
        client = shared_client()
        response = await client.request(method, "/api/v2" + path, json=body, params=params,
                                        headers={"X-API-Key": key, **(headers or {})})
    except httpx.RequestError as exc:
        raise RuntimeError("SmartEval public API could not be reached.") from exc
    if not response.is_success:
        try:
            error = response.json()
            code = error.get("code", "HTTP_ERROR") if isinstance(error, dict) else "HTTP_ERROR"
        except ValueError:
            code = "HTTP_ERROR"
        raise RuntimeError(f"SmartEval API returned {response.status_code} ({code}).")
    return response.json()


def status_view(job):
    status = job["status"]
    return {
        **{key: job[key] for key in ("id", "title", "status", "run", "status_url", "error_code") if key in job},
        "counts": job.get("counts", {}),
        "terminal": status in TERMINAL,
        "needs_teacher": status in TEACHER_ACTION,
        "report_available": job["run"] > 0,
    }


def compact_report(report):
    items = []
    source_ids = set()
    for item in report["items"]:
        result = item.get("result") or {}
        criteria = result.get("criteria", [])
        source_ids.update(source_id for entry in criteria for source_id in entry.get("source_ids", []))
        items.append({
            **{key: item[key] for key in ("ordinal", "question_id", "submission_id", "kind", "status", "max_score", "error_code")
               if key in item},
            "suggested_score": result.get("suggested_score"),
            "final_score": result.get("final_score"),
            **{key: result[key] for key in ("decision", "review_reason", "reason", "analysis_error") if key in result},
            "criteria": criteria,
            "findings": result.get("findings", []),
        })
    sources = [
        {key: value for key, value in entry.items()
         if key in {"id", "asset_id", "name", "page", "region", "line", "paragraph", "submission_id"}}
        for entry in report["sources"] if entry["id"] in source_ids
    ]
    return {
        "job_id": report["job_id"], "run": report["run"], "latest_run": report["latest_run"],
        "submissions": report["submissions"], "items": items, "sources": sources,
        "similarity_status": report["similarity_status"], "similarity_alerts": report["similarity_alerts"],
    }


@server.tool(structured_output=True)
async def submit_grading_job(
    title: Annotated[str, Field(min_length=1, max_length=160)],
    questions: Annotated[list[Question], Field(min_length=1, max_length=80)],
    auto_confirm: bool = True,
    idempotency_key: Annotated[str, StringConstraints(pattern=r"^[A-Za-z0-9._:-]{1,100}$")] | None = None,
) -> dict[str, Any]:
    """Submit TEXT, EXACT or CODE questions. Reuse the same idempotency_key when retrying the same payload."""
    job = await request("POST", "/grading-jobs", body={
        "title": title, "questions": [question.model_dump(exclude_none=True) for question in questions],
        "auto_confirm": auto_confirm,
    }, headers={"Idempotency-Key": idempotency_key or str(uuid4())})
    return status_view(job)


@server.tool(structured_output=True)
async def get_grading_status(job_id: UUID) -> dict[str, Any]:
    """Return item progress counts. Terminal stops automatic polling for this run. needs_teacher also marks drafts awaiting confirmation."""
    return status_view(await request("GET", f"/grading-jobs/{job_id}"))


@server.tool(structured_output=True)
async def get_verified_report(job_id: UUID, run: Annotated[int, Field(ge=1)] | None = None) -> dict[str, Any]:
    """Read scores, verbatim evidence, code findings and structural similarity for a confirmed run. Pending or unverified items keep null scores."""
    report = await request("GET", f"/grading-jobs/{job_id}/report", params={} if run is None else {"run": run})
    return compact_report(report)


@server.tool(structured_output=True)
async def list_grading_templates() -> dict[str, Any]:
    """List available workspace grading templates by ID, version, title and question standards."""
    templates = await request("GET", "/grading-templates")
    return {"templates": [
        {key: template[key] for key in ("id", "version", "title", "questions") if key in template}
        for template in templates
    ]}


def main():
    server.run(transport="stdio")
