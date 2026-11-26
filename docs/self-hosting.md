# Self-hosting

Use one application container per SQLite library, persistent data/media directories and an HTTPS reverse proxy. Configure the identity provider before using native sign-in. This is an experimental pilot, not a qualified production recorder.

Copy `.env.example` to an ignored `.env` and replace the example origins and OIDC values. Register the exact `/auth/callback` URL, require the configured group, and include its membership in the signed identity token. Keep the confidential client secret on the server; native apps use browser PKCE instead.

Build the root Dockerfile and run the image with the environment file, persistent `/data` and `/media` mounts, and a read-only project-key file at `/run/secrets/sudoai-openai`. The image runs as UID/GID 1000, so prepare writable mount directories for that identity. Publish port 8000 only to your reverse proxy. Store configuration and backup archives outside Git.

The service may start without a model key; processing waits until it is available. Audio and selected text are sent to the configured OpenAI service. Original screen images remain in your library. Review consent, retention and provider handling before capturing real material.

Set the HTTPS origin in each native app before signing in. Generic source defaults deliberately use reserved example domains. Use the existing backup/verification/restore commands in the root README, retain compatible image and database checkpoints, and verify restoration before broader use. Do not clear or uninstall a native app while recordings are pending.
