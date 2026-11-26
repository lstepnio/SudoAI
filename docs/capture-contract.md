# SudoAI capture protocol v1

The HTTPS service URL is configurable and defaults to `https://sudoai.example.com`. Requests below use a native bearer token or a signed-in browser session. The server derives the owner from authentication. JSON uses snake case. IDs for sessions, moments, and gaps are client-generated UUIDs. All capture offsets are integer milliseconds on the session's monotonic timeline. A pause leaves elapsed time in the timeline.

## Sign in

Generate a random PKCE verifier (43 to 128 URL-safe characters), its base64url SHA-256 challenge without padding, and random state. Open the system browser at `/auth/native/start?platform=macos&challenge=...&state=...` (Android uses `android`). The browser returns to `sudoai://auth/callback?code=...&state=...`. Validate state then `POST /api/v1/auth/exchange` with `{"code":"...","verifier":"..."}`. Store the returned `access_token` in OS-protected storage and send `Authorization: Bearer ...`. A 401 keeps the offline queue intact and asks for sign-in. `GET /api/v1/me` returns `id` and `name`; `POST /api/v1/auth/revoke` revokes the current native token. Browser library is `/`.

## Durable capture

1. `PUT /api/v1/sessions/{id}` with `{"title":"Meeting","started_at":"2026-11-25T12:00:00Z","device":"macos"}`. Devices are `macos`, `android`, or `import`.
2. `PUT /api/v1/sessions/{id}/tracks/{track}/chunks/{seq}?start_ms=0&end_ms=20000`. Send independently playable binary audio, its MIME type, and lowercase hex `X-Content-SHA256`. Supported track names are `mic`, `system`, `omi`, `playback`, `import`. Sequence starts at zero per track. Use bounded chunks under 8 MiB, preferably 15 to 30 seconds. PCM16 WAV, AAC/M4A, Ogg Opus, FLAC, MP3, and WebM are accepted. Time reflects the first and last sample, including any preceding gaps in session time.
3. `PUT /api/v1/sessions/{id}/moments/{uuid}` with `{"at_ms":4000,"text":"Launch date","bookmarked":false,"image_base64":"..."}`. Images are JPEG, at most 2 MiB decoded. Text-only moments omit the image. `PUT /api/v1/sessions/{id}/gaps/{uuid}` with `{"track":"omi","start_ms":5000,"end_ms":6500,"reason":"Disconnected"}`; `screen` is also a valid gap track.
4. Once all moments and gaps are acknowledged, `POST /api/v1/sessions/{id}/finalize` with `{"duration_ms":23000,"tracks":{"mic":2,"system":2}}`. Numbers are exact chunk counts. HTTP 409 indicates incomplete or conflicting input. Repeating acknowledged identical writes and finalization is safe. A differing payload for an existing identity is a conflict. Treat 2xx as durable acknowledgement. Preserve local bytes on transport errors and sign-in expiry. Retry with backoff. Never transfer a pending session to a different signed-in user.

`POST /api/v1/import` takes multipart `file`, optional `session_id`, and optional `title`. Browser and Android Share use the same importer. Open a meeting at `/sessions/{id}`. The app can show local capture history and pending uploads without server connectivity.
