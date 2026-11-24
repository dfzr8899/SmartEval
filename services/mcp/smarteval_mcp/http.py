"""Streamable HTTP transport. Authentication is bound to a single ASGI request."""
import os
import re

import httpx
import uvicorn
from starlette.responses import JSONResponse, Response

from .server import api_origin, close_clients, request_key, server, shared_client


class RequestAuthentication:
    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] == "lifespan":
            async def wrapped_send(message):
                if message.get("type") == "lifespan.shutdown.complete":
                    await close_clients()
                await send(message)

            await self.app(scope, receive, wrapped_send)
            return
        if scope["type"] == "http" and scope["path"] == "/health":
            await Response(status_code=204)(scope, receive, send)
            return
        if scope["type"] != "http" or scope["path"] != "/mcp":
            await self.app(scope, receive, send)
            return
        headers = scope["headers"]
        origins = [value for name, value in headers if name == b"origin"]
        public_origin = os.getenv("SMARTEVAL_PUBLIC_ORIGIN", "http://localhost:8088").rstrip("/").encode("utf-8")
        if origins and origins != [public_origin]:
            await JSONResponse({"code":"INVALID_ORIGIN"},status_code=403)(scope,receive,send)
            return
        keys = [value for name, value in headers if name == b"x-api-key"]
        if len(keys) != 1 or not re.fullmatch(rb"[A-Za-z0-9_-]{43}",keys[0]):
            await self.reject(scope,receive,send,401)
            return
        key = keys[0].decode("ascii")
        try:
            client = shared_client()
            probe = await client.get("/api/v2/auth/key", headers={"X-API-Key": key}, timeout=5)
            valid = probe.status_code
        except httpx.RequestError:
            valid = 503
        if valid != 204:
            await self.reject(scope,receive,send,401 if valid==401 else 503)
            return
        marker = request_key.set(key)
        try:
            await self.app(scope,receive,send)
        finally:
            request_key.reset(marker)

    @staticmethod
    async def reject(scope,receive,send,status):
        await JSONResponse({"code":"INVALID_API_KEY" if status==401 else "BUSINESS_UNAVAILABLE"},
                           status_code=status,headers={"Cache-Control":"no-store"})(scope,receive,send)


def main():
    api_origin()
    uvicorn.run(RequestAuthentication(server.streamable_http_app()),
                host=os.getenv("SMARTEVAL_MCP_HOST","0.0.0.0"),port=int(os.getenv("SMARTEVAL_MCP_PORT","8090")))


if __name__ == "__main__":
    main()
