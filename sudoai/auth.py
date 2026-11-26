import base64
import hashlib
import hmac
import re
import secrets
from urllib.parse import urlencode, urlsplit

import httpx
import jwt
from fastapi import APIRouter, Depends, HTTPException, Request
from fastapi.responses import RedirectResponse
from pydantic import BaseModel, Field

from .db import digest, new_token, now, uid

router = APIRouter()
COOKIE = "__Host-sudoai-session"
FLOW = "__Host-sudoai-flow"


def pkce(verifier):
    return (
        base64.urlsafe_b64encode(hashlib.sha256(verifier.encode()).digest())
        .decode()
        .rstrip("=")
    )


def csrf(token):
    return digest(("sudoai-browser-csrf:" + token).encode())


def principal(request: Request):
    authorization = request.headers.get("authorization")
    if authorization:
        scheme, _, token = authorization.partition(" ")
        if scheme.lower() != "bearer":
            raise HTTPException(401, "Sign in to SudoAI")
        kind = "device"
    else:
        token = request.cookies.get(COOKIE, "")
        kind = "browser"
    with request.app.state.store.connect() as db:
        row = db.execute(
            "SELECT u.id,u.name FROM tokens t JOIN users u ON t.owner=u.id WHERE t.digest=? AND t.kind=? AND t.expires>?",
            (digest(token.encode()), kind, now()),
        ).fetchone()
    if row is None:
        raise HTTPException(401, "Sign in to SudoAI")
    if kind == "browser" and request.method not in {"GET", "HEAD", "OPTIONS"}:
        supplied = request.headers.get("X-SudoAI-CSRF", "")
        if not hmac.compare_digest(supplied, csrf(token)):
            raise HTTPException(403, "Refresh the page and try again")
    return dict(row)


async def metadata(request):
    cfg = request.app.state.settings
    if not cfg.client_id or not cfg.client_secret:
        raise HTTPException(503, "Pilot sign-in has not been configured")
    cached = getattr(request.app.state, "oidc_metadata", None)
    if cached and cached[0] > now():
        return cached[1]
    try:
        response = await request.app.state.http.get(
            cfg.issuer + ".well-known/openid-configuration"
        )
        response.raise_for_status()
        info = response.json()
        for name in ("authorization_endpoint", "token_endpoint", "jwks_uri"):
            url = urlsplit(info[name])
            if url.scheme != "https" or url.netloc != urlsplit(cfg.issuer).netloc:
                raise ValueError("Unexpected provider endpoint")
        if info["issuer"] != cfg.issuer:
            raise ValueError("Unexpected issuer")
    except (httpx.HTTPError, KeyError, ValueError) as exc:
        raise HTTPException(503, "Sign-in provider unavailable") from exc
    request.app.state.oidc_metadata = (now() + 900, info)
    return info


async def begin(request, challenge=None, app_state=None):
    cfg = request.app.state.settings
    info = await metadata(request)
    state, browser, nonce, verifier = (secrets.token_urlsafe(40) for _ in range(4))
    with request.app.state.store.connect(write=True) as db:
        db.execute(
            "INSERT INTO auth_flows VALUES(?,?,?,?,?,?,?)",
            (
                state,
                digest(browser.encode()),
                nonce,
                verifier,
                challenge,
                app_state,
                now() + 600,
            ),
        )
    query = urlencode(
        dict(
            client_id=cfg.client_id,
            redirect_uri=cfg.public_url + "/auth/callback",
            response_type="code",
            scope="openid profile email",
            state=state,
            nonce=nonce,
            code_challenge=pkce(verifier),
            code_challenge_method="S256",
        )
    )
    response = RedirectResponse(info["authorization_endpoint"] + "?" + query, 303)
    response.set_cookie(
        FLOW, browser, max_age=600, secure=True, httponly=True, samesite="lax", path="/"
    )
    return response


@router.get("/auth/login")
async def login(request: Request):
    return await begin(request)


@router.get("/auth/native/start")
async def native(request: Request, platform: str, challenge: str, state: str):
    if (
        platform not in {"android", "macos"}
        or not re.fullmatch(r"[A-Za-z0-9_-]{43}", challenge)
        or not re.fullmatch(r"[A-Za-z0-9_.~-]{16,128}", state)
    ):
        raise HTTPException(400, "Invalid sign-in request")
    return await begin(request, challenge, state)


