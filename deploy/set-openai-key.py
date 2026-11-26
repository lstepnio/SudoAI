"""Prompt locally and store the deployment key outside the source tree."""

import getpass
import os
import re
import tempfile
from pathlib import Path

directory = Path.home() / ".config" / "sudoai"
directory.mkdir(parents=True, mode=0o700, exist_ok=True)
key = getpass.getpass("SudoAI project OpenAI key (hidden): ").strip()
if not re.fullmatch(r"sk-[A-Za-z0-9_-]{20,}", key) or len(key) >= 8192:
    raise SystemExit(
        "Enter the OpenAI secret beginning with sk-. The existing file was preserved."
    )
with tempfile.NamedTemporaryFile(mode="w", dir=directory, delete=False) as output:
    output.write(key + "\n")
    output.flush()
    os.fsync(output.fileno())
os.replace(output.name, directory / "openai.key")
print("Saved the project key in ~/.config/sudoai/openai.key with mode 0600.")
