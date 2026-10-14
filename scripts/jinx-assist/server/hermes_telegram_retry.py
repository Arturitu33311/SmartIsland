#!/usr/bin/env python3
"""Retry Telegram replies the Hermes gateway gave up on (``send_path_degraded``).

Native Hermes only replays a ``send_path_degraded`` row when the Telegram adapter reconnects, with
no timer and no backoff, so one bad window to api.telegram.org strands the reply in ``failed``.
This claims those rows (``failed`` -> ``attempting``, atomically), sends them straight to the Bot
API trying known-good Telegram IPs first, and marks them ``delivered``. It never sends a row it
could not claim, so it cannot double-send with the gateway.

Token is read in memory from ``hermes-secrets env --shell`` and never printed.
Run with the Hermes venv python from a systemd timer. ``--dry-run`` lists what it would send.
"""
import argparse
import http.client
import json
import os
import shlex
import socket
import sqlite3
import ssl
import subprocess
import sys
import time
from pathlib import Path

HERMES_HOME = Path(os.environ.get("HERMES_HOME", "~/.hermes")).expanduser()
API_HOST = "api.telegram.org"
# 149.154.167.99 answers from this network; the DNS address and .220 are blackholed here.
DEFAULT_IPS = ["149.154.167.99", "149.154.167.220", "149.154.166.110"]
CHUNK = 4000
MAX_AGE_S = 6 * 3600
MAX_TRIES = 12
TRANSIENT = ("send_path_degraded",)
CONNECT_TIMEOUT = 6


def split_text(text, limit=CHUNK):
    """Split on paragraph/line/space boundaries so each Telegram message stays under the limit."""
    text = text or ""
    parts = []
    while len(text) > limit:
        cut = max(text.rfind("\n\n", 0, limit), text.rfind("\n", 0, limit), text.rfind(" ", 0, limit))
        if cut < limit // 2:
            cut = limit
        parts.append(text[:cut].rstrip())
        text = text[cut:].lstrip()
    if text or not parts:
        parts.append(text)
    return parts


def ip_order(env_value=None):
    ips = [p.strip() for p in (env_value or "").split(",") if p.strip()] or list(DEFAULT_IPS)
    return list(dict.fromkeys(ips)) + [None]  # None = normal DNS resolution, last


class _IPConn(http.client.HTTPSConnection):
    """HTTPS to a fixed IP while keeping SNI/cert validation for api.telegram.org."""

    def __init__(self, ip, **kw):
        super().__init__(API_HOST, timeout=CONNECT_TIMEOUT, **kw)
        self._ip = ip

    def connect(self):
        target = self._ip or API_HOST
        sock = socket.create_connection((target, 443), timeout=CONNECT_TIMEOUT)
        self.sock = ssl.create_default_context().wrap_socket(sock, server_hostname=API_HOST)


def send_message(token, chat_id, text, thread_id=None, ips=None):
    """Send one message; returns (ok, error). Tries each IP until one answers with an API reply."""
    payload = {"chat_id": chat_id, "text": text, "disable_web_page_preview": True}
    if thread_id:
        payload["message_thread_id"] = int(thread_id)
    body = json.dumps(payload).encode()
    last = "no_route"
    for ip in ips or ip_order(os.environ.get("TELEGRAM_FALLBACK_IPS")):
        try:
            conn = _IPConn(ip)
            conn.request("POST", f"/bot{token}/sendMessage", body, {"Content-Type": "application/json"})
            resp = conn.getresponse()
            data = json.loads(resp.read() or b"{}")
            conn.close()
        except (OSError, ssl.SSLError, ValueError, http.client.HTTPException) as exc:
            last = f"{type(exc).__name__}"
            continue
        if data.get("ok"):
            return True, ""
        last = f"{data.get('error_code')}:{str(data.get('description'))[:80]}"
        if data.get("error_code") in (400, 403):  # bad chat / blocked: another IP will not help
            return False, last
    return False, last


