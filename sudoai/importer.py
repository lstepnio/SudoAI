import codecs
import html
import re
import shutil
import subprocess
import tempfile
import wave
from datetime import datetime, timezone
from pathlib import Path
from uuid import UUID

from fastapi import APIRouter, Depends, File, Form, HTTPException, Request, UploadFile

from .auth import principal
from .capture import queue_processing
from .db import canonical, digest, session_for, uid

router = APIRouter(prefix="/api/v1")
STAMP = r"(?:\d{1,3}:)?\d{2}:\d{2}\.\d{3}"
TIMES = re.compile(rf"^({STAMP})\s+-->\s+({STAMP})(?:\s.*)?$")
DEMUXERS = {
    ".wav": "wav",
    ".m4a": "mov",
    ".mp4": "mov",
    ".mp3": "mp3",
    ".ogg": "ogg",
    ".opus": "ogg",
    ".webm": "matroska",
    ".flac": "flac",
}
AUDIO_LIMIT = 160 * 1024**2


def milliseconds(value):
    clock, fraction = value.split(".")
    seconds = 0
    for part in clock.split(":"):
        seconds = seconds * 60 + int(part)
    return seconds * 1000 + int(fraction)


def parse_vtt(raw):
    try:
        text = raw.decode(
            "utf-16"
            if raw.startswith((codecs.BOM_UTF16_LE, codecs.BOM_UTF16_BE))
            else "utf-8-sig"
        )
    except UnicodeError as exc:
        raise HTTPException(422, "Unreadable WebVTT encoding") from exc
    text = text.replace("\r\n", "\n").replace("\r", "\n")
    if not text.split("\n", 1)[0].strip().startswith("WEBVTT"):
        raise HTTPException(422, "Expected a WebVTT header")
    cues, seen = [], set()
    for block in re.split(r"\n[ \t]*\n", text)[1:]:
        lines = block.strip().splitlines()
        if not lines or lines[0].startswith(("NOTE", "STYLE", "REGION")):
            continue
        index = 0 if "-->" in lines[0] else 1
        if index >= len(lines) or not (match := TIMES.match(lines[index].strip())):
            continue
        start, end = (milliseconds(v) for v in match.groups())
        if end < start or end > 7 * 86400000:
            raise HTTPException(422, "Invalid WebVTT cue timing")
        body = " ".join(lines[index + 1 :])
        voice = re.search(r"<v(?:\.[\w-]+)*\s+([^>]+)>", body, re.I)
        speaker = html.unescape(voice[1]).strip()[:180] if voice else "Teams transcript"
        words = " ".join(html.unescape(re.sub(r"<[^>]+>", "", body)).split())
        key = (start, end, speaker, words)
        if words and key not in seen:
            cues.append(key)
            seen.add(key)
    if not cues:
        raise HTTPException(422, "The transcript has no timed speech")
    if len(cues) > 60000:
        raise HTTPException(413, "Transcript is too large")
    return cues


