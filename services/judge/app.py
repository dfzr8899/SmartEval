"""Lightweight Judge0-CE-compatible 5-language sandbox for SmartEval."""
import base64
import hmac
import os
import resource
import shutil
import signal
import subprocess
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Annotated

from fastapi import Depends, FastAPI, Header, HTTPException, Query, Response
from pydantic import BaseModel

WORK_ROOT = Path(os.getenv("JUDGE_WORK_DIR", "/work"))
MAX_OUTPUT_BYTES = 65_536
MAX_SOURCE_BYTES = 256_000
MAX_STDIN_BYTES = 1_048_576
TTL_SECONDS = 600
MAX_STORED = 1000

LANGUAGES = [
    {"id": 50, "name": "C (GCC)"},
    {"id": 54, "name": "C++ (GCC)"},
    {"id": 62, "name": "Java (OpenJDK)"},
    {"id": 63, "name": "JavaScript (Node.js)"},
    {"id": 71, "name": "Python (3)"},
]
LANG_IDS = {row["id"] for row in LANGUAGES}

STATUS = {
    1: {"id": 1, "description": "In Queue"},
    2: {"id": 2, "description": "Processing"},
    3: {"id": 3, "description": "Accepted"},
    5: {"id": 5, "description": "Time Limit Exceeded"},
    6: {"id": 6, "description": "Compilation Error"},
    11: {"id": 11, "description": "Runtime Error (NZEC)"},
    13: {"id": 13, "description": "Internal Error"},
}

app = FastAPI(title="SmartEval Judge Sandbox", docs_url=None, redoc_url=None, openapi_url=None)
_lock = threading.Lock()
_store: dict[str, dict] = {}
_pool = ThreadPoolExecutor(max_workers=int(os.getenv("JUDGE_WORKERS", "4")))


def check_auth(x_auth_token: Annotated[str | None, Header(alias="X-Auth-Token")] = None):
    expected = os.getenv("JUDGE_AUTH_TOKEN", "").strip()
    if expected and (not x_auth_token or not hmac.compare_digest(x_auth_token, expected)):
        raise HTTPException(401, "Invalid X-Auth-Token")


class SubmissionRequest(BaseModel):
    language_id: int
    source_code: str = ""
    stdin: str | None = ""
    cpu_time_limit: float | None = 3.0
    wall_time_limit: float | None = 5.0
    memory_limit: int | None = 262_144
    enable_network: bool | None = False


def b64_decode(value: str | None, limit: int) -> bytes:
    if not value:
        return b""
    try:
        raw = base64.b64decode(value, validate=False)
    except Exception as exc:
        raise HTTPException(422, "Invalid base64 payload") from exc
    if len(raw) > limit:
        raise HTTPException(422, "Payload exceeds size limit")
    return raw


def b64_encode(raw: bytes | None) -> str | None:
    if raw is None:
        return None
    return base64.b64encode(raw[:MAX_OUTPUT_BYTES]).decode("ascii")


def safe_env(workdir: Path) -> dict[str, str]:
    return {
        "PATH": "/usr/local/bin:/usr/bin:/bin",
        "HOME": str(workdir),
        "TMPDIR": str(workdir),
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
        "PYTHONDONTWRITEBYTECODE": "1",
    }


def make_preexec(cpu_sec: int, mem_kib: int, apply_as: bool):
    def _limits():
        os.setsid()
        resource.setrlimit(resource.RLIMIT_CPU, (cpu_sec, cpu_sec + 1))
        resource.setrlimit(resource.RLIMIT_FSIZE, (4 * 1024 * 1024, 4 * 1024 * 1024))
        resource.setrlimit(resource.RLIMIT_CORE, (0, 0))
        resource.setrlimit(resource.RLIMIT_NOFILE, (256, 256))
        if apply_as:
            mem_bytes = max(64 * 1024 * 1024, mem_kib * 1024)
            resource.setrlimit(resource.RLIMIT_AS, (mem_bytes, mem_bytes))
    return _limits


