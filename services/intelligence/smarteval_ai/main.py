import os
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, Response
from fastapi.responses import JSONResponse

from .agents import Model, Unavailable, client_pool, role_models
from .grading import router
from .runtime import store
from .similarity import router as similarity_router
from .vision import ocr_readiness

_s3_health_cache = {"expires": 0.0, "storage": None}


@asynccontextmanager
async def lifespan(app):
    async with client_pool():
        yield


app = FastAPI(title="SmartEval Grading Intelligence", version="2.0", docs_url=None, redoc_url=None,
              openapi_url=None, lifespan=lifespan)


@app.get("/live")
def live():
    return Response(status_code=204)


@app.get("/health")
def health():
    reasons = []
    critical = False
    internal_auth = len(os.getenv("SMARTEVAL_INTERNAL_TOKEN", "")) >= 32
    if not internal_auth:
        reasons.append("INTERNAL_AUTH_NOT_CONFIGURED")
        critical = True
    try:
        assets = store()
        now = time.monotonic()
        if assets.client is not None and _s3_health_cache["storage"] is not None and now < _s3_health_cache["expires"]:
            storage = _s3_health_cache["storage"]
        else:
            storage = assets.readiness()
            if assets.client is not None:
                _s3_health_cache["storage"] = storage
                _s3_health_cache["expires"] = now + 30.0
        for operation in ("write", "read", "cleanup"):
            if storage[operation] is False:
                reasons.append(f"OBJECT_STORAGE_{operation.upper()}_FAILED")
                critical = True
    except Exception:
        storage = {"write": False, "read": None, "cleanup": None}
        reasons.append("OBJECT_STORAGE_CONFIGURATION_INVALID")
        critical = True

    try:
        Model.configured()
        default_model = {"configured": True}
    except Unavailable as error:
        default_model = {"configured": False, "detail": str(error)}
        reasons.append("DEFAULT_MODEL_NOT_CONFIGURED")

    ocr = ocr_readiness()
    if not ocr["ready"]:
        reasons.append(ocr["reason"])

    try:
        Model.vision()
        vision = {"configured": True}
    except Unavailable as error:
        vision = {"configured": False, "detail": str(error)}
        reasons.append("VISION_NOT_CONFIGURED")
    payload = {"status": "degraded" if reasons else "ready", "reasons": reasons,
               "object_storage": storage, "internal_auth_configured": internal_auth, "default_model": default_model,
               "configured_roles": list(role_models()), "ocr": ocr, "vision": vision}
    return JSONResponse(payload, status_code=503 if critical else 200)


app.include_router(router, prefix="/internal/v2/grading")
app.include_router(similarity_router, prefix="/internal/v2/grading")
