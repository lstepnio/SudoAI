#!/usr/bin/env python3
"""Exercise the actual backend and live AI on invented meeting evidence only."""
import argparse
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import re
import sys
import tempfile
import time
from uuid import uuid4

FACTS = [
    {"id":"project","text":"The approved project codename is Lumen Harbor.","question":"What is the approved project codename?","query":"project codename","patterns":[r"lumen\s+harbor"]},
    {"id":"release","text":"The release date is 2027-02-18.","question":"What is the release date?","query":"release date","patterns":[r"2027[-/]02[-/]18|feb(?:ruary)?\s+18(?:th)?(?:,)?\s+2027|18\s+feb(?:ruary)?\s+2027"]},
    {"id":"devices","text":"The pilot will use exactly 42 devices.","question":"How many devices will the pilot use?","query":"pilot devices","patterns":[r"\b42\b|forty[ -]two"]},
    {"id":"budget","text":"The approved budget is 73500 USD.","question":"What is the approved budget in USD?","query":"approved budget","patterns":[r"73[,]?500",r"usd|dollars|\$"]},
    {"id":"freeze","text":"The change freeze starts at 17:00 UTC on 2027-02-11.","question":"When does the change freeze start, including time and timezone?","query":"change freeze","patterns":[r"17[:.]00|5(?::00)?\s*p\.?m\.?",r"\butc\b",r"2027[-/]02[-/]11|feb(?:ruary)?\s+11(?:th)?(?:,)?\s+2027|11\s+feb(?:ruary)?\s+2027"]},
    {"id":"owner","text":"Mina Rao owns the upload retry work.","question":"Who owns the upload retry work?","query":"upload retry owns","patterns":[r"mina\s+rao"]},
    {"id":"deadline","text":"The upload retry work is due on 2027-02-05.","question":"What is the upload retry work deadline?","query":"upload retry due","patterns":[r"2027[-/]02[-/]0?5|feb(?:ruary)?\s+0?5(?:th)?(?:,)?\s+2027|0?5\s+feb(?:ruary)?\s+2027"]},
    {"id":"fallback","text":"The selected fallback region is Indigo.","question":"What is the selected fallback region?","query":"fallback region","patterns":[r"\bindigo\b"]},
    {"id":"risk","text":"The identified launch risk is the server certificate expiring on 2027-02-09.","question":"What launch risk was identified, including its date?","query":"launch risk certificate","patterns":[r"certificate",r"expir",r"2027[-/]02[-/]0?9|feb(?:ruary)?\s+0?9(?:th)?(?:,)?\s+2027|0?9\s+feb(?:ruary)?\s+2027"]},
    {"id":"screen","text":"The screen-only rack access code is QZ-418.","question":"What is the rack access code shown on the screen?","query":"rack access code","patterns":[r"qz[- ]418"],"screen":True},
]
UNKNOWN = ["What is the production database password?", "Who approved the catering vendor and what was its price?"]


def normalized(value):
    return " ".join(value.casefold().split())


def require_response(response):
    if not response.is_success:
        raise RuntimeError(f"API returned HTTP {response.status_code}: {response.text[:500]}")
    return response.json()


def check_refs(refs, evidence, sid):
    return bool(refs) and all(
        ref.get("id") in evidence
        and ref.get("session_id")==sid
        and ref.get("quote","").strip()
        and normalized(ref["quote"]) in normalized(evidence[ref["id"]]["text"])
        and ref.get("at_ms")==evidence[ref["id"]]["start_ms"]
        for ref in refs
    )


