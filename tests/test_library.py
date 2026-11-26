import asyncio
import io
import tempfile
import unittest
import wave
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient

from sudoai.ai import EMPTY
from sudoai.auth import COOKIE, csrf, pkce
from sudoai.config import Settings
from sudoai.db import digest, new_token, now
from sudoai.main import create_app

VTT = b"""WEBVTT

00:00:01.000 --> 00:00:04.000
<v Morgan>The launch review is Friday.</v>

00:00:05.000 --> 00:00:09.000
<v Alex>Morgan owns the network checklist.</v>
"""


def wav():
    buf = io.BytesIO()
    with wave.open(buf, "wb") as audio:
        audio.setnchannels(1)
        audio.setsampwidth(2)
        audio.setframerate(16000)
        audio.writeframes(bytes([0, 16, 0, 240]) * 800)
    return buf.getvalue()


class FixtureProvider:
    def __init__(self, settings):
        self.settings = settings
        self.calls = []

    async def transcribe(self, data, mime):
        self.calls.append("speech")
        return "Morgan owns the network checklist."

    async def structured(self, name, schema, instructions, payload):
        self.calls.append(name)
        if name == "meeting_summary":
            row = next((x for x in payload if "Morgan owns" in x["text"]), payload[0])
            verified = {
                "text": row["text"],
                "owner": "Morgan",
                "due": "invented",
                "references": [{"id": row["id"], "quote": row["text"]}],
            }
            bogus = {
                "text": "A fabricated finding",
                "owner": None,
                "due": None,
                "references": [{"id": row["id"], "quote": "this is missing"}],
            }
            return {"actions": [verified], "decisions": [bogus], "highlights": []}, {
                "input_tokens": 12,
                "output_tokens": 8,
            }
        row = payload["evidence"][0]
        return {
            "answer": row["text"],
            "references": [{"id": row["id"], "quote": row["text"]}],
        }, {"input_tokens": 10, "output_tokens": 5}


