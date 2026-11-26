import asyncio
from contextlib import asynccontextmanager, suppress
from pathlib import Path

import httpx
from fastapi import FastAPI
from fastapi.responses import JSONResponse
from fastapi.staticfiles import StaticFiles

from . import auth, capture, importer, web
from .ai import OpenAI, Worker
from .config import Settings
from .db import Store


class RequestLimits:
    """Bound both streaming transfer and ordinary uploads before multipart parsing."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http":
            return await self.app(scope, receive, send)
        path = scope["path"]
        limit = (
            165 * 1024**2
            if path == "/api/v1/import"
            else 8 * 1024**2
            if "/chunks/" in path
            else 3 * 1024**2
        )
        headers = dict(scope["headers"])
        try:
            length = int(headers.get(b"content-length", b"0"))
        except ValueError:
            length = limit + 1

        async def reject():
            await JSONResponse({"detail": "Request is too large"}, 413)(
                scope, receive, send
            )

        if length < 0 or length > limit:
            return await reject()
        size = 0

        async def bounded():
            nonlocal size
            message = await receive()
            size += len(message.get("body", b""))
            if size > limit:
                from starlette.exceptions import HTTPException

                raise HTTPException(413, "Request is too large")
            return message

        async def secured(message):
            if message["type"] == "http.response.start":
                message["headers"] += [
                    (b"cache-control", b"private, no-store"),
                    (b"referrer-policy", b"no-referrer"),
                    (b"x-content-type-options", b"nosniff"),
                    (b"x-frame-options", b"DENY"),
                    (
                        b"content-security-policy",
                        b"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; media-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
                    ),
                ]
            await send(message)

        return await self.app(scope, bounded, secured)


def create_app(settings=None, provider=None):
    cfg = settings or Settings()

    @asynccontextmanager
    async def lifespan(app):
        async with httpx.AsyncClient(timeout=15, follow_redirects=False) as client:
            app.state.http = client
            app.state.store = Store(cfg)
            app.state.provider = provider or OpenAI(cfg, client)
            app.state.worker = Worker(app.state.store, app.state.provider)
            task = (
                asyncio.create_task(app.state.worker.loop()) if cfg.run_worker else None
            )
            try:
                yield
            finally:
                if task:
                    task.cancel()
                    with suppress(asyncio.CancelledError):
                        await task

    app = FastAPI(
        title="SudoAI",
        version="0.1.0",
        lifespan=lifespan,
        docs_url=None,
        redoc_url=None,
        openapi_url=None,
    )
    app.state.settings = cfg
    app.add_middleware(RequestLimits)
    app.include_router(auth.router)
    app.include_router(capture.router)
    app.include_router(importer.router)
    app.include_router(web.router)
    app.mount(
        "/static",
        StaticFiles(directory=str(Path(__file__).with_name("static"))),
        name="static",
    )

    @app.get("/healthz")
    def health():
        with app.state.store.connect() as db:
            db.execute("SELECT 1 FROM sessions LIMIT 1").fetchone()
        return {"status": "ok"}

    return app


app = create_app()
