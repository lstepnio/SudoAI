# Qualification fixtures

The scripts in this directory use invented facts and temporary libraries. They do not qualify real meeting accuracy or physical device behavior.

Run offline server regressions from the repository root:

```sh
.venv/bin/python -m unittest discover -s tests -v
```

The opt-in retrieval fixture makes real model requests:

```sh
.venv/bin/python qa/qualify_pilot.py --backend . --live
```

It reads the project key from `~/.config/sudoai/openai.key`, never prints it, and rejects an environment-key override. Review the script before enabling `--live`. Generated reports remain under ignored `qa/results/` and must not contain recordings or credentials.

For the real Kotlin client against an isolated temporary HTTPS backend, see [Android contract checks](ANDROID.md). The slide app and VTT file contain only invented test content. Physical microphone, headset, call, Omi and lifecycle qualification require separate consented tests. Android Stop/restart and offline audio recovery remain unqualified.
