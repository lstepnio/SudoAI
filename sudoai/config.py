import os
from dataclasses import dataclass, field
from pathlib import Path


@dataclass
class Settings:
    data: Path = field(
        default_factory=lambda: Path(os.getenv("SUDOAI_DATA", "var/data"))
    )
    media: Path = field(
        default_factory=lambda: Path(os.getenv("SUDOAI_MEDIA", "var/media"))
    )
    public_url: str = field(
        default_factory=lambda: os.getenv(
            "SUDOAI_URL", "https://sudoai.example.com"
        ).rstrip("/")
    )
    issuer: str = field(
        default_factory=lambda: (
            os.getenv(
                "SUDOAI_ISSUER", "https://auth.example.com/application/o/sudoai/"
            ).rstrip("/")
            + "/"
        )
    )
    client_id: str = field(default_factory=lambda: os.getenv("SUDOAI_CLIENT_ID", ""))
    client_secret: str = field(
        default_factory=lambda: os.getenv("SUDOAI_CLIENT_SECRET", ""), repr=False
    )
    group: str = field(
        default_factory=lambda: os.getenv("SUDOAI_GROUP", "SudoAI Users")
    )
    speech_model: str = field(
        default_factory=lambda: os.getenv("SUDOAI_SPEECH_MODEL", "gpt-transcribe")
    )
    text_model: str = field(
        default_factory=lambda: os.getenv("SUDOAI_TEXT_MODEL", "gpt-5-mini")
    )
    key_file: Path = field(
        default_factory=lambda: Path(
            os.getenv("SUDOAI_OPENAI_KEY_FILE", "/run/secrets/sudoai-openai")
        )
    )
    run_worker: bool = True

    def openai_key(self):
        value = os.getenv("SUDOAI_OPENAI_API_KEY")
        if value:
            return value.strip()
        try:
            return self.key_file.read_text().strip()
        except FileNotFoundError:
            return ""
