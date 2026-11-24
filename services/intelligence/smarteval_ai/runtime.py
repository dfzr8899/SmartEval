import hmac
import os
from functools import lru_cache
from typing import Annotated

from fastapi import Header, HTTPException

from .assets import Assets


def authorized(authorization: Annotated[str | None, Header()] = None):
    secret = os.getenv("SMARTEVAL_INTERNAL_TOKEN", "")
    if len(secret) < 32:
        raise HTTPException(503, "Internal service authentication is not configured")
    if not authorization or not hmac.compare_digest(authorization, "Bearer " + secret):
        raise HTTPException(401, "Service credential required")


@lru_cache(maxsize=1)
def store():
    return Assets.configured(os.getenv("SMARTEVAL_BLOB_DIRECTORY", "./data/objects"))
