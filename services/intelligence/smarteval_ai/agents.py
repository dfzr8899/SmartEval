import asyncio
import hashlib
import json
import os
import time
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from urllib.parse import urlsplit

from openai import AsyncOpenAI

_pools = {}


@asynccontextmanager
async def client_pool():
    loop = asyncio.get_running_loop()
    pool = _pools[loop] = {}
    try:
        yield
    finally:
        _pools.pop(loop, None)
        await asyncio.gather(*(client.close() for client in pool.values()))


@asynccontextmanager
async def model_client(model):
    pool = _pools.get(asyncio.get_running_loop())
    if pool is None or getattr(model, "transient", False):
        async with AsyncOpenAI(base_url=model.base_url, api_key=model.key, max_retries=0) as client:
            yield client
        return
    key = (model.base_url, model.key)
    if key not in pool:
        pool[key] = AsyncOpenAI(base_url=model.base_url, api_key=model.key, max_retries=0)
    yield pool[key]


ROLE_PREFIXES = {
    "QuestionReader": "QUESTION_READER",
    "RubricDesigner": "RUBRIC_DESIGNER",
    "Grader": "GRADER",
    "EvidenceReviewer": "EVIDENCE_REVIEWER",
    "LogicReviewer": "LOGIC_REVIEWER",
}


class Unavailable(RuntimeError):
    pass


@dataclass(frozen=True)
class Model:
    base_url: str
    key: str = field(repr=False)
    name: str
    response_format: str = "text"
    token_limit_field: str = "max_tokens"
    extra_body: dict = field(default_factory=dict, repr=False)
    transient: bool = False

    @property
    def pin(self):
        options = json.dumps([self.base_url, self.response_format, self.token_limit_field, self.extra_body],
                             sort_keys=True, separators=(",", ":"))
        return {"model": self.name, "configuration_id": hashlib.sha256(options.encode()).hexdigest()}

    @classmethod
    def from_override(cls, override):
        if not isinstance(override, dict):
            raise Unavailable("Invalid model override")
        url = str(override.get("base_url") or "").strip().rstrip("/")
        key = str(override.get("api_key") or "").strip()
        name = str(override.get("model_name") or "").strip()
        mode = str(override.get("response_format") or "json_object").strip()
        validate_endpoint(url, key, name)
        if mode not in {"text", "json_object", "json_schema"}:
            raise Unavailable("Invalid model response format")
        return cls(url, key, name, mode, "max_tokens", {}, transient=True)

    @classmethod
    def configured(cls, role=None, override=None):
        if override:
            return cls.from_override(override)
        prefix = f"SMARTEVAL_{ROLE_PREFIXES[role]}_" if role else None

        def default(key):
            return os.getenv(f"SMARTEVAL_MODEL_{key}", "").strip()

        def override(key):
            return os.getenv(prefix + key, "").strip() if prefix else ""

        url = (override("BASE_URL") or default("BASE_URL")).rstrip("/")
        same_endpoint = url == default("BASE_URL").rstrip("/")
        key = override("API_KEY") or (default("API_KEY") if same_endpoint else "")
        legacy = os.getenv("SMARTEVAL_REVIEWER_MODEL", "").strip() if role == "EvidenceReviewer" else ""
        name = override("MODEL") or legacy or default("NAME")
        mode = override("RESPONSE_FORMAT") or (default("RESPONSE_FORMAT") if same_endpoint else "") or "text"
        limit_field = override("TOKEN_LIMIT_FIELD") or (default("TOKEN_LIMIT_FIELD") if same_endpoint else "") or "max_tokens"
        extension = override("EXTRA_BODY_JSON") or (default("EXTRA_BODY_JSON") if same_endpoint else "") or "{}"
        validate_endpoint(url, key, name)
        if mode not in {"text", "json_object", "json_schema"}:
            raise Unavailable("Invalid model response format")
        if limit_field not in {"max_tokens", "max_completion_tokens"}:
            raise Unavailable("Invalid model token limit field")
        try:
            extra = json.loads(extension)
        except ValueError as error:
            raise Unavailable("Model extra body is invalid JSON") from error
        reserved = {"model", "messages", "stream", "n", "max_tokens", "max_completion_tokens", "response_format", "tools", "tool_choice"}
        if not isinstance(extra, dict) or reserved.intersection(extra):
            raise Unavailable("Model extra body must be an object without reserved request fields")
        return cls(url, key, name, mode, limit_field, extra)

    @classmethod
    def vision(cls):
        name = os.getenv("SMARTEVAL_VISION_MODEL", "").strip()
        if not name:
            raise Unavailable("Vision model is not configured")
        url = os.getenv("SMARTEVAL_VISION_BASE_URL", "").strip().rstrip("/")
        separate_key = os.getenv("SMARTEVAL_VISION_API_KEY", "").strip()
        default_url = os.getenv("SMARTEVAL_MODEL_BASE_URL", "").strip().rstrip("/")
        same_endpoint = not url or url == default_url
        if not url:
            url = default_url
            key = separate_key or os.getenv("SMARTEVAL_MODEL_API_KEY", "").strip()
        else:
            key = separate_key
        validate_endpoint(url, key, name)
        limit_field = (os.getenv("SMARTEVAL_VISION_TOKEN_LIMIT_FIELD", "").strip()
                       or (os.getenv("SMARTEVAL_MODEL_TOKEN_LIMIT_FIELD", "").strip() if same_endpoint else "")
                       or "max_tokens")
        if limit_field not in {"max_tokens", "max_completion_tokens"}:
            raise Unavailable("Invalid vision token limit field")
        extension = (os.getenv("SMARTEVAL_VISION_EXTRA_BODY_JSON", "").strip()
                     or (os.getenv("SMARTEVAL_MODEL_EXTRA_BODY_JSON", "").strip() if same_endpoint else "")
                     or "{}")
        try:
            extra = json.loads(extension)
        except ValueError as error:
            raise Unavailable("Vision extra body is invalid JSON") from error
        reserved = {"model", "messages", "stream", "n", "max_tokens", "max_completion_tokens", "response_format", "tools", "tool_choice"}
        if not isinstance(extra, dict) or reserved.intersection(extra):
            raise Unavailable("Vision extra body must be an object without reserved request fields")
        return cls(url, key, name, "text", limit_field, extra)


