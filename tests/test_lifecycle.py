"""Synthetic retention boundary and permanent-asset deletion qualification."""

import base64
import unittest
from uuid import uuid4

import test_library

from sudoai.db import new_token


class LifecycleTests(unittest.TestCase):
    def test_retention_boundary_export_and_bookmark_deletion(self):
        fixture = test_library.LibraryTests()
        fixture.setUp()
        self.addCleanup(fixture.doCleanups)
        fixture.create()
        self.assertEqual(fixture.upload().status_code, 200)
        mid = str(uuid4())
        self.assertEqual(
            fixture.client.put(
                fixture.base + "/moments/" + mid,
                headers=fixture.headers,
                json={
                    "at_ms": 20,
                    "text": "Synthetic cobalt launch slide",
                    "bookmarked": True,
                    "image_base64": base64.b64encode(
                        b"\xff\xd8\xff\xe0synthetic\xff\xd9"
                    ).decode(),
                },
            ).status_code,
            200,
        )
        self.assertEqual(
            fixture.client.post(
                fixture.base + "/finalize",
                headers=fixture.headers,
                json={"duration_ms": 100, "tracks": {"mic": 1}},
            ).status_code,
            200,
        )
        fixture.run_jobs()
        with fixture.store.connect() as db:
            processed = db.execute(
                "SELECT processed_at FROM sessions WHERE id=?", (fixture.sid,)
            ).fetchone()[0]
            raw = fixture.store.asset_path(
                db.execute("SELECT asset FROM chunks").fetchone()[0]
            )
        fixture.store.collect(processed + 7 * 86400 - 1)
        self.assertTrue(raw.exists())
        fixture.store.collect(processed + 7 * 86400)
        self.assertFalse(raw.exists())
        with fixture.store.connect(write=True) as db:
            fixture.headers = {
                "Authorization": "Bearer " + new_token(db, "u1", "device")
            }
            fixture.second = {
                "Authorization": "Bearer " + new_token(db, "u2", "device")
            }
        exported = fixture.client.get(fixture.base + "/export", headers=fixture.headers)
        self.assertEqual(exported.status_code, 200)
        self.assertIn("Synthetic cobalt launch slide", exported.text)
        self.assertIn("Morgan owns", exported.text)
        self.assertIn("#e-", exported.text)
        self.assertEqual(
            fixture.client.get(
                fixture.base + "/images/" + mid, headers=fixture.second
            ).status_code,
            404,
        )
        self.assertEqual(
            fixture.client.get(
                fixture.base + "/images/" + mid, headers=fixture.headers
            ).status_code,
            200,
        )
        self.assertEqual(
            fixture.client.delete(fixture.base, headers=fixture.headers).status_code,
            200,
        )
        self.assertEqual(list((fixture.cfg.data / "bookmarks").iterdir()), [])
        self.assertEqual(list(fixture.cfg.media.iterdir()), [])
        with fixture.store.connect() as db:
            for table in ("sessions", "evidence", "chunks", "moments", "jobs", "usage"):
                self.assertEqual(
                    db.execute(f"SELECT count(*) FROM {table}").fetchone()[0], 0
                )
            self.assertEqual(
                db.execute("SELECT count(*) FROM evidence_fts").fetchone()[0], 0
            )