def get_token():
    out = subprocess.run(
        [str(HERMES_HOME / "hermes-agent/venv/bin/hermes-secrets"), "env", "--shell"],
        capture_output=True, text=True, timeout=30, check=True).stdout
    for line in out.splitlines():
        if line.startswith("export TELEGRAM_BOT_TOKEN="):
            return shlex.split(line.split("=", 1)[1])[0]
    raise RuntimeError("TELEGRAM_BOT_TOKEN not provided by hermes-secrets")


def load_tries(path):
    try:
        return json.loads(Path(path).read_text())
    except (OSError, ValueError):
        return {}


def run(db_path, tries_path, dry_run=False, now=None):
    now = time.time() if now is None else now
    conn = sqlite3.connect(str(db_path), timeout=30)
    conn.execute("PRAGMA busy_timeout=30000")
    marks = ",".join("?" * len(TRANSIENT))
    rows = conn.execute(
        f"SELECT obligation_id, chat_id, thread_id, content, created_at, updated_at FROM delivery_obligations "
        f"WHERE platform='telegram' AND state='failed' AND last_error IN ({marks}) "
        f"AND created_at > ? ORDER BY created_at", (*TRANSIENT, now - MAX_AGE_S)).fetchall()
    # Give the gateway's own reconnect replay a moment before taking over.
    rows = [r for r in rows if now - r[5] >= 20]
    tries = load_tries(tries_path)
    if not rows:
        return 0
    if dry_run:
        for r in rows:
            print(f"would send {r[0]} to chat {r[1]} ({len(r[3])} chars, tries={tries.get(r[0], 0)})", flush=True)
        return len(rows)
    token = get_token()
    delivered = 0
    for oid, chat_id, thread_id, content, _created, _updated in rows:
        if tries.get(oid, 0) >= MAX_TRIES:
            conn.execute("UPDATE delivery_obligations SET state='abandoned', updated_at=? WHERE obligation_id=? "
                         "AND state='failed'", (now, oid))
            conn.commit()
            print(f"abandoned {oid}: {MAX_TRIES} tries", flush=True)
            continue
        claim = conn.execute("UPDATE delivery_obligations SET state='attempting', updated_at=? "
                             "WHERE obligation_id=? AND state='failed'", (time.time(), oid))
        conn.commit()
        if claim.rowcount != 1:
            continue  # the gateway took it
        ok, err = True, ""
        for part in split_text(content):
            ok, err = send_message(token, chat_id, part, thread_id)
            if not ok:
                break
        if ok:
            conn.execute("UPDATE delivery_obligations SET state='delivered', updated_at=?, last_error=NULL "
                         "WHERE obligation_id=?", (time.time(), oid))
            tries.pop(oid, None)
            delivered += 1
            print(f"delivered {oid} to chat {chat_id}", flush=True)
        else:
            conn.execute("UPDATE delivery_obligations SET state='failed', updated_at=?, last_error=? "
                         "WHERE obligation_id=?", (time.time(), TRANSIENT[0], oid))
            tries[oid] = tries.get(oid, 0) + 1
            print(f"retry-failed {oid}: {err} (try {tries[oid]}/{MAX_TRIES})", flush=True)
        conn.commit()
    Path(tries_path).parent.mkdir(parents=True, exist_ok=True)
    Path(tries_path).write_text(json.dumps(tries))
    return delivered


def main(argv=None):
    ap = argparse.ArgumentParser()
    ap.add_argument("--dry-run", action="store_true")
    ap.add_argument("--db", default=str(HERMES_HOME / "state.db"))
    ap.add_argument("--tries", default=str(HERMES_HOME / "state" / "telegram_retry_tries.json"))
    args = ap.parse_args(argv)
    run(args.db, args.tries, args.dry_run)
    return 0


if __name__ == "__main__":
    sys.exit(main())
