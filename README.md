# SudoAI

Experimental audio-first meeting capture for macOS and Android, with a self-hosted private library.

**Pilot status:** Android microphone Stop/restart behavior and interrupted/offline audio recovery still require validation. An unexpected capture was observed during testing. Use controlled, consented tests; this is not qualified for unattended recording. Screen capture is optional and secondary.

SudoAI records user-started meetings and Omi voice notes on macOS and Android. Native clients queue captures offline and upload them to a private, per-user library on a self-hosted server. OpenAI transcribes the audio and prepares cited summaries and answers. Transcripts and local OCR are searchable even when an original recording has expired.

## Use the apps

- In the Mac menu bar, sign in, choose your local mic or Omi, enable Teams output if needed, and start a meeting. Screen moments require a separate selection. Each input has its own status. SudoAI's local mic control is independent of Teams mute.
- On Android, sign in once, choose a phone or Omi source, and start from the visible app. When notifications are allowed, the ongoing notification provides recording controls. If notifications are denied, stop capture from the app. Screen sharing uses Android's per-session picker. Playback capture is limited to sources Android allows.
- For a mobile call, import an available Teams VTT transcript or an exported audio file. A pendant recording made on speakerphone captures acoustic room sound. A headset keeps the remote participant out of that room recording.
- Use the web library for decisions, action items, timestamps, screen moments, questions, Markdown exports, and deletion. Capture workplace material only when the participants and the applicable workplace policy permit recording and processing on this service. Local recording controls are visible on the recording device.

## Build and test

The [pilot plan](docs/pilot-plan.md) records project research, five parallel workstreams, their dependencies and the physical acceptance tests.

| Component | Entry point |
| --- | --- |
| Mac menu bar app | [macOS build and permissions](macos/README.md) |
| Pixel app | [Android build and permissions](android/README.md) |
| Capture API | [Versioned capture and PKCE contract](docs/capture-contract.md) |
| Server | Python 3.13, FFmpeg, SQLite with FTS5, and the pinned dependencies in requirements.txt |
| Linux image | Build the root Dockerfile. Run one application process per database. The HTTP service listens on port 8000. |

From the repository root, create a Python 3.13 environment and run the server tests with FFmpeg available on PATH.

~~~sh
python3.13 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/python -m unittest discover -s tests -v
.venv/bin/uvicorn sudoai.main:app --host 127.0.0.1 --port 8000 --no-access-log
~~~

The health endpoint is /healthz. The anonymous home page is public. Library data requires authentication even on LAN. A complete development login needs an HTTPS public URL and an OIDC client. The test suite uses synthetic identities and a temporary database.

See [self-hosting](docs/self-hosting.md) for a single-container deployment example. Replace the example service origins before signing in. No recordings, signing keys or deployment credentials are included.

## Service configuration

| Variable | Purpose |
| --- | --- |
| SUDOAI_URL | HTTPS library origin, default https://sudoai.example.com. Register the exact /auth/callback path on this origin. |
| SUDOAI_ISSUER, SUDOAI_CLIENT_ID, SUDOAI_CLIENT_SECRET | Authentik confidential authorization-code client. Use the signed ID token, an exact issuer, and the default profile mapping with a groups claim. |
| SUDOAI_GROUP | Required pilot group, default SudoAI Users. Native clients use system-browser PKCE and store the resulting device credential in the operating system's credential store. |
| SUDOAI_OPENAI_KEY_FILE | Protected project key, default /run/secrets/sudoai-openai. Audio and selected text go to OpenAI. Screen images stay on the clients and your server. Responses requests use store=false. |
| SUDOAI_SPEECH_MODEL, SUDOAI_TEXT_MODEL | Defaults are gpt-transcribe and gpt-5-mini. The library records submitted audio duration, model IDs, and text token counts. |
| SUDOAI_DATA, SUDOAI_MEDIA | Separate private directories for the durable library and expiring raw media. Use persistent local filesystems. |

The worker checks every hour for originals whose processing completed seven days ago. Text, saved summaries, and bookmarked images persist until the user deletes the meeting. Clients keep pending bytes until durable acknowledgment. Expired source links continue to open retained text. Backups have their own retention period. Operational backups and signing credentials need protected storage.

An operator can bring the library online before connecting OpenAI. Audio and summary jobs stay queued, and imported transcripts and screen text remain searchable. Installing the project key enables the worker on its next poll.

A platform-source timestamp marks a chunk range. A Teams VTT cue carries its original speaker label and timestamp. Several people can share one headset, room, or Teams output track, so capture tracks do not establish a speaker's identity.

## Operator commands

The dedicated Android release key is generated once with deploy/sign-android.py and kept outside Git in the user's private configuration. Back up the signing directory securely to retain the ability to ship compatible updates. The macOS build supports Developer ID signing and notarization. An ad hoc build is suitable for local development.

~~~sh
python -m sudoai.manage backup /path/to/new-snapshot
python -m sudoai.manage verify-backup /path/to/new-snapshot
python -m sudoai.manage restore /path/to/new-snapshot /path/to/new-data
python -m sudoai.manage revoke-user OWNER_ID
~~~

A durable snapshot contains the SQLite library and bookmarked images. Restoring into a fresh directory verifies integrity and clears authentication tokens. Remove a departing pilot member's Authentik group membership and revoke their existing SudoAI tokens. Run these commands inside the existing project container or an equivalent local Python environment.