def validate_endpoint(url, key, name):
    try:
        parsed = urlsplit(url)
        if (parsed.scheme in {"http", "https"} and parsed.hostname and not parsed.username
                and parsed.password is None and not parsed.query and not parsed.fragment
                and not any(character.isspace() for character in url) and parsed.port != 0 and key and name):
            return
    except ValueError:
        pass
    raise Unavailable("A model name, HTTP(S) endpoint and API key are required")


def role_models(override=None):
    models = {}
    for role in ROLE_PREFIXES:
        try:
            models[role] = Model.configured(role, override=override).pin
        except Unavailable:
            continue
    return models


def object_schema(**properties):
    return {"type": "object", "properties": properties, "required": list(properties), "additionalProperties": False}


def array(items):
    return {"type": "array", "items": items}


STRING = {"type": "string"}
INTEGER = {"type": "integer"}
BOOLEAN = {"type": "boolean"}
NULLABLE_STRING = {"type": ["string", "null"]}
SCHEMAS = {
    "QuestionReader": object_schema(questions=array(object_schema(
        id=STRING, prompt=STRING, kind={"type": "string", "enum": ["TEXT", "EXACT", "CODE"]},
        source_ids=array(STRING), answers=array(object_schema(submission_id=STRING, text=STRING,
            source_ids=array(STRING), language=NULLABLE_STRING, needs_input=BOOLEAN))))),
    "RubricDesigner": object_schema(standards=array(object_schema(
        id=STRING, max_score=INTEGER, reference_answer=NULLABLE_STRING,
        rubric=array(object_schema(id=STRING, description=STRING, points=INTEGER)), accepted=array(STRING)))),
    "Grader": object_schema(criteria=array(object_schema(id=STRING, points=INTEGER, reason=STRING, quote=STRING))),
    "EvidenceReviewer": object_schema(
        approved=BOOLEAN,
        review_summary=STRING,
        criteria=array(object_schema(id=STRING, points=INTEGER, reason=STRING, quote=STRING)),
        total_score=INTEGER,
    ),
    "EvidenceReviewerVeto": object_schema(approved=BOOLEAN, review_summary=STRING),
    "LogicReviewer": object_schema(findings=array(object_schema(quote=STRING, reason=STRING))),
}


class LlmClient:
    def __init__(self, model):
        self.model = model
        self.usage = []

    async def complete(self, role, instructions, payload, budget, timeout, schema_name=None):
        return await self.chat(role, [
            {"role": "system", "content": "Return a JSON object.\n" + instructions},
            {"role": "user", "content": json.dumps(payload, ensure_ascii=False)},
        ], budget, timeout, schema_name=schema_name)

    async def chat(self, role, messages, budget, timeout, schema_name=None):
        options = {self.model.token_limit_field: budget}
        mode = self.model.response_format
        if mode == "json_object":
            options["response_format"] = {"type": mode}
        elif mode == "json_schema":
            options["response_format"] = {"type": mode, "json_schema": {
                "name": schema_name or role, "strict": True, "schema": SCHEMAS[schema_name or role],
            }}
        start = time.perf_counter()
        async with model_client(self.model) as client:
            response = await asyncio.wait_for(client.chat.completions.create(
                model=self.model.name, messages=messages, extra_body=self.model.extra_body, timeout=timeout, **options), timeout)
        usage = response.usage
        self.usage.append({"agent_role": role, "model": response.model or self.model.name,
                           "input_tokens": usage.prompt_tokens if usage else None,
                           "output_tokens": usage.completion_tokens if usage else None,
                           "latency_ms": round((time.perf_counter() - start) * 1000)})
        if not response.choices or response.choices[0].finish_reason == "length":
            raise ValueError("Model response is empty or truncated")
        return response.choices[0].message.content


def parse_object(text, byte_limit=65536):
    if not isinstance(text, str) or len(text.encode("utf-8")) > byte_limit:
        raise ValueError("Model output exceeds the response limit")
    stripped = text.strip()
    if stripped.startswith("\x60\x60\x60"):
        lines = stripped.splitlines()
        if len(lines) >= 2 and lines[-1].strip() == "\x60\x60\x60":
            stripped = "\n".join(lines[1:-1]).strip()
    try:
        result = json.loads(stripped)
    except ValueError:
        start = stripped.find("{")
        if start < 0:
            raise
        result, end = json.JSONDecoder().raw_decode(stripped, start)
        if stripped[end:].strip().startswith("{"):
            raise ValueError("Model response contains multiple JSON objects")
    if not isinstance(result, dict):
        raise ValueError("Model response is not a JSON object")
    return result
