import base64
import binascii
import wave
from datetime import datetime, timezone
from io import BytesIO
from typing import Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from pydantic import BaseModel, Field, field_validator

from .auth import principal
from .db import canonical, digest, session_for, uid

router = APIRouter(prefix="/api/v1")
TRACKS = {"mic", "system", "omi", "playback", "import"}
MIME = {
    "audio/wav",
    "audio/x-wav",
    "audio/mp4",
    "audio/m4a",
    "audio/x-m4a",
    "audio/ogg",
    "audio/flac",
    "audio/mpeg",
    "audio/webm",
}
MAX_MS = 7 * 24 * 3600 * 1000


class SessionInput(BaseModel):
    title: str = Field(min_length=1, max_length=300)
    started_at: datetime
    device: Literal["macos", "android", "import"]

    @field_validator("started_at")
    @classmethod
    def with_zone(cls, value):
        if value.tzinfo is None:
            raise ValueError("Include the time zone")
        return value.astimezone(timezone.utc)


class Moment(BaseModel):
    at_ms: int = Field(ge=0, le=MAX_MS)
    text: str = Field(default="", max_length=35000)
    bookmarked: bool = False
    image_base64: str | None = Field(default=None, max_length=2800000)


class Gap(BaseModel):
    track: Literal["mic", "system", "omi", "playback", "import", "screen"]
    start_ms: int = Field(ge=0, le=MAX_MS)
    end_ms: int = Field(ge=0, le=MAX_MS)
    reason: str = Field(min_length=1, max_length=500)


class Manifest(BaseModel):
    duration_ms: int = Field(ge=0, le=MAX_MS)
    tracks: dict[str, int]


def writable(row):
    if row["manifest"] is not None:
        raise HTTPException(409, "Meeting was already finalized")


def check_audio(body, mime):
    if mime not in MIME:
        raise HTTPException(415, "Unsupported audio type")
    signatures = {
        "audio/ogg": body.startswith(b"OggS"),
        "audio/flac": body.startswith(b"fLaC"),
        "audio/webm": body.startswith(bytes.fromhex("1a45dfa3")),
        "audio/mpeg": body.startswith(b"ID3")
        or (len(body) > 1 and body[0] == 255 and body[1] & 224 == 224),
    }
    if mime in ("audio/wav", "audio/x-wav"):
        try:
            with wave.open(BytesIO(body)) as source:
                if not source.getnframes() or source.getframerate() <= 0:
                    raise ValueError
        except (wave.Error, EOFError, ValueError) as exc:
            raise HTTPException(422, "Invalid WAV chunk") from exc
    elif not signatures.get(mime, body[4:8] == b"ftyp"):
        raise HTTPException(422, "Audio header does not match the content type")


def queue_processing(db, sid):
    row = db.execute("SELECT revision FROM sessions WHERE id=?", (sid,)).fetchone()
    revision = row[0]
    imported = db.execute(
        "SELECT 1 FROM imports WHERE session_id=? AND kind='vtt'", (sid,)
    ).fetchone()
    if not imported:
        db.execute(
            """INSERT OR IGNORE INTO jobs(session_id,kind,track,seq,revision)
                      SELECT c.session_id,'speech',c.track,c.seq,? FROM chunks c WHERE c.session_id=?
                      AND NOT EXISTS(SELECT 1 FROM evidence e WHERE e.session_id=c.session_id AND e.track=c.track AND e.chunk_seq=c.seq)""",
            (revision, sid),
        )
    db.execute(
        "INSERT OR IGNORE INTO jobs(session_id,kind,revision) VALUES(?,'summary',?)",
        (sid, revision),
    )
    db.execute("UPDATE sessions SET state='processing',error=NULL WHERE id=?", (sid,))


