"""Operator commands for the single-container pilot."""

import argparse
import json
import os
import shutil
import sqlite3
import tempfile
from contextlib import closing
from pathlib import Path

from .config import Settings
from .db import Store, digest


def verify(directory):
    with closing(
        sqlite3.connect(
            (directory / "library.sqlite3").resolve().as_uri() + "?mode=ro", uri=True
        )
    ) as db:
        if (
            db.execute("PRAGMA integrity_check").fetchone()[0] != "ok"
            or db.execute("PRAGMA foreign_key_check").fetchone()
        ):
            raise ValueError("Backup database failed integrity checks")
        assets = {
            row[0]
            for row in db.execute(
                "SELECT asset FROM moments WHERE bookmarked=1 AND asset IS NOT NULL"
            )
        }
    manifest = json.loads((directory / "manifest.json").read_text())
    if set(manifest) != {
        "library.sqlite3",
        *("bookmarks/" + Path(asset).name for asset in assets),
    }:
        raise ValueError("Backup contents differ from its manifest")
    for name, checksum in manifest.items():
        if digest((directory / name).read_bytes()) != checksum:
            raise ValueError("Backup checksum mismatch")
    return len(assets)


def backup(store, destination):
    if destination.exists():
        raise ValueError("Choose a new backup directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".sudoai-backup-", dir=destination.parent))
    try:
        (stage / "bookmarks").mkdir(mode=0o700)
        # The write lock keeps the set of bookmarked files stable while copying the SQLite snapshot.
        with store.connect(write=True) as locked:
            with (
                closing(sqlite3.connect(store.path)) as reader,
                closing(sqlite3.connect(stage / "library.sqlite3")) as target,
            ):
                reader.backup(target)
                for table in ("tokens", "auth_flows", "exchanges"):
                    target.execute(f"DELETE FROM {table}")
                target.execute("UPDATE chunks SET asset=NULL")
                target.execute("UPDATE moments SET asset=NULL WHERE bookmarked=0")
                target.execute("DELETE FROM garbage")
                target.commit()
                target.execute("PRAGMA journal_mode=DELETE")
            for row in locked.execute(
                "SELECT asset FROM moments WHERE bookmarked=1 AND asset IS NOT NULL"
            ):
                source = store.asset_path(row[0])
                shutil.copyfile(source, stage / "bookmarks" / source.name)
        manifest = {
            str(p.relative_to(stage)): digest(p.read_bytes())
            for p in stage.rglob("*")
            if p.is_file()
        }
        (stage / "manifest.json").write_text(
            json.dumps(manifest, sort_keys=True) + "\n"
        )
        verify(stage)
        os.rename(stage, destination)
    except BaseException:
        shutil.rmtree(stage, ignore_errors=True)
        raise


def restore(source, target):
    verify(source)
    if target.exists():
        raise ValueError("Restore requires a new data directory")
    target.parent.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".sudoai-restore-", dir=target.parent))
    try:
        shutil.copytree(source / "bookmarks", stage / "bookmarks")
        shutil.copyfile(source / "library.sqlite3", stage / "library.sqlite3")
        with closing(sqlite3.connect(stage / "library.sqlite3")) as db:
            # Offline captures awaiting raw audio require the originals before processing can resume.
            db.execute(
                "UPDATE sessions SET state='failed',error='Original audio was outside the durable backup' WHERE state IN ('processing','capturing','failed') AND EXISTS (SELECT 1 FROM chunks c WHERE c.session_id=sessions.id AND NOT EXISTS (SELECT 1 FROM evidence e WHERE e.session_id=c.session_id AND e.track=c.track AND e.chunk_seq=c.seq))"
            )
            db.execute(
                "UPDATE jobs SET state='failed',lease=NULL WHERE session_id IN (SELECT id FROM sessions WHERE error='Original audio was outside the durable backup') AND state!='done'"
            )
            db.commit()
        os.rename(stage, target)
    except BaseException:
        shutil.rmtree(stage, ignore_errors=True)
        raise


def main():
    p = argparse.ArgumentParser(description=__doc__)
    subs = p.add_subparsers(dest="operation", required=True)
    for name in ("backup", "verify-backup"):
        subs.add_parser(name).add_argument("directory", type=Path)
    cmd = subs.add_parser("restore")
    cmd.add_argument("source", type=Path)
    cmd.add_argument("new_data", type=Path)
    cmd = subs.add_parser("revoke-user")
    cmd.add_argument("owner_id")
    args = p.parse_args()
    if args.operation == "backup":
        backup(Store(Settings()), args.directory)
        print("Verified durable library backup created.")
    elif args.operation == "verify-backup":
        print(f"Verified database and {verify(args.directory)} bookmarked images.")
    elif args.operation == "restore":
        restore(args.source, args.new_data)
        print("Restored the durable library into a new directory.")
    else:
        with Store(Settings()).connect(write=True) as db:
            db.execute("DELETE FROM tokens WHERE owner=?", (args.owner_id,))
            db.execute("DELETE FROM exchanges WHERE owner=?", (args.owner_id,))
        print("Revoked existing sessions and pending device exchange codes.")


if __name__ == "__main__":
    main()
