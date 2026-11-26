import json
import tempfile
import time
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

import httpx
import jwt
from cryptography.hazmat.primitives.asymmetric import rsa
from fastapi.testclient import TestClient

from sudoai.auth import pkce
from sudoai.config import Settings
from sudoai.main import create_app


class IdentityTests(unittest.TestCase):
    def test_signed_oidc_browser_native_and_group_denial(self):
        with tempfile.TemporaryDirectory() as path:
            cfg = Settings(
                data=Path(path) / "data",
                media=Path(path) / "raw",
                client_id="pilot",
                client_secret="test-only",
                run_worker=False,
            )
            app = create_app(cfg)
            key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
            jwk = json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(key.public_key()))
            jwk["kid"] = "test"
            state = {"nonce": "", "member": True}

            def provider(req):
                url = str(req.url)
                if ".well-known" in url:
                    return httpx.Response(
                        200,
                        json={
                            "issuer": cfg.issuer,
                            "authorization_endpoint": cfg.issuer + "authorize",
                            "token_endpoint": cfg.issuer + "token",
                            "jwks_uri": cfg.issuer + "keys",
                        },
                    )
                if url.endswith("keys"):
                    return httpx.Response(200, json={"keys": [jwk]})
                now = int(time.time())
                claims = {
                    "exp": now + 120,
                    "iat": now,
                    "iss": cfg.issuer,
                    "aud": cfg.client_id,
                    "sub": "fixture-sub",
                    "nonce": state["nonce"],
                    "name": "Tester",
                    "groups": [cfg.group] if state["member"] else [],
                }
                return httpx.Response(
                    200,
                    json={
                        "id_token": jwt.encode(
                            claims, key, algorithm="RS256", headers={"kid": "test"}
                        )
                    },
                )

            with TestClient(
                app, base_url=cfg.public_url, follow_redirects=False
            ) as client:
                app.state.http = httpx.AsyncClient(
                    transport=httpx.MockTransport(provider)
                )

                def callback(path):
                    begin = client.get(path)
                    self.assertEqual(begin.status_code, 303)
                    query = parse_qs(urlsplit(begin.headers["location"]).query)
                    state["nonce"] = query["nonce"][0]
                    return client.get(
                        "/auth/callback",
                        params={"state": query["state"][0], "code": "provider-code"},
                    )

                self.assertEqual(callback("/auth/login").status_code, 303)
                self.assertEqual(client.get("/api/v1/me").json()["name"], "Tester")
                verifier = "Z" * 58
                appstate = "S" * 32
                native = callback(
                    "/auth/native/start?platform=macos&challenge="
                    + pkce(verifier)
                    + "&state="
                    + appstate
                )
                url = urlsplit(native.headers["location"])
                self.assertEqual(
                    (url.scheme, url.netloc, url.path), ("sudoai", "auth", "/callback")
                )
                query = parse_qs(url.query)
                self.assertEqual(query["state"], [appstate])
                self.assertTrue(
                    client.post(
                        "/api/v1/auth/exchange",
                        json={"code": query["code"][0], "verifier": verifier},
                    ).is_success
                )
                state["member"] = False
                self.assertEqual(callback("/auth/login").status_code, 403)
                client.portal.call(app.state.http.aclose)
