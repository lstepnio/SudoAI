import hashlib
import json
import os
import re
import secrets
import sqlite3
import tempfile
import time
import uuid
from contextlib import contextmanager
from pathlib import Path

from fastapi import HTTPException

SCHEMA = """
CREATE TABLE IF NOT EXISTS users(id TEXT PRIMARY KEY, subject TEXT UNIQUE NOT NULL, name TEXT NOT NULL);
CREATE TABLE IF NOT EXISTS tokens(digest TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE, kind TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS auth_flows(state TEXT PRIMARY KEY, browser TEXT NOT NULL, nonce TEXT NOT NULL, verifier TEXT NOT NULL, challenge TEXT, app_state TEXT, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS exchanges(digest TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), challenge TEXT NOT NULL, expires INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS sessions(id TEXT PRIMARY KEY, owner TEXT NOT NULL REFERENCES users(id), title TEXT NOT NULL, started_at TEXT NOT NULL, device TEXT NOT NULL, state TEXT NOT NULL DEFAULT 'capturing', duration_ms INTEGER NOT NULL DEFAULT 0, manifest TEXT, summary TEXT, processed_at INTEGER, revision INTEGER NOT NULL DEFAULT 0, error TEXT);
CREATE INDEX IF NOT EXISTS sessions_owner ON sessions(owner,started_at DESC);
CREATE TABLE IF NOT EXISTS deleted_sessions(id TEXT PRIMARY KEY, owner TEXT NOT NULL, deleted_at INTEGER NOT NULL);
CREATE TABLE IF NOT EXISTS chunks(session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, track TEXT NOT NULL, seq INTEGER NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, mime TEXT NOT NULL, digest TEXT NOT NULL, asset TEXT, PRIMARY KEY(session_id,track,seq));
CREATE TABLE IF NOT EXISTS moments(id TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, at_ms INTEGER NOT NULL, text TEXT NOT NULL, bookmarked INTEGER NOT NULL, asset TEXT, digest TEXT NOT NULL, PRIMARY KEY(session_id,id));
CREATE TABLE IF NOT EXISTS gaps(id TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, track TEXT NOT NULL, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, reason TEXT NOT NULL, PRIMARY KEY(session_id,id));
CREATE TABLE IF NOT EXISTS evidence(id TEXT PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, kind TEXT NOT NULL, track TEXT, start_ms INTEGER NOT NULL, end_ms INTEGER NOT NULL, speaker TEXT NOT NULL, text TEXT NOT NULL, chunk_seq INTEGER, moment_id TEXT);
CREATE INDEX IF NOT EXISTS evidence_session ON evidence(session_id,start_ms);
CREATE VIRTUAL TABLE IF NOT EXISTS evidence_fts USING fts5(text, tokenize='unicode61 remove_diacritics 2');
CREATE TRIGGER IF NOT EXISTS evidence_insert AFTER INSERT ON evidence BEGIN INSERT INTO evidence_fts(rowid,text) VALUES(new.rowid,new.text); END;
CREATE TRIGGER IF NOT EXISTS evidence_delete AFTER DELETE ON evidence BEGIN DELETE FROM evidence_fts WHERE rowid=old.rowid; END;
CREATE TABLE IF NOT EXISTS jobs(id INTEGER PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, kind TEXT NOT NULL, track TEXT NOT NULL DEFAULT '', seq INTEGER NOT NULL DEFAULT -1, revision INTEGER NOT NULL, state TEXT NOT NULL DEFAULT 'pending', attempts INTEGER NOT NULL DEFAULT 0, due INTEGER NOT NULL DEFAULT 0, lease TEXT, error TEXT, UNIQUE(session_id,kind,track,seq,revision));
CREATE INDEX IF NOT EXISTS jobs_due ON jobs(state,due);
CREATE TABLE IF NOT EXISTS imports(owner TEXT NOT NULL, scope TEXT NOT NULL, digest TEXT NOT NULL, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, kind TEXT NOT NULL, PRIMARY KEY(owner,scope,digest));
CREATE TABLE IF NOT EXISTS usage(id INTEGER PRIMARY KEY, session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE, operation TEXT NOT NULL, model TEXT NOT NULL, audio_ms INTEGER NOT NULL DEFAULT 0, input_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0);
CREATE TABLE IF NOT EXISTS garbage(asset TEXT PRIMARY KEY);
CREATE TABLE IF NOT EXISTS task_checks(session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,task_id TEXT NOT NULL,PRIMARY KEY(session_id,task_id));
CREATE TABLE IF NOT EXISTS ask_limits(owner TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,hour INTEGER NOT NULL,n INTEGER NOT NULL,PRIMARY KEY(owner,hour));
"""


