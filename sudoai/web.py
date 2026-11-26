import hashlib
import json
from pathlib import Path
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.responses import FileResponse, RedirectResponse
from fastapi.templating import Jinja2Templates
from pydantic import BaseModel, Field

from .ai import answer, search
from .auth import COOKIE, csrf, principal
from .db import now, session_for

router = APIRouter()
templates = Jinja2Templates(directory=str(Path(__file__).with_name("templates")))


def clock(ms):
    sec = max(0, int(ms or 0)) // 1000
    return (
        f"{sec // 3600}:{sec // 60 % 60:02}:{sec % 60:02}"
        if sec >= 3600
        else f"{sec // 60}:{sec % 60:02}"
    )


templates.env.filters["clock"] = clock


def summary_for(db, row):
    summary = (
        json.loads(row["summary"])
        if row["summary"]
        else {"decisions": [], "actions": [], "highlights": []}
    )
    checked = {
        r[0]
        for r in db.execute(
            "SELECT task_id FROM task_checks WHERE session_id=?", (row["id"],)
        )
    }
    for action in summary["actions"]:
        action["task_id"] = hashlib.sha256(action["text"].encode()).hexdigest()[:24]
        action["completed"] = action["task_id"] in checked
    return summary


def meeting_list(db, owner):
    return [
        dict(r)
        for r in db.execute(
            """SELECT s.id,s.title,s.started_at,s.device,s.duration_ms,s.state,s.error,
      (SELECT count(*) FROM moments WHERE session_id=s.id) AS moments
      FROM sessions s WHERE owner=? ORDER BY started_at DESC,id""",
            (owner,),
        )
    ]


def detail(db, owner, sid):
    base = session_for(db, owner, sid)
    m = {k: base[k] for k in base.keys() if k not in {"owner", "manifest"}}
    m["summary"] = summary_for(db, base)
    m["evidence"] = [
        dict(r)
        for r in db.execute(
            """SELECT e.*, c.asset IS NOT NULL AS has_audio,
      m.asset IS NOT NULL AS has_image, coalesce(m.bookmarked,0) AS bookmarked
      FROM evidence e LEFT JOIN chunks c ON c.session_id=e.session_id AND c.track=e.track AND c.seq=e.chunk_seq
      LEFT JOIN moments m ON m.session_id=e.session_id AND m.id=e.moment_id WHERE e.session_id=? ORDER BY start_ms,e.id""",
            (sid,),
        )
    ]
    m["gaps"] = [
        dict(r)
        for r in db.execute(
            "SELECT track,start_ms,end_ms,reason FROM gaps WHERE session_id=? ORDER BY start_ms",
            (sid,),
        )
    ]
    m["usage"] = [
        dict(r)
        for r in db.execute(
            "SELECT operation,model,sum(audio_ms) audio_ms,sum(input_tokens) input_tokens,sum(output_tokens) output_tokens,count(*) requests FROM usage WHERE session_id=? GROUP BY operation,model",
            (sid,),
        )
    ]
    m["retries"] = db.execute(
        "SELECT coalesce(sum(max(0,attempts-1)),0) FROM jobs WHERE session_id=?", (sid,)
    ).fetchone()[0]
    return m


def template(request, name, user=None, **context):
    return templates.TemplateResponse(
        request=request,
        name=name,
        context={
            "user": user,
            "csrf": csrf(request.cookies.get(COOKIE, "")),
            "section": "library",
            "ai_available": request.app.state.worker.available if user else False,
            "android_build": (
                request.app.state.settings.data / "distribution" / "SudoAI-Android.apk"
            ).is_file(),
            **context,
        },
    )


@router.get("/downloads/android")
def download_android(request: Request, user=Depends(principal)):
    path = request.app.state.settings.data / "distribution" / "SudoAI-Android.apk"
    if not path.is_file():
        raise HTTPException(404, "Android pilot build unavailable")
    return FileResponse(
        path, filename=path.name, media_type="application/vnd.android.package-archive"
    )


@router.get("/")
def home(request: Request, q: str = "", view: str = "library"):
    try:
        user = principal(request)
    except HTTPException as exc:
        if exc.status_code == 401:
            return template(request, "welcome.html")
        raise
    if len(q) > 500:
        raise HTTPException(422, "Shorten the search")
    with request.app.state.store.connect() as db:
        meetings = meeting_list(db, user["id"])
        results = search(db, user["id"], q) if q.strip() else []
        tasks = []
        if view == "actions":
            for row in db.execute(
                "SELECT * FROM sessions WHERE owner=? AND summary IS NOT NULL ORDER BY started_at DESC",
                (user["id"],),
            ):
                tasks.extend(
                    {**task, "meeting": row["title"], "session_id": row["id"]}
                    for task in summary_for(db, row)["actions"]
                )
    return template(
        request,
        "library.html",
        user,
        meetings=meetings,
        results=results,
        q=q,
        section="actions" if view == "actions" else "library",
        tasks=tasks,
    )


@router.get("/sessions/{sid}")
def page(request: Request, sid: UUID):
    try:
        user = principal(request)
    except HTTPException as exc:
        if exc.status_code == 401:
            return RedirectResponse("/auth/login", 303)
        raise
    with request.app.state.store.connect() as db:
        meeting = detail(db, user["id"], str(sid))
    return template(request, "meeting.html", user, m=meeting)