@router.put("/sessions/{sid}")
def create(sid: UUID, body: SessionInput, request: Request, user=Depends(principal)):
    sid = str(sid)
    fields = (body.title, body.started_at.isoformat(), body.device)
    with request.app.state.store.connect(write=True) as db:
        tomb = db.execute(
            "SELECT owner FROM deleted_sessions WHERE id=?", (sid,)
        ).fetchone()
        if tomb:
            raise HTTPException(
                410 if tomb[0] == user["id"] else 404, "Meeting unavailable"
            )
        db.execute(
            "INSERT OR IGNORE INTO sessions(id,owner,title,started_at,device) VALUES(?,?,?,?,?)",
            (sid, user["id"], *fields),
        )
        row = session_for(db, user["id"], sid)
        if tuple(row[k] for k in ("title", "started_at", "device")) != fields:
            raise HTTPException(409, "Session identity has different metadata")
    return {"id": sid}


@router.put("/sessions/{sid}/tracks/{track}/chunks/{seq}")
async def chunk(
    sid: UUID,
    track: str,
    seq: int,
    request: Request,
    start_ms: int = Query(ge=0, le=MAX_MS),
    end_ms: int = Query(gt=0, le=MAX_MS),
    user=Depends(principal),
):
    sid = str(sid)
    if track not in TRACKS or not 0 <= seq < 100000 or end_ms <= start_ms:
        raise HTTPException(422, "Invalid chunk location")
    expected = request.headers.get("X-Content-SHA256", "")
    mime = request.headers.get("content-type", "").split(";")[0].lower()
    body = await request.body()
    if not body or len(body) > 8 * 1024**2:
        raise HTTPException(413, "Chunk must fit in 8 MiB")
    if digest(body) != expected:
        raise HTTPException(422, "Audio checksum mismatch")
    check_audio(body, mime)
    store = request.app.state.store
    with store.connect(write=True) as db:
        session = session_for(db, user["id"], sid)
        row = db.execute(
            "SELECT digest,start_ms,end_ms,mime FROM chunks WHERE session_id=? AND track=? AND seq=?",
            (sid, track, seq),
        ).fetchone()
        if row:
            if tuple(row) != (expected, start_ms, end_ms, mime):
                raise HTTPException(409, "Chunk identity has different content")
        else:
            writable(session)
            asset = store.save_asset(body)
            db.execute(
                "INSERT INTO chunks VALUES(?,?,?,?,?,?,?,?)",
                (sid, track, seq, start_ms, end_ms, mime, expected, asset),
            )
    return {"seq": seq, "sha256": expected}


@router.put("/sessions/{sid}/moments/{mid}")
def moment(
    sid: UUID, mid: UUID, body: Moment, request: Request, user=Depends(principal)
):
    sid, mid = str(sid), str(mid)
    image = b""
    if body.image_base64:
        try:
            image = base64.b64decode(body.image_base64, validate=True)
        except (binascii.Error, ValueError) as exc:
            raise HTTPException(422, "Invalid image encoding") from exc
        if (
            len(image) > 2 * 1024**2
            or not image.startswith(b"\xff\xd8\xff")
            or not image.endswith(b"\xff\xd9")
        ):
            raise HTTPException(422, "Expected a JPEG of at most 2 MiB")
    checksum = digest(
        canonical([body.at_ms, body.text, body.bookmarked, digest(image)]).encode()
    )
    store = request.app.state.store
    with store.connect(write=True) as db:
        session = session_for(db, user["id"], sid)
        row = db.execute(
            "SELECT digest FROM moments WHERE id=? AND session_id=?", (mid, sid)
        ).fetchone()
        if row and row[0] != checksum:
            raise HTTPException(409, "Moment identity has different content")
        if not row:
            writable(session)
            asset = store.save_asset(image, body.bookmarked) if image else None
            db.execute(
                "INSERT INTO moments VALUES(?,?,?,?,?,?,?)",
                (mid, sid, body.at_ms, body.text, body.bookmarked, asset, checksum),
            )
            db.execute(
                "INSERT INTO evidence VALUES(?,?,'screen','screen',?,?,?,?,NULL,?)",
                (
                    uid(),
                    sid,
                    body.at_ms,
                    body.at_ms,
                    "Bookmarked screen" if body.bookmarked else "Screen",
                    body.text,
                    mid,
                ),
            )
    return {"id": mid}