def run_cmd(cmd: list[str], cwd: Path, stdin_bytes: bytes, wall_sec: float, cpu_sec: int, mem_kib: int, apply_as: bool):
    start = time.perf_counter()
    proc = subprocess.Popen(
        cmd,
        cwd=str(cwd),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=safe_env(cwd),
        preexec_fn=make_preexec(cpu_sec, mem_kib, apply_as),
    )
    timed_out = False
    try:
        out, err = proc.communicate(input=stdin_bytes, timeout=wall_sec)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            out, err = proc.communicate(timeout=2)
        except Exception:
            out, err = b"", b""
    elapsed = time.perf_counter() - start
    return proc.returncode, out[:MAX_OUTPUT_BYTES], err[:MAX_OUTPUT_BYTES], elapsed, timed_out


def execute_submission(token: str, lang_id: int, source: bytes, stdin: bytes, cpu_limit: float, wall_limit: float, mem_kib: int):
    with _lock:
        if token in _store:
            _store[token]["status"] = STATUS[2]

    workdir = WORK_ROOT / token
    workdir.mkdir(parents=True, exist_ok=True)
    cpu_sec = max(1, min(10, int(cpu_limit + 0.99)))
    wall_sec = max(1.0, min(15.0, float(wall_limit)))
    mem_kib = max(65_536, min(1_048_576, int(mem_kib)))
    mem_mb = max(64, mem_kib // 1024)

    result = {
        "status": STATUS[13],
        "stdout": None,
        "stderr": None,
        "compile_output": None,
        "time": "0.000",
        "wall_time": "0.000",
        "memory": 0,
        "updated_at": time.monotonic(),
    }

    try:
        if lang_id == 50:  # C
            src = workdir / "main.c"
            bin_path = workdir / "main"
            src.write_bytes(source)
            rc, c_out, c_err, _, c_to = run_cmd(
                ["gcc", "-O2", "-std=c17", str(src), "-o", str(bin_path), "-lm"],
                workdir, b"", 12.0, 10, 524_288, False,
            )
            if c_to or rc != 0:
                result.update(status=STATUS[6], compile_output=b64_encode(c_err or c_out or b"Compilation failed"))
                return
            rc, out, err, elapsed, to = run_cmd([str(bin_path)], workdir, stdin, wall_sec, cpu_sec, mem_kib, True)

        elif lang_id == 54:  # C++
            src = workdir / "main.cpp"
            bin_path = workdir / "main"
            src.write_bytes(source)
            rc, c_out, c_err, _, c_to = run_cmd(
                ["g++", "-O2", "-std=c++17", str(src), "-o", str(bin_path)],
                workdir, b"", 15.0, 12, 786_432, False,
            )
            if c_to or rc != 0:
                result.update(status=STATUS[6], compile_output=b64_encode(c_err or c_out or b"Compilation failed"))
                return
            rc, out, err, elapsed, to = run_cmd([str(bin_path)], workdir, stdin, wall_sec, cpu_sec, mem_kib, True)

        elif lang_id == 62:  # Java
            src = workdir / "Main.java"
            src.write_bytes(source)
            rc, c_out, c_err, _, c_to = run_cmd(
                ["javac", "-encoding", "UTF-8", str(src)],
                workdir, b"", 15.0, 12, 786_432, False,
            )
            if c_to or rc != 0:
                result.update(status=STATUS[6], compile_output=b64_encode(c_err or c_out or b"Compilation failed"))
                return
            rc, out, err, elapsed, to = run_cmd(
                ["java", f"-Xmx{mem_mb}m", "-XX:+UseSerialGC", "-XX:ReservedCodeCacheSize=32m", "-cp", str(workdir), "Main"],
                workdir, stdin, max(wall_sec, 6.0), max(cpu_sec, 4), mem_kib, False,
            )

        elif lang_id == 63:  # JavaScript (Node.js)
            src = workdir / "main.js"
            src.write_bytes(source)
            rc, out, err, elapsed, to = run_cmd(
                ["node", f"--max-old-space-size={mem_mb}", str(src)],
                workdir, stdin, wall_sec, cpu_sec, mem_kib, False,
            )

        elif lang_id == 71:  # Python 3
            src = workdir / "main.py"
            src.write_bytes(source)
            rc, out, err, elapsed, to = run_cmd(
                ["python3", "-I", str(src)],
                workdir, stdin, wall_sec, cpu_sec, mem_kib, True,
            )
        else:
            result.update(status=STATUS[13], stderr=b64_encode(b"Unsupported language"))
            return

        st = STATUS[5] if to or rc in (-signal.SIGXCPU, 137, 152) else STATUS[3] if rc == 0 else STATUS[11]
        result.update(
            status=st,
            stdout=b64_encode(out),
            stderr=b64_encode(err),
            time=f"{elapsed:.3f}",
            wall_time=f"{elapsed:.3f}",
            memory=0,
        )
    except Exception as exc:
        result.update(status=STATUS[13], stderr=b64_encode(f"Sandbox error: {type(exc).__name__}".encode()))
    finally:
        result["updated_at"] = time.monotonic()
        with _lock:
            _store[token] = result
        shutil.rmtree(workdir, ignore_errors=True)


def prune_store():
    now = time.monotonic()
    expired = [k for k, v in _store.items() if now - v.get("updated_at", now) > TTL_SECONDS]
    for k in expired:
        _store.pop(k, None)
    if len(_store) > MAX_STORED:
        oldest = sorted(_store.items(), key=lambda kv: kv[1].get("updated_at", 0))
        for k, _ in oldest[: len(_store) - MAX_STORED]:
            _store.pop(k, None)


@app.get("/health")
def health():
    return Response(status_code=204)


@app.get("/languages", dependencies=[Depends(check_auth)])
def list_languages():
    return LANGUAGES


@app.post("/submissions", status_code=201, dependencies=[Depends(check_auth)])
def create_submission(
    payload: SubmissionRequest,
    base64_encoded: bool = Query(default=False),
    wait: bool = Query(default=False),
):
    if payload.language_id not in LANG_IDS:
        raise HTTPException(422, "Unsupported language_id")
    if base64_encoded:
        source = b64_decode(payload.source_code, MAX_SOURCE_BYTES)
        stdin = b64_decode(payload.stdin, MAX_STDIN_BYTES)
    else:
        source = (payload.source_code or "").encode("utf-8")[:MAX_SOURCE_BYTES]
        stdin = (payload.stdin or "").encode("utf-8")[:MAX_STDIN_BYTES]

    token = str(uuid.uuid4())
    with _lock:
        prune_store()
        _store[token] = {
            "status": STATUS[1],
            "stdout": None,
            "stderr": None,
            "compile_output": None,
            "time": None,
            "wall_time": None,
            "memory": None,
            "updated_at": time.monotonic(),
        }

    cpu_limit = payload.cpu_time_limit if payload.cpu_time_limit is not None else 3.0
    wall_limit = payload.wall_time_limit if payload.wall_time_limit is not None else 5.0
    mem_kib = payload.memory_limit if payload.memory_limit is not None else 262_144

    if wait:
        execute_submission(token, payload.language_id, source, stdin, cpu_limit, wall_limit, mem_kib)
        return get_submission(token, base64_encoded=base64_encoded)

    _pool.submit(execute_submission, token, payload.language_id, source, stdin, cpu_limit, wall_limit, mem_kib)
    return {"token": token}


@app.get("/submissions/{token}", dependencies=[Depends(check_auth)])
def get_submission(
    token: str,
    base64_encoded: bool = Query(default=False),
    fields: str | None = Query(default=None),
):
    with _lock:
        entry = _store.get(token)
        if not entry:
            raise HTTPException(404, "Submission not found")
        data = dict(entry)
    data.pop("updated_at", None)
    if not base64_encoded:
        for f in ("stdout", "stderr", "compile_output"):
            if data.get(f):
                data[f] = base64.b64decode(data[f]).decode("utf-8", errors="replace")
    if fields:
        allowed = {f.strip() for f in fields.split(",") if f.strip()}
        data = {k: v for k, v in data.items() if k in allowed}
    return data