@router.get("/api/v1/sessions")
def sessions(request: Request, user=Depends(principal)):
    with request.app.state.store.connect() as db:
        return {"sessions": meeting_list(db, user["id"])}


@router.get("/api/v1/sessions/{sid}")
def get_session(request: Request, sid: UUID, user=Depends(principal)):
    with request.app.state.store.connect() as db:
        return detail(db, user["id"], str(sid))


@router.get("/api/v1/search")
def find(
    request: Request, q: str, session_id: UUID | None = None, user=Depends(principal)
):
    if not 1 <= len(q) <= 500:
        raise HTTPException(422, "Enter a search of up to 500 characters")
    with request.app.state.store.connect() as db:
        if session_id:
            session_for(db, user["id"], str(session_id))
        return {
            "results": search(
                db, user["id"], q, str(session_id) if session_id else None
            )
        }


class Question(BaseModel):
    question: str = Field(min_length=2, max_length=1200)
    session_id: UUID | None = None


@router.post("/api/v1/ask")
async def ask(request: Request, body: Question, user=Depends(principal)):
    store = request.app.state.store
    with store.connect(write=True) as db:
        if body.session_id:
            session_for(db, user["id"], str(body.session_id))
        hour = now() // 3600
        db.execute("DELETE FROM ask_limits WHERE hour<?", (hour,))
        count = db.execute(
            "INSERT INTO ask_limits VALUES(?,?,1) ON CONFLICT(owner,hour) DO UPDATE SET n=n+1 RETURNING n",
            (user["id"], hour),
        ).fetchone()[0]
        if count > 60:
            raise HTTPException(429, "Hourly question limit reached")
    return await answer(
        store,
        request.app.state.provider,
        user["id"],
        body.question,
        str(body.session_id) if body.session_id else None,
    )


@router.post("/api/v1/sessions/{sid}/retry")
def retry(request: Request, sid: UUID, user=Depends(principal)):
    with request.app.state.store.connect(write=True) as db:
        row = session_for(db, user["id"], str(sid))
        if row["state"] != "failed":
            raise HTTPException(409, "No failed work to retry")
        db.execute(
            "UPDATE jobs SET state='pending',due=0,attempts=0,error=NULL WHERE session_id=? AND state='failed'",
            (str(sid),),
        )
        db.execute(
            "UPDATE sessions SET state='processing',error=NULL WHERE id=?", (str(sid),)
        )
    return {"queued": True}


class Completion(BaseModel):
    completed: bool


@router.put("/api/v1/sessions/{sid}/tasks/{task_id}")
def toggle(
    request: Request, sid: UUID, task_id: str, body: Completion, user=Depends(principal)
):
    with request.app.state.store.connect(write=True) as db:
        row = session_for(db, user["id"], str(sid))
        if task_id not in {a["task_id"] for a in summary_for(db, row)["actions"]}:
            raise HTTPException(404, "Action item unavailable")
        if body.completed:
            db.execute(
                "INSERT OR IGNORE INTO task_checks VALUES(?,?)", (str(sid), task_id)
            )
        else:
            db.execute(
                "DELETE FROM task_checks WHERE session_id=? AND task_id=?",
                (str(sid), task_id),
            )
    return {"completed": body.completed}


@router.delete("/api/v1/sessions/{sid}")
def delete(request: Request, sid: UUID, user=Depends(principal)):
    sid = str(sid)
    store = request.app.state.store
    with store.connect(write=True) as db:
        session_for(db, user["id"], sid)
        for table in ("chunks", "moments"):
            db.execute(
                f"INSERT OR IGNORE INTO garbage SELECT asset FROM {table} WHERE session_id=? AND asset IS NOT NULL",
                (sid,),
            )
        db.execute(
            "INSERT INTO deleted_sessions VALUES(?,?,?)", (sid, user["id"], now())
        )
        db.execute("DELETE FROM sessions WHERE id=?", (sid,))
    store.collect()
    return {"deleted": True}


@router.get("/api/v1/sessions/{sid}/export")
def export(request: Request, sid: UUID, user=Depends(principal)):
    with request.app.state.store.connect() as db:
        m = detail(db, user["id"], str(sid))
    lines = ["# " + m["title"], "", m["started_at"], ""]
    for key, items in m["summary"].items():
        lines.extend(["## " + key.title(), ""])
        for item in items:
            refs = " ".join(
                f"[{clock(r['at_ms'])}]({request.app.state.settings.public_url}/sessions/{sid}#e-{r['id']})"
                for r in item["references"]
            )
            prefix = (
                "[x] " if item.get("completed") else "[ ] " if key == "actions" else ""
            )
            lines.append("- " + prefix + item["text"] + " " + refs)
        lines.append("")
    lines += ["## Timeline", ""]
    lines.extend(
        f"- {clock(e['start_ms'])} · {e['speaker']} · {e['text']}"
        for e in m["evidence"]
    )
    return Response(
        "\n".join(lines) + "\n",
        media_type="text/markdown",
        headers={"Content-Disposition": f'attachment; filename="sudoai-{sid}.md"'},
    )