@router.put("/sessions/{sid}/gaps/{gid}")
def gap(sid: UUID, gid: UUID, body: Gap, request: Request, user=Depends(principal)):
    sid, gid = str(sid), str(gid)
    if body.end_ms < body.start_ms:
        raise HTTPException(422, "Invalid gap")
    fields = (body.track, body.start_ms, body.end_ms, body.reason)
    with request.app.state.store.connect(write=True) as db:
        session = session_for(db, user["id"], sid)
        row = db.execute(
            "SELECT track,start_ms,end_ms,reason FROM gaps WHERE id=? AND session_id=?",
            (gid, sid),
        ).fetchone()
        if row and tuple(row) != fields:
            raise HTTPException(409, "Gap identity has different content")
        if not row:
            writable(session)
            db.execute("INSERT INTO gaps VALUES(?,?,?,?,?,?)", (gid, sid, *fields))
    return {"id": gid}


@router.post("/sessions/{sid}/finalize")
def finalize(sid: UUID, body: Manifest, request: Request, user=Depends(principal)):
    sid = str(sid)
    if set(body.tracks) - TRACKS or any(
        n < 0 or n > 100000 for n in body.tracks.values()
    ):
        raise HTTPException(422, "Invalid track counts")
    value = canonical(body.model_dump())
    with request.app.state.store.connect(write=True) as db:
        session = session_for(db, user["id"], sid)
        if session["manifest"] and session["manifest"] != value:
            raise HTTPException(409, "Final manifest has different content")
        if not session["manifest"]:
            rows = db.execute(
                "SELECT track,count(*) n,max(seq) last,max(end_ms) end_ms FROM chunks WHERE session_id=? GROUP BY track",
                (sid,),
            ).fetchall()
            actual = {r["track"]: r["n"] for r in rows}
            if actual != {k: n for k, n in body.tracks.items() if n} or any(
                r["last"] != r["n"] - 1 or r["end_ms"] > body.duration_ms for r in rows
            ):
                raise HTTPException(409, "Manifest does not match durable audio chunks")
            last_event = db.execute(
                "SELECT max(t) FROM (SELECT max(at_ms) t FROM moments WHERE session_id=? UNION ALL SELECT max(end_ms) FROM gaps WHERE session_id=?)",
                (sid, sid),
            ).fetchone()[0]
            if last_event is not None and last_event > body.duration_ms:
                raise HTTPException(409, "Manifest ends before a captured event")
            db.execute(
                "UPDATE sessions SET manifest=?,duration_ms=? WHERE id=?",
                (value, body.duration_ms, sid),
            )
            queue_processing(db, sid)
    return {"id": sid, "accepted": True}


@router.get("/sessions/{sid}/audio/{track}/{seq}")
def audio(sid: UUID, track: str, seq: int, request: Request, user=Depends(principal)):
    return asset_response(
        request,
        user,
        str(sid),
        "SELECT asset,mime FROM chunks WHERE session_id=? AND track=? AND seq=?",
        (track, seq),
    )


@router.get("/sessions/{sid}/images/{mid}")
def screenshot(sid: UUID, mid: UUID, request: Request, user=Depends(principal)):
    return asset_response(
        request,
        user,
        str(sid),
        "SELECT asset,'image/jpeg' FROM moments WHERE session_id=? AND id=?",
        (str(mid),),
    )


def asset_response(request, user, sid, query, params):
    store = request.app.state.store
    with store.connect() as db:
        session_for(db, user["id"], sid)
        row = db.execute(query, (sid, *params)).fetchone()
        if row is None:
            raise HTTPException(404, "Media unavailable")
        if not row[0]:
            raise HTTPException(410, "Original media expired")
        try:
            return Response(store.asset_path(row[0]).read_bytes(), media_type=row[1])
        except FileNotFoundError as exc:
            raise HTTPException(410, "Original media unavailable") from exc
