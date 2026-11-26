#!/usr/bin/env python3
"""Run the actual Android Kotlin upload client against a temporary local HTTPS FastAPI server."""

import argparse
import base64
import hashlib
import json
import os
import shutil
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import urllib.request
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backend", required=True)
    parser.add_argument("--android", required=True)
    parser.add_argument("--device", default="emulator-5580")
    parser.add_argument("--port", type=int, default=18586)
    parser.add_argument(
        "--output",
        default=str(Path(__file__).with_name("results") / "android-contract.json"),
    )
    args = parser.parse_args()
    if not args.device.startswith("emulator-"):
        parser.error(
            "Only disposable emulator devices are supported; physical installs require separate coordination"
        )
    backend = Path(args.backend).resolve()
    android = Path(args.android).resolve()
    sys.path.insert(0, str(backend))
    import uvicorn
    from sudoai.config import Settings
    from sudoai.db import new_token
    from sudoai.main import create_app

    start = time.perf_counter()
    report = {
        "synthetic_only": True,
        "actual_kotlin_http_client": True,
        "temporary_https_server": True,
        "physical_device": False,
        "backend": str(backend),
    }
    with tempfile.TemporaryDirectory(prefix="sudoai-android-contract-") as scratch:
        temp = Path(scratch)
        key = temp / "server.key"
        cert = temp / "server.pem"
        subprocess.run(
            [
                "openssl",
                "req",
                "-x509",
                "-newkey",
                "rsa:2048",
                "-nodes",
                "-keyout",
                str(key),
                "-out",
                str(cert),
                "-days",
                "1",
                "-subj",
                "/CN=localhost",
                "-addext",
                "subjectAltName=DNS:localhost,IP:127.0.0.1",
            ],
            check=True,
            capture_output=True,
        )
        cfg = Settings(
            data=temp / "data",
            media=temp / "media",
            key_file=temp / "no-provider-key",
            run_worker=False,
        )
        app = create_app(cfg)
        server = uvicorn.Server(
            uvicorn.Config(
                app,
                host="127.0.0.1",
                port=args.port,
                ssl_keyfile=str(key),
                ssl_certfile=str(cert),
                log_level="warning",
                access_log=False,
            )
        )
        thread = threading.Thread(target=server.run, daemon=True)
        thread.start()
        endpoint = f"https://localhost:{args.port}"
        server_ca = base64.b64encode(
            ssl.PEM_cert_to_DER_cert(cert.read_text())
        ).decode()
        trust = ssl.create_default_context(cafile=str(cert))
        try:
            for _ in range(100):
                try:
                    with urllib.request.urlopen(
                        endpoint + "/healthz", context=trust, timeout=1
                    ) as response:
                        if response.status == 200:
                            break
                except OSError:
                    time.sleep(0.1)
            else:
                raise RuntimeError("Temporary HTTPS backend did not start")
            with app.state.store.connect(write=True) as db:
                db.execute(
                    "INSERT INTO users VALUES(?,?,?)",
                    (
                        "synthetic-android-contract",
                        "invented-local-subject",
                        "Invented Android QA",
                    ),
                )
                token = new_token(db, "synthetic-android-contract", "device")
            env = dict(os.environ)
            env.setdefault("ANDROID_HOME", str(Path.home() / "Library/Android/sdk"))
            build = subprocess.run(
                [
                    str(android / "gradlew"),
                    "assembleDebug",
                    "assembleDebugAndroidTest",
                    "--console=plain",
                ],
                check=False,
                cwd=android,
                env=env,
                capture_output=True,
                text=True,
            )
            if build.returncode:
                raise RuntimeError(
                    "Android test APK build failed: "
                    + build.stdout[-1200:]
                    + build.stderr[-500:]
                )
            adb = shutil.which("adb") or str(
                Path(env["ANDROID_HOME"]) / "platform-tools/adb"
            )

            def run_adb(*parts):
                return subprocess.run(
                    [adb, "-s", args.device, *parts],
                    check=True,
                    capture_output=True,
                    text=True,
                )

            run_adb("wait-for-device")
            for _ in range(120):
                if (
                    run_adb("shell", "getprop", "sys.boot_completed").stdout.strip()
                    == "1"
                ):
                    break
                time.sleep(0.5)
            else:
                raise RuntimeError("Emulator boot did not complete")
            run_adb("reverse", f"tcp:{args.port}", f"tcp:{args.port}")
            try:
                run_adb(
                    "install",
                    "-r",
                    str(android / "app/build/outputs/apk/debug/app-debug.apk"),
                )
                run_adb(
                    "install",
                    "-r",
                    str(
                        android
                        / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
                    ),
                )
                result = run_adb(
                    "shell",
                    "am",
                    "instrument",
                    "-w",
                    "-r",
                    "-e",
                    "class",
                    "com.majjix.sudoai.LocalServerContractTest",
                    "-e",
                    "server_url",
                    endpoint,
                    "-e",
                    "server_ca",
                    server_ca,
                    "-e",
                    "synthetic_token",
                    token,
                    "com.majjix.sudoai.test/androidx.test.runner.AndroidJUnitRunner",
                )
            finally:
                run_adb("reverse", "--remove", f"tcp:{args.port}")
            report["instrumentation"] = result.stdout
            report["passed"] = (
                "OK (1 test)" in result.stdout and "FAILURES" not in result.stdout
            )
            with app.state.store.connect() as db:
                report["server_rows"] = {
                    table: db.execute(f"SELECT count(*) FROM {table}").fetchone()[0]
                    for table in (
                        "sessions",
                        "chunks",
                        "moments",
                        "gaps",
                        "imports",
                        "usage",
                    )
                }
            report["passed"] = report["passed"] and report["server_rows"] == {
                "sessions": 2,
                "chunks": 1,
                "moments": 1,
                "gaps": 1,
                "imports": 1,
                "usage": 0,
            }
            report["debug_apk_sha256"] = hashlib.sha256(
                (android / "app/build/outputs/apk/debug/app-debug.apk").read_bytes()
            ).hexdigest()
        finally:
            server.should_exit = True
            thread.join(timeout=10)
    report["seconds"] = round(time.perf_counter() - start, 3)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(
        json.dumps(
            {k: report[k] for k in ("passed", "server_rows", "seconds")}, indent=2
        )
    )
    print(f"Synthetic integration result: {output}")
    return 0 if report["passed"] else 1


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:  # noqa: BLE001 - sanitize subprocess argument-bearing failures
        # Do not print subprocess command arguments: they include an ephemeral synthetic token.
        print(
            f"Android contract qualification failed: {type(exc).__name__}: {str(exc)[:500] if not isinstance(exc, subprocess.CalledProcessError) else 'subprocess returned nonzero'}",
            file=sys.stderr,
        )
        sys.exit(2)
