import base64
import tempfile
import unittest
from pathlib import Path
from uuid import uuid4

from fastapi.testclient import TestClient

from sudoai.config import Settings
from sudoai.db import new_token
from sudoai.main import create_app
from sudoai.manage import backup, restore, verify


class RecoveryTests(unittest.TestCase):
    def test_restore_preserves_bookmarks_and_evidence_but_expires_credentials(self):
        with tempfile.TemporaryDirectory() as temp:
            root = Path(temp)
            cfg = Settings(data=root / "data", media=root / "raw", run_worker=False)
            app = create_app(cfg)
            with TestClient(app, base_url=cfg.public_url) as client:
                original = app.state.store
                with original.connect(write=True) as db:
                    db.execute("INSERT INTO users VALUES ('owner','subject','Member')")
                    old_token = new_token(db, "owner", "device")
                auth = {"authorization": "Bearer " + old_token}
                sid = str(uuid4())
                base = "/api/v1/sessions/" + sid
                self.assertTrue(
                    client.put(
                        base,
                        headers=auth,
                        json={
                            "title": "Rollout",
                            "device": "macos",
                            "started_at": "2026-11-25T19:00:00Z",
                        },
                    ).is_success
                )
                mids = [str(uuid4()), str(uuid4())]
                jpeg = b"\xff\xd8\xff\xe0synthetic\xff\xd9"
                for index, mid in enumerate(mids):
                    self.assertTrue(
                        client.put(
                            base + "/moments/" + mid,
                            headers=auth,
                            json={
                                "at_ms": 10,
                                "text": "rollout quartz",
                                "bookmarked": bool(index),
                                "image_base64": base64.b64encode(jpeg).decode(),
                            },
                        ).is_success
                    )
                backup(original, root / "snapshot")
                self.assertEqual(verify(root / "snapshot"), 1)
                with self.assertRaises(ValueError):
                    backup(original, root / "snapshot")
                restore(root / "snapshot", root / "restored")
                with self.assertRaises(ValueError):
                    restore(root / "snapshot", root / "restored")
            restored = Settings(
                data=root / "restored", media=root / "freshraw", run_worker=False
            )
            new_app = create_app(restored)
            with TestClient(new_app, base_url=cfg.public_url) as client:
                self.assertEqual(client.get(base, headers=auth).status_code, 401)
                with new_app.state.store.connect(write=True) as db:
                    token = new_token(db, "owner", "device")
                auth = {"authorization": "Bearer " + token}
                hits = client.get("/api/v1/search?q=quartz", headers=auth).json()[
                    "results"
                ]
                self.assertEqual(len(hits), 2)
                self.assertEqual(
                    client.get(base + "/images/" + mids[0], headers=auth).status_code,
                    410,
                )
                self.assertEqual(
                    client.get(base + "/images/" + mids[1], headers=auth).content, jpeg
                )
                page = client.get("/sessions/" + sid, headers=auth).text
                self.assertTrue(all('id="e-' + hit["id"] + '"' in page for hit in hits))
            (
                root
                / "snapshot"
                / "bookmarks"
                / next((root / "snapshot" / "bookmarks").iterdir()).name
            ).write_bytes(b"damaged")
            with self.assertRaises(ValueError):
                verify(root / "snapshot")
