#!/usr/bin/env python3
"""Time-based session reset for the Hermes gateway (idle and daily boundary).

Hermes v0.21 made ``session_reset`` in config.yaml inert ("time never replaces a routed
conversation"), so a Telegram DM session lived for days and every message paid a huge context
compaction. This restores the old policy out-of-process: it ends the active session row in
state.db with end_reason ``session_reset``. The running gateway already checks on every routed
message whether the session is ended in the DB (``_is_session_ended_in_db``) and opens a fresh one,
so no gateway restart or code patch is needed.

Policy comes from config.yaml ``session_reset`` (mode both|idle|daily|none, idle_minutes, at_hour).
Activity clock = last real user message (automatic ``[[DISPARADOR`` triggers do not count).

Run with the Hermes venv python. ``--dry-run`` only reports. ``--db`` points at a copy for tests.
"""
import argparse
import os
import sqlite3
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

HERMES_HOME = Path(os.environ.get("HERMES_HOME", "~/.hermes")).expanduser()
AGENT_DIR = HERMES_HOME / "hermes-agent"
PLATFORMS = ("telegram",)
TRIGGER_PREFIX = "[[DISPARADOR"
# A turn in flight keeps writing rows; never cut a session that moved this recently.
BUSY_WINDOW_S = 180


def should_reset(last_activity, now, mode="both", idle_minutes=60, at_hour=4):
    """Return 'idle', 'daily' or None. Timestamps are epoch seconds, local time for the boundary."""
    if mode in (None, "none") or last_activity is None:
        return None
    if mode in ("idle", "both") and now - last_activity >= idle_minutes * 60:
        return "idle"
    if mode in ("daily", "both"):
        cur = datetime.fromtimestamp(now)
        boundary = cur.replace(hour=at_hour, minute=0, second=0, microsecond=0)
        if boundary > cur:
            boundary -= timedelta(days=1)
        if last_activity < boundary.timestamp():
            return "daily"
    return None


def load_policy(config_path):
    defaults = {"mode": "both", "idle_minutes": 60, "at_hour": 4}
    try:
        import yaml
        cfg = (yaml.safe_load(Path(config_path).read_text()) or {}).get("session_reset") or {}
    except Exception:
        return defaults
    return {k: cfg.get(k, v) for k, v in defaults.items()}


def candidates(conn, now):
    """Active sessions of the watched platforms that look idle-able, with their activity clock."""
    out = []
    marks = ",".join("?" * len(PLATFORMS))
    rows = conn.execute(
        f"SELECT id, session_key, started_at, parent_session_id, message_count FROM sessions "
        f"WHERE ended_at IS NULL AND source IN ({marks}) AND session_key IS NOT NULL", PLATFORMS).fetchall()
    for sid, key, started, parent, count in rows:
        last_user = conn.execute(
            "SELECT max(timestamp) FROM messages WHERE session_id=? AND role='user' "
            "AND content NOT LIKE ?", (sid, TRIGGER_PREFIX + "%")).fetchone()[0]
        last_any = conn.execute(
            "SELECT max(timestamp) FROM messages WHERE session_id=?", (sid,)).fetchone()[0]
        # A compression child holds only the tail: its start is when a user turn last ran.
        has_user = last_user is not None or parent is not None
        activity = max(x for x in (last_user, started if parent is not None else None, 0) if x is not None)
        out.append({
            "id": sid, "key": key, "messages": count or 0, "has_user": has_user,
            "activity": activity or started, "busy": bool(last_any and now - last_any < BUSY_WINDOW_S),
        })
    return out


def is_locked(conn, sid):
    try:
        now = time.time()
        row = conn.execute(
            "SELECT 1 FROM compression_locks WHERE session_id=? AND expires_at>?", (sid, now)).fetchone()
        return row is not None
    except sqlite3.Error:
        return False


def run(db_path, dry_run, now=None):
    now = time.time() if now is None else now
    policy = load_policy(HERMES_HOME / "config.yaml")
    conn = sqlite3.connect(f"file:{db_path}?mode=ro", uri=True, timeout=10)
    todo = []
    for c in candidates(conn, now):
        if not c["has_user"] or c["messages"] == 0:
            continue
        reason = should_reset(c["activity"], now, **policy)
        if not reason:
            continue
        if c["busy"] or is_locked(conn, c["id"]):
            print(f"skip {c['id']}: turn or compression in flight", flush=True)
            continue
        todo.append((c, reason))
    conn.close()
    for c, reason in todo:
        idle_min = int((now - c["activity"]) / 60)
        print(f"{'would reset' if dry_run else 'reset'} {c['id']} key={c['key']} "
              f"msgs={c['messages']} idle={idle_min}m reason={reason}", flush=True)
        if dry_run:
            continue
        sys.path.insert(0, str(AGENT_DIR))
        from hermes_state import SessionDB
        SessionDB(db_path=Path(db_path)).end_session(c["id"], "session_reset")
    return len(todo)


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--db", default=str(HERMES_HOME / "state.db"))
    args = ap.parse_args(argv)
    run(args.db, args.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
