# Android client/backend contract

With an explicitly selected disposable emulator running, execute:

```sh
.venv/bin/python qa/android_contract.py --backend . --android android --device emulator-5580
```

The launcher uses temporary SQLite/media, localhost TLS and synthetic credentials. It rejects physical device IDs and does not make model requests. The Kotlin test exercises authentication, WAV/moment/gap upload, finalization, search, acknowledgement cleanup and duplicate transcript import.

The temporary certificate exception exists only in instrumentation code, not the signed application. Generated results are ignored. These checks do not establish physical microphone fidelity, permission behavior, call downlink capture or safe Stop/restart behavior.