def uid():
    return str(uuid.uuid4())


def digest(data):
    return hashlib.sha256(data).hexdigest()


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=False)


def now():
    return int(time.time())


class Store:
    def __init__(self, settings):
        self.settings = settings
        for directory in (settings.data, settings.media, settings.data / "bookmarks"):
            directory.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path = settings.data / "library.sqlite3"
        db = sqlite3.connect(self.path)
        try:
            db.execute("PRAGMA journal_mode=WAL")
            db.executescript(SCHEMA)
        finally:
            db.close()

    @contextmanager
    def connect(self, write=False):
        db = sqlite3.connect(self.path, timeout=12, isolation_level=None)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA foreign_keys=ON")
        db.execute("PRAGMA synchronous=FULL")
        try:
            db.execute("BEGIN IMMEDIATE" if write else "BEGIN")
            yield db
            if db.in_transaction:
                db.commit()
        except BaseException:
            db.rollback()
            raise
        finally:
            db.close()

    def asset_path(self, key):
        domain, filename = key.split("/", 1)
        if domain not in {"raw", "bookmark"} or Path(filename).name != filename:
            raise ValueError("Invalid asset reference")
        base = (
            self.settings.media if domain == "raw" else self.settings.data / "bookmarks"
        )
        return base / filename

    def save_asset(self, data, pinned=False):
        key = ("bookmark/" if pinned else "raw/") + uid()
        path = self.asset_path(key)
        with tempfile.NamedTemporaryFile(
            dir=path.parent, prefix=".sudoai-stage-", delete=False
        ) as f:
            try:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            except BaseException:
                os.unlink(f.name)
                raise
        os.replace(f.name, path)
        descriptor = os.open(path.parent, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return key

    def collect(self, current=None):
        current = now() if current is None else current
        with self.connect(write=True) as db:
            for table, extra in (("chunks", ""), ("moments", " AND bookmarked=0")):
                where = (
                    "asset IS NOT NULL AND session_id IN (SELECT id FROM sessions WHERE processed_at<=?)"
                    + extra
                )
                db.execute(
                    f"INSERT OR IGNORE INTO garbage SELECT asset FROM {table} WHERE {where}",
                    (current - 7 * 86400,),
                )
                db.execute(
                    f"UPDATE {table} SET asset=NULL WHERE {where}",
                    (current - 7 * 86400,),
                )
            for row in db.execute("SELECT asset FROM garbage").fetchall():
                self.asset_path(row[0]).unlink(missing_ok=True)
                db.execute("DELETE FROM garbage WHERE asset=?", (row[0],))
            for table in ("tokens", "auth_flows", "exchanges"):
                db.execute(f"DELETE FROM {table} WHERE expires<=?", (current,))
            # A crash between a file rename and SQLite commit can leave one unreferenced file.
            referenced = {
                r[0]
                for r in db.execute(
                    "SELECT asset FROM chunks WHERE asset IS NOT NULL UNION SELECT asset FROM moments WHERE asset IS NOT NULL"
                )
            }
            for prefix, directory in (
                ("raw/", self.settings.media),
                ("bookmark/", self.settings.data / "bookmarks"),
            ):
                for path in directory.iterdir():
                    if (
                        (
                            path.name.startswith(".sudoai-stage-")
                            or re.fullmatch(
                                r"[a-f0-9]{8}(?:-[a-f0-9]{4}){3}-[a-f0-9]{12}",
                                path.name,
                            )
                        )
                        and path.is_file()
                        and prefix + path.name not in referenced
                        and path.stat().st_mtime < current - 3600
                    ):
                        path.unlink(missing_ok=True)


def session_for(db, owner, sid):
    row = db.execute(
        "SELECT * FROM sessions WHERE id=? AND owner=?", (sid, owner)
    ).fetchone()
    if row is None:
        raise HTTPException(404, "Meeting unavailable")
    return row


def new_token(db, owner, kind):
    token = secrets.token_urlsafe(40)
    lifetime = 8 * 3600 if kind == "browser" else 7 * 86400
    db.execute(
        "INSERT INTO tokens VALUES(?,?,?,?)",
        (digest(token.encode()), owner, kind, now() + lifetime),
    )
    return token