def qualify(args):
    # Use a file-backed Settings value only. Never load, copy, print or report the key.
    if os.getenv("SUDOAI_OPENAI_API_KEY"):
        raise RuntimeError("Unset the API-key environment override; this qualification requires the settings key file")
    backend=Path(args.backend).resolve()
    sys.path.insert(0,str(backend))
    from fastapi.testclient import TestClient
    from sudoai.ai import EMPTY
    from sudoai.config import Settings
    from sudoai.db import new_token
    from sudoai.main import create_app
    key_file=Path(args.key_file).expanduser()
    if not key_file.is_file():
        raise RuntimeError("Configured key file does not exist")
    start=time.perf_counter()
    report={"run_at":datetime.now(timezone.utc).isoformat(),"synthetic_only":True,"backend":str(backend),"checks":{},"facts":[],"unknown":[]}
    with tempfile.TemporaryDirectory(prefix="sudoai-qualification-") as scratch:
        root=Path(scratch)
        settings=Settings(data=root/"data",media=root/"media",key_file=key_file,run_worker=False)
        report["text_model"]=settings.text_model
        app=create_app(settings)
        with TestClient(app,base_url="https://sudoai.example.com") as client:
            with app.state.store.connect(write=True) as db:
                db.execute("INSERT INTO users VALUES(?,?,?)",("qualification-user","invented-qualification-subject","Synthetic QA"))
                token=new_token(db,"qualification-user","device")
            headers={"Authorization":"Bearer "+token}
            sid=str(uuid4()); base="/api/v1/sessions/"+sid
            require_response(client.put(base,headers=headers,json={"title":"Synthetic Lumen Harbor qualification","device":"android","started_at":"2026-11-25T12:00:00Z"}))
            screen=next(f for f in FACTS if f.get("screen")); moment=str(uuid4())
            body={"at_ms":55000,"text":screen["text"],"bookmarked":True}
            require_response(client.put(base+"/moments/"+moment,headers=headers,json=body))
            require_response(client.put(base+"/moments/"+moment,headers=headers,json=body))
            require_response(client.post(base+"/finalize",headers=headers,json={"duration_ms":60000,"tracks":{}}))
            cues=["WEBVTT",""]
            for i,fact in enumerate(f for f in FACTS if not f.get("screen")):
                at=i*5
                cues.extend([f"00:00:{at:02d}.000 --> 00:00:{at+4:02d}.000",f"<v Synthetic facilitator>{fact['text']}</v>",""])
            vtt="\n".join(cues).encode()
            def send_import():
                return require_response(client.post("/api/v1/import",headers=headers,data={"session_id":sid},files={"file":("synthetic-meeting.vtt",vtt,"text/vtt")}))
            imported=send_import(); duplicate=send_import()
            report["checks"]["import_idempotent"]=imported["id"]==sid and duplicate=={"id":sid,"duplicate":True}
            summary_start=time.perf_counter()
            # Run the real worker on the TestClient's event loop, including real OpenAI summary calls.
            for _ in range(20):
                if not client.portal.call(app.state.worker.step):
                    break
            report["summary_seconds"]=round(time.perf_counter()-summary_start,3)
            meeting=require_response(client.get(base,headers=headers))
            report["meeting_state"]=meeting["state"]
            if meeting["state"]!="ready":
                raise RuntimeError(f"Meeting processing did not become ready: {meeting.get('error')}")
            evidence={row["id"]:row for row in meeting["evidence"]}
            source_ids={fact["id"]:{eid for eid,row in evidence.items() if normalized(fact["text"])==normalized(row["text"])} for fact in FACTS}
            report["checks"]["ten_distinct_facts_imported"]=len(evidence)==10 and all(len(ids)==1 for ids in source_ids.values())
            report["checks"]["screen_only_source"]=all(evidence[eid]["kind"]=="screen" for eid in source_ids["screen"]) and not any(screen["text"] in f["text"] for f in FACTS if not f.get("screen"))
            report["summary"]=meeting["summary"]
            findings=[item for category in meeting["summary"].values() for item in category]
            report["checks"]["summary_citations_valid"]=bool(findings) and all(check_refs(item["references"],evidence,sid) for item in findings)
            def ask_fact(fact):
                before=time.perf_counter()
                found=require_response(client.get("/api/v1/search",headers=headers,params={"q":fact["query"],"session_id":sid}))["results"]
                result=require_response(client.post("/api/v1/ask",headers=headers,json={"question":fact["question"],"session_id":sid}))
                expected=source_ids[fact["id"]]
                factual=all(re.search(pattern,result["answer"],re.I) for pattern in fact["patterns"])
                cited={ref["id"] for ref in result["references"]}
                return {"fact":fact["id"],"question":fact["question"],"search_hit":bool(expected & {row["id"] for row in found}),"correct":factual,"citation_valid":check_refs(result["references"],evidence,sid),"expected_source_cited":bool(expected & cited),"seconds":round(time.perf_counter()-before,3),**result}
            with ThreadPoolExecutor(max_workers=args.parallel) as pool:
                report["facts"]=list(pool.map(ask_fact,FACTS))
            for question in UNKNOWN:
                before=time.perf_counter()
                # A scoped question falls back to the meeting's evidence, requiring model abstention.
                result=require_response(client.post("/api/v1/ask",headers=headers,json={"question":question,"session_id":sid}))
                report["unknown"].append({"question":question,"abstained":result=={"answer":EMPTY,"references":[]},"seconds":round(time.perf_counter()-before,3),**result})
            with app.state.store.connect() as db:
                usage=[dict(row) for row in db.execute("SELECT operation,model,audio_ms,input_tokens,output_tokens FROM usage WHERE session_id=?",(sid,))]
            report["usage"]=usage
            report["checks"]["real_ai_calls_recorded"]=sum(row["operation"]=="ask" for row in usage)==len(FACTS)+len(UNKNOWN) and any(row["operation"]=="summary" for row in usage)
            report["checks"]["no_stt_calls"]=not any(row["operation"]=="transcribe" for row in usage)
            report["evidence"]=list(evidence.values())
    report["seconds"]=round(time.perf_counter()-start,3)
    report["scores"]={"search_hits":sum(f["search_hit"] for f in report["facts"]),"correct_answers":sum(f["correct"] for f in report["facts"]),"correct_citations":sum(f["citation_valid"] and f["expected_source_cited"] for f in report["facts"]),"abstentions":sum(f["abstained"] for f in report["unknown"]),"known_total":len(FACTS),"unknown_total":len(UNKNOWN)}
    report["passed"]=all(report["checks"].values()) and all(f["search_hit"] and f["correct"] and f["citation_valid"] and f["expected_source_cited"] for f in report["facts"]) and all(f["abstained"] for f in report["unknown"])
    return report


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend",required=True,help="Checkout containing the actual sudoai Python package")
    parser.add_argument("--key-file",default="~/.config/sudoai/openai.key")
    parser.add_argument("--output",default=str(Path(__file__).with_name("results")/"pilot-live.json"))
    parser.add_argument("--parallel",type=int,choices=range(1,5),default=3)
    parser.add_argument("--live",action="store_true",help="Run real OpenAI summary and answer requests using invented text")
    args=parser.parse_args()
    if not args.live:
        parser.error("--live is required; this is an actual provider qualification")
    try:
        result=qualify(args)
    except Exception as exc:
        # Backend/provider exceptions are sanitized; avoid arbitrary exception object reprs or headers.
        print(f"Qualification failed: {type(exc).__name__}: {str(exc)[:500]}",file=sys.stderr)
        return 2
    output=Path(args.output); output.parent.mkdir(parents=True,exist_ok=True); output.write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps({k:result[k] for k in ("passed","scores","seconds","summary_seconds","text_model")},indent=2))
    print(f"Synthetic results: {output}")
    return 0 if result["passed"] else 1


if __name__=="__main__":
    sys.exit(main())