def transcode(source, folder, demuxer):
    if not shutil.which("ffmpeg"):
        raise HTTPException(503, "Audio import requires FFmpeg on the server")
    command = [
        "ffmpeg",
        "-nostdin",
        "-hide_banner",
        "-loglevel",
        "error",
        "-protocol_whitelist",
        "file,pipe",
        "-f",
        demuxer,
        "-i",
        str(source),
        "-vn",
        "-map",
        "0:a:0",
        "-t",
        "14401",
        "-ar",
        "16000",
        "-ac",
        "1",
        "-c:a",
        "pcm_s16le",
        "-f",
        "segment",
        "-segment_time",
        "25",
        "-reset_timestamps",
        "1",
        str(folder / "chunk-%05d.wav"),
    ]
    try:
        result = subprocess.run(
            command,
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            timeout=420,
        )
    except subprocess.TimeoutExpired as exc:
        raise HTTPException(422, "Audio import exceeded its processing limit") from exc
    files = sorted(folder.glob("chunk-*.wav"))
    if result.returncode or not files:
        raise HTTPException(422, "Could not decode the audio file")
    chunks, samples = [], 0
    for path in files:
        with wave.open(str(path)) as audio:
            count = audio.getnframes()
        if not count:
            continue
        start = samples * 1000 // 16000
        samples += count
        if samples > 14400 * 16000:
            raise HTTPException(413, "Imports are limited to four hours")
        chunks.append((start, samples * 1000 // 16000, path))
    if not chunks:
        raise HTTPException(422, "Audio file contains no samples")
    return chunks


@router.post("/import")
def import_file(
    request: Request,
    file: UploadFile = File(),
    session_id: UUID | None = Form(None),
    title: str | None = Form(None, max_length=300),
    user=Depends(principal),
):
    import hashlib

    store = request.app.state.store
    scope = str(session_id) if session_id else ""
    name = Path(file.filename or "Meeting").name
    suffix = Path(name).suffix.lower()
    is_vtt = suffix == ".vtt"
    if not is_vtt and suffix not in DEMUXERS:
        raise HTTPException(415, "Choose a WebVTT transcript or supported audio file")
    limit = 4 * 1024**2 if is_vtt else AUDIO_LIMIT
    with tempfile.TemporaryDirectory() as temp:
        folder = Path(temp)
        source = folder / ("input" + suffix)
        hasher = hashlib.sha256()
        size = 0
        with source.open("wb") as target:
            while block := file.file.read(1024 * 1024):
                size += len(block)
                if size > limit:
                    raise HTTPException(413, "Import is too large")
                hasher.update(block)
                target.write(block)
        checksum = hasher.hexdigest()

        def existing(db):
            if scope:
                session_for(db, user["id"], scope)
            return db.execute(
                "SELECT session_id FROM imports WHERE owner=? AND scope=? AND digest=?",
                (user["id"], scope, checksum),
            ).fetchone()

        with store.connect() as db:
            if row := existing(db):
                return {"id": row[0], "duplicate": True}
        cues = parse_vtt(source.read_bytes()) if is_vtt else []
        chunks = [] if is_vtt else transcode(source, folder, DEMUXERS[suffix])
        with store.connect(write=True) as db:
            if row := existing(db):
                return {"id": row[0], "duplicate": True}
            sid = scope or uid()
            if scope:
                meeting = session_for(db, user["id"], sid)
                if meeting["manifest"] is None:
                    raise HTTPException(
                        409, "Finish this capture before attaching an import"
                    )
                if db.execute(
                    "SELECT 1 FROM imports WHERE session_id=? AND kind=?",
                    (sid, "vtt" if is_vtt else "audio"),
                ).fetchone():
                    raise HTTPException(
                        409, "This meeting already has a different import of this type"
                    )
            else:
                db.execute(
                    "INSERT INTO sessions(id,owner,title,started_at,device) VALUES(?,?,?,?, 'import')",
                    (
                        sid,
                        user["id"],
                        title or Path(name).stem[:300] or "Imported meeting",
                        datetime.now(timezone.utc).isoformat(),
                    ),
                )
            db.execute(
                "INSERT INTO imports VALUES(?,?,?,?,?)",
                (user["id"], scope, checksum, sid, "vtt" if is_vtt else "audio"),
            )
            for start, end, speaker, words in cues:
                db.execute(
                    "INSERT INTO evidence VALUES(?,?,'teams','import',?,?,?,?,NULL,NULL)",
                    (uid(), sid, start, end, speaker, words),
                )
            for seq, (start, end, path) in enumerate(chunks):
                data = path.read_bytes()
                asset = store.save_asset(data)
                db.execute(
                    "INSERT INTO chunks VALUES(?,'import',?,?,?,'audio/wav',?,?)",
                    (sid, seq, start, end, digest(data), asset),
                )
            duration = max(
                [end for _, end, _, _ in cues] + [end for _, end, _ in chunks],
                default=0,
            )
            new_manifest = canonical(
                {
                    "duration_ms": duration,
                    "tracks": {"import": len(chunks)} if chunks else {},
                }
            )
            db.execute(
                "UPDATE sessions SET duration_ms=max(duration_ms,?),manifest=coalesce(manifest,?),revision=revision+1,summary=NULL,processed_at=NULL WHERE id=?",
                (duration, new_manifest, sid),
            )
            db.execute("DELETE FROM jobs WHERE session_id=?", (sid,))
            queue_processing(db, sid)
    return {"id": sid, "duplicate": False}