@router.get("/auth/callback")
async def callback(request: Request, state: str = "", code: str = "", error: str = ""):
    settings = request.app.state.settings
    store = request.app.state.store
    browser = digest(request.cookies.get(FLOW, "").encode())
    with store.connect(write=True) as db:
        flow = db.execute(
            "DELETE FROM auth_flows WHERE state=? AND browser=? AND expires>? RETURNING *",
            (state, browser, now()),
        ).fetchone()
    if not flow or error or not code:
        raise HTTPException(403, "Sign-in was declined or expired")
    info = await metadata(request)
    try:
        client = request.app.state.http
        token = await client.post(
            info["token_endpoint"],
            auth=(settings.client_id, settings.client_secret),
            data={
                "grant_type": "authorization_code",
                "code": code,
                "redirect_uri": settings.public_url + "/auth/callback",
                "code_verifier": flow["verifier"],
            },
        )
        token.raise_for_status()
        encoded = token.json()["id_token"]
        header = jwt.get_unverified_header(encoded)
        keys = await client.get(info["jwks_uri"])
        keys.raise_for_status()
        key = jwt.PyJWKSet.from_dict(keys.json())[header["kid"]].key
        claims = jwt.decode(
            encoded,
            key,
            algorithms=["RS256"],
            audience=settings.client_id,
            issuer=settings.issuer,
            options={"require": ["exp", "iat", "sub", "nonce"]},
        )
        if (
            not hmac.compare_digest(claims["nonce"], flow["nonce"])
            or claims.get("azp", settings.client_id) != settings.client_id
        ):
            raise ValueError("Invalid ID token")
        if (
            not isinstance(claims.get("groups"), list)
            or settings.group not in claims["groups"]
        ):
            raise HTTPException(403, "This account is outside the SudoAI pilot")
    except (httpx.HTTPError, jwt.PyJWTError, KeyError, ValueError, TypeError) as exc:
        raise HTTPException(403, "Could not verify pilot sign-in") from exc
    with store.connect(write=True) as db:
        subject = settings.issuer + "|" + claims["sub"]
        owner = db.execute(
            "INSERT INTO users VALUES(?,?,?) ON CONFLICT(subject) DO UPDATE SET name=excluded.name RETURNING id",
            (
                uid(),
                subject,
                claims.get("name")
                or claims.get("preferred_username")
                or "Pilot member",
            ),
        ).fetchone()[0]
        if flow["challenge"]:
            bridge = secrets.token_urlsafe(40)
            db.execute(
                "INSERT INTO exchanges VALUES(?,?,?,?)",
                (digest(bridge.encode()), owner, flow["challenge"], now() + 120),
            )
            response = RedirectResponse(
                "sudoai://auth/callback?"
                + urlencode({"code": bridge, "state": flow["app_state"]}),
                303,
            )
        else:
            response = RedirectResponse("/", 303)
            response.set_cookie(
                COOKIE,
                new_token(db, owner, "browser"),
                max_age=8 * 3600,
                secure=True,
                httponly=True,
                samesite="lax",
                path="/",
            )
    response.delete_cookie(FLOW, path="/", secure=True, httponly=True, samesite="lax")
    return response


class Exchange(BaseModel):
    code: str = Field(min_length=16, max_length=150)
    verifier: str = Field(pattern=r"^[A-Za-z0-9._~-]{43,128}$")


@router.post("/api/v1/auth/exchange")
def exchange(body: Exchange, request: Request):
    with request.app.state.store.connect(write=True) as db:
        row = db.execute(
            "DELETE FROM exchanges WHERE digest=? AND challenge=? AND expires>? RETURNING owner",
            (digest(body.code.encode()), pkce(body.verifier), now()),
        ).fetchone()
        if row is None:
            raise HTTPException(403, "This sign-in expired or was already used")
        return {
            "access_token": new_token(db, row[0], "device"),
            "token_type": "Bearer",
            "expires_in": 7 * 86400,
        }


@router.get("/api/v1/me")
def me(user=Depends(principal)):
    return user


@router.post("/api/v1/auth/revoke")
def revoke(request: Request, user=Depends(principal)):
    token = request.headers.get("authorization", "").partition(" ")[
        2
    ] or request.cookies.get(COOKIE, "")
    with request.app.state.store.connect(write=True) as db:
        db.execute(
            "DELETE FROM tokens WHERE digest=? AND owner=?",
            (digest(token.encode()), user["id"]),
        )
    from fastapi.responses import JSONResponse

    response = JSONResponse({"revoked": True})
    response.delete_cookie(COOKIE, path="/", secure=True, httponly=True, samesite="lax")
    return response
