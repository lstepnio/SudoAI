"""Create a dedicated local signing key on first use, then build a signed pilot APK."""

import argparse
import os
import secrets
import shutil
import subprocess
import tempfile
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument(
    "--android", type=Path, default=Path(__file__).resolve().parents[1] / "android"
)
parser.add_argument("--init-key-only", action="store_true")
args = parser.parse_args()
root = Path.home() / ".config" / "sudoai"
root.mkdir(mode=0o700, parents=True, exist_ok=True)
signing = root / "android-signing"
alias = "sudoai-pilot"
if not signing.exists():
    stage = Path(tempfile.mkdtemp(prefix=".signing-", dir=root))
    try:
        password = stage / "password"
        fd = os.open(password, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w") as output:
            output.write(secrets.token_urlsafe(42) + "\n")
        subprocess.run(
            [
                "keytool",
                "-genkeypair",
                "-keystore",
                str(stage / "pilot.p12"),
                "-storetype",
                "PKCS12",
                "-storepass:file",
                str(password),
                "-keyalg",
                "RSA",
                "-keysize",
                "3072",
                "-validity",
                "10000",
                "-alias",
                alias,
                "-dname",
                "CN=SudoAI Private Pilot",
            ],
            check=True,
        )
        (stage / "pilot.p12").chmod(0o600)
        stage.rename(signing)
    finally:
        if stage.exists():
            shutil.rmtree(stage)
if args.init_key_only:
    print("Dedicated Android pilot signing identity is ready.")
else:
    env = dict(os.environ)
    password = (signing / "password").read_text().strip()
    env.update(
        SUDOAI_KEYSTORE=str(signing / "pilot.p12"),
        SUDOAI_STORE_PASSWORD=password,
        SUDOAI_KEY_ALIAS=alias,
        SUDOAI_KEY_PASSWORD=password,
    )
    env.setdefault("ANDROID_HOME", str(Path.home() / "Library" / "Android" / "sdk"))
    subprocess.run(
        ["./gradlew", "--no-daemon", "assembleRelease"],
        cwd=args.android,
        env=env,
        check=True,
    )
    print("Signed pilot build is in android/app/build/outputs/apk/release/.")