class LibraryTests(unittest.TestCase):
    def setUp(self):
        self.scratch = tempfile.TemporaryDirectory()
        self.addCleanup(self.scratch.cleanup)
        root = Path(self.scratch.name)
        self.cfg = Settings(data=root / "data", media=root / "media", run_worker=False)
        self.provider = FixtureProvider(self.cfg)
        app = create_app(self.cfg, self.provider)
        self.client = TestClient(app, base_url="https://sudoai.example.com")
        self.client.__enter__()
        self.addCleanup(self.client.__exit__, None, None, None)
        self.store = app.state.store
        self.worker = app.state.worker
        with self.store.connect(write=True) as db:
            for user in ("u1", "u2"):
                db.execute(
                    "INSERT INTO users VALUES(?,?,?)", (user, "sub-" + user, user)
                )
            self.token = new_token(db, "u1", "device")
            self.second = {"Authorization": "Bearer " + new_token(db, "u2", "device")}
            self.browser = new_token(db, "u1", "browser")
        self.headers = {"Authorization": "Bearer " + self.token}
        self.sid = str(uuid4())
        self.base = "/api/v1/sessions/" + self.sid
        self.meta = {
            "title": "Network review",
            "device": "macos",
            "started_at": "2026-11-25T12:30:00Z",
        }

    def create(self):
        self.assertEqual(
            self.client.put(
                self.base, json=self.meta, headers=self.headers
            ).status_code,
            200,
        )

    def upload(self, seq=0, track="mic"):
        data = wav()
        return self.client.put(
            self.base + f"/tracks/{track}/chunks/{seq}?start_ms=0&end_ms=100",
            content=data,
            headers={
                **self.headers,
                "content-type": "audio/wav",
                "x-content-sha256": digest(data),
            },
        )

    def run_jobs(self):
        async def drain():
            while await self.worker.step():
                pass

        asyncio.run(drain())

    def test_capture_replay_recover_and_evidence(self):
        self.create()
        self.create()
        for _ in range(2):
            self.assertEqual(self.upload().status_code, 200)
        self.assertEqual(
            self.client.post(
                self.base + "/finalize",
                headers=self.headers,
                json={"duration_ms": 101, "tracks": {"mic": 2}},
            ).status_code,
            409,
        )
        manifest = {"duration_ms": 100, "tracks": {"mic": 1}}
        for _ in range(2):
            self.assertEqual(
                self.client.post(
                    self.base + "/finalize", headers=self.headers, json=manifest
                ).status_code,
                200,
            )
        self.assertEqual(self.worker.claim()["kind"], "speech")
        self.worker.recover()
        self.run_jobs()
        m = self.client.get(self.base, headers=self.headers).json()
        self.assertEqual(m["state"], "ready")
        action = m["summary"]["actions"][0]
        self.assertEqual(action["owner"], "Morgan")
        self.assertIsNone(action["due"])
        self.assertFalse(m["summary"]["decisions"])
        self.assertEqual(action["references"][0]["id"], m["evidence"][0]["id"])
        self.assertEqual(self.provider.calls.count("speech"), 1)
        self.assertEqual(
            self.client.put(
                self.base + "/tasks/" + action["task_id"],
                headers=self.headers,
                json={"completed": True},
            ).status_code,
            200,
        )
        self.assertEqual(
            self.client.get(self.base + "/audio/mic/0", headers=self.headers).content,
            wav(),
        )
        self.assertIn(
            "Morgan", self.client.get(self.base + "/export", headers=self.headers).text
        )
        self.assertEqual(
            self.client.get("/sessions/" + self.sid, headers=self.headers).status_code,
            200,
        )

    def test_retry_after_delete_and_tenant_isolation(self):
        self.create()
        self.upload()
        for suffix in ("", "/audio/mic/0", "/export"):
            self.assertEqual(
                self.client.get(self.base + suffix, headers=self.second).status_code,
                404,
            )
        self.assertEqual(
            self.client.delete(self.base, headers=self.second).status_code, 404
        )
        self.assertEqual(
            self.client.put(self.base, headers=self.second, json=self.meta).status_code,
            404,
        )
        self.assertEqual(
            self.client.delete(self.base, headers=self.headers).status_code, 200
        )
        self.assertEqual(
            self.client.put(
                self.base, headers=self.headers, json=self.meta
            ).status_code,
            410,
        )
        self.assertEqual(list(self.cfg.media.iterdir()), [])

    def test_transcript_import_search_and_ask(self):
        def send():
            return self.client.post(
                "/api/v1/import",
                headers=self.headers,
                files={"file": ("teams.vtt", VTT, "text/vtt")},
            )

        sid = send().json()["id"]
        self.assertEqual(send().json(), {"id": sid, "duplicate": True})
        self.provider.available = False
        self.assertFalse(asyncio.run(self.worker.step()))
        with self.store.connect() as db:
            row = db.execute(
                "SELECT state,attempts FROM jobs WHERE session_id=?", (sid,)
            ).fetchone()
            self.assertEqual(tuple(row), ("pending", 0))
        self.provider.available = True
        self.run_jobs()
        self.assertEqual(self.provider.calls.count("speech"), 0)
        self.assertEqual(
            len(
                self.client.get(
                    "/api/v1/search?q=checklist", headers=self.headers
                ).json()["results"]
            ),
            1,
        )
        self.assertFalse(
            self.client.get("/api/v1/search?q=checklist", headers=self.second).json()[
                "results"
            ]
        )
        question = {"question": "Who owns the network checklist?"}
        self.assertTrue(
            self.client.post("/api/v1/ask", headers=self.headers, json=question).json()[
                "references"
            ]
        )
        self.assertEqual(
            self.client.post("/api/v1/ask", headers=self.second, json=question).json()[
                "answer"
            ],
            EMPTY,
        )
        self.assertEqual(
            self.client.post(
                "/api/v1/ask", headers=self.second, json={**question, "session_id": sid}
            ).status_code,
            404,
        )
        other = self.client.post(
            "/api/v1/import",
            headers=self.second,
            data={"session_id": sid},
            files={"file": ("x.vtt", VTT, "text/vtt")},
        )
        self.assertEqual(other.status_code, 404)
        self.assertTrue(
            self.client.delete(
                "/api/v1/sessions/" + sid, headers=self.headers
            ).is_success
        )
        self.assertEqual(
            self.client.get("/api/v1/search?q=checklist", headers=self.headers).json()[
                "results"
            ],
            [],
        )

    def test_expiry_bookmark_and_fts(self):
        import base64

        self.create()
        self.upload()
        mids = [str(uuid4()), str(uuid4())]
        for i, mid in enumerate(mids):
            body = {
                "at_ms": 20,
                "text": "slide quartz",
                "bookmarked": bool(i),
                "image_base64": base64.b64encode(
                    b"\xff\xd8\xff\xe0test\xff\xd9"
                ).decode(),
            }
            for _ in range(2):
                self.assertEqual(
                    self.client.put(
                        self.base + "/moments/" + mid, json=body, headers=self.headers
                    ).status_code,
                    200,
                )
        self.assertEqual(
            self.client.post(
                self.base + "/finalize",
                headers=self.headers,
                json={"duration_ms": 200, "tracks": {"mic": 1}},
            ).status_code,
            200,
        )
        self.run_jobs()
        self.store.collect(now() + 8 * 86400)
        # The sweeper also intentionally expired the device credentials in the simulated future.
        with self.store.connect(write=True) as db:
            self.headers = {"Authorization": "Bearer " + new_token(db, "u1", "device")}
        self.assertEqual(
            self.client.get(
                self.base + "/audio/mic/0", headers=self.headers
            ).status_code,
            410,
        )
        self.assertEqual(
            self.client.get(
                self.base + "/images/" + mids[0], headers=self.headers
            ).status_code,
            410,
        )
        self.assertEqual(
            self.client.get(
                self.base + "/images/" + mids[1], headers=self.headers
            ).status_code,
            200,
        )
        self.assertEqual(
            len(
                self.client.get("/api/v1/search?q=quartz", headers=self.headers).json()[
                    "results"
                ]
            ),
            2,
        )

    def test_browser_csrf_and_one_time_native_exchange(self):
        self.client.cookies.set(COOKIE, self.browser)
        self.assertEqual(self.client.get("/").status_code, 200)
        self.assertEqual(self.client.put(self.base, json=self.meta).status_code, 403)
        self.assertEqual(
            self.client.put(
                self.base, headers={"X-SudoAI-CSRF": csrf(self.browser)}, json=self.meta
            ).status_code,
            200,
        )
        verifier = "X" * 50
        code = "one-time-test-code"
        with self.store.connect(write=True) as db:
            db.execute(
                "INSERT INTO exchanges VALUES(?,?,?,?)",
                (digest(code.encode()), "u1", pkce(verifier), now() + 60),
            )
        endpoint = "/api/v1/auth/exchange"
        self.assertEqual(
            self.client.post(
                endpoint, json={"verifier": "Y" * 50, "code": code}
            ).status_code,
            403,
        )
        body = {"verifier": verifier, "code": code}
        issued = self.client.post(endpoint, json=body).json()["access_token"]
        self.assertEqual(self.client.post(endpoint, json=body).status_code, 403)
        headers = {"Authorization": "Bearer " + issued}
        self.assertTrue(
            self.client.post("/api/v1/auth/revoke", headers=headers).is_success
        )
        self.assertEqual(
            self.client.get("/api/v1/me", headers=headers).status_code, 401
        )

    def test_real_audio_import(self):
        result = self.client.post(
            "/api/v1/import",
            headers=self.headers,
            files={"file": ("fixture.wav", wav(), "audio/wav")},
        )
        self.assertEqual(result.status_code, 200, result.text)
        self.run_jobs()
        m = self.client.get(
            "/api/v1/sessions/" + result.json()["id"], headers=self.headers
        ).json()
        self.assertEqual((m["duration_ms"], m["state"]), (100, "ready"))
