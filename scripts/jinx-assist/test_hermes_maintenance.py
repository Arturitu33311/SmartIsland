import importlib.util
import sqlite3
import tempfile
import unittest
from datetime import datetime
from pathlib import Path
from unittest import mock

SERVER = Path(__file__).parent / "server"


def load(name):
    spec = importlib.util.spec_from_file_location(name, SERVER / f"{name}.py")
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


reset = load("hermes_session_time_reset")
retry = load("hermes_telegram_retry")


def ts(y, m, d, h=0, mi=0):
    return datetime(y, m, d, h, mi).timestamp()


class ShouldReset(unittest.TestCase):
    def test_idle_after_threshold(self):
        now = ts(2026, 10, 14, 15, 0)
        self.assertEqual(reset.should_reset(now - 61 * 60, now), "idle")

    def test_recent_activity_same_day_is_kept(self):
        now = ts(2026, 10, 14, 15, 0)
        self.assertIsNone(reset.should_reset(now - 10 * 60, now))

    def test_daily_boundary_crossed_even_if_not_idle_long(self):
        # last msg 03:50, now 04:05 -> only 15 min idle but the 04:00 boundary was crossed
        now = ts(2026, 10, 14, 4, 5)
        self.assertEqual(reset.should_reset(ts(2026, 10, 14, 3, 50), now, idle_minutes=600), "daily")

    def test_before_boundary_today_uses_yesterdays(self):
        now = ts(2026, 10, 14, 2, 0)
        self.assertIsNone(reset.should_reset(ts(2026, 10, 13, 22, 0), now, idle_minutes=600))

    def test_mode_none_and_idle_only(self):
        now = ts(2026, 10, 14, 15, 0)
        self.assertIsNone(reset.should_reset(now - 7200, now, mode="none"))
        self.assertIsNone(reset.should_reset(ts(2026, 10, 13, 9), now, mode="idle", idle_minutes=100000))


def make_state(path):
    c = sqlite3.connect(path)
    c.executescript("""
    CREATE TABLE sessions(id TEXT, source TEXT, session_key TEXT, started_at REAL, ended_at REAL,
        parent_session_id TEXT, message_count INT);
    CREATE TABLE messages(session_id TEXT, role TEXT, content TEXT, timestamp REAL);
    CREATE TABLE compression_locks(session_id TEXT, holder TEXT, acquired_at REAL, expires_at REAL);
    CREATE TABLE delivery_obligations(obligation_id TEXT, session_key TEXT, platform TEXT, chat_id TEXT,
        thread_id TEXT, content TEXT, state TEXT, attempts INT, created_at REAL, updated_at REAL,
        last_error TEXT);
    """)
    return c


class ResetCandidates(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = str(Path(self.tmp.name) / "state.db")
        self.c = make_state(self.db)
        self.now = ts(2026, 10, 14, 15, 0)

    def tearDown(self):
        self.tmp.cleanup()

    def add(self, sid, user_ts, parent=None, trigger_ts=None, started=None):
        self.c.execute("INSERT INTO sessions VALUES(?,?,?,?,?,?,?)",
                       (sid, "telegram", "agent:main:telegram:dm:1", started or user_ts or 0, None, parent, 3))
        if user_ts:
            self.c.execute("INSERT INTO messages VALUES(?,?,?,?)", (sid, "user", "hola", user_ts))
        if trigger_ts:
            self.c.execute("INSERT INTO messages VALUES(?,?,?,?)", (sid, "user", "[[DISPARADOR AUTO", trigger_ts))
        self.c.commit()

    def run_reset(self):
        with mock.patch.object(reset, "load_policy", return_value={"mode": "both", "idle_minutes": 60, "at_hour": 4}):
            return reset.run(self.db, dry_run=True, now=self.now)

    def test_idle_session_flagged(self):
        self.add("s1", self.now - 90 * 60)
        self.assertEqual(self.run_reset(), 1)

    def test_triggers_do_not_keep_session_alive(self):
        self.add("s1", self.now - 90 * 60, trigger_ts=self.now - 5 * 60 - 200)
        self.assertEqual(self.run_reset(), 1)

    def test_busy_session_is_skipped(self):
        self.add("s1", self.now - 90 * 60, trigger_ts=self.now - 30)
        self.assertEqual(self.run_reset(), 0)

    def test_session_without_user_messages_is_not_reset(self):
        self.add("s1", None, trigger_ts=self.now - 120 * 60, started=self.now - 120 * 60)
        self.assertEqual(self.run_reset(), 0)

    def test_compression_child_uses_start_as_activity(self):
        self.add("s2", None, parent="s1", started=self.now - 10 * 60)
        self.assertEqual(self.run_reset(), 0)

    def test_compression_lock_blocks_reset(self):
        self.add("s1", self.now - 90 * 60)
        self.c.execute("INSERT INTO compression_locks VALUES('s1','x',0,?)", (self.now + 100,))
        self.c.commit()
        self.assertEqual(self.run_reset(), 0)


class Retry(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.db = str(Path(self.tmp.name) / "state.db")
        self.tries = str(Path(self.tmp.name) / "tries.json")
        self.c = make_state(self.db)
        self.now = 1_000_000.0

    def tearDown(self):
        self.tmp.cleanup()

    def add(self, oid, state="failed", err="send_path_degraded", created=None, updated=None, content="hola"):
        self.c.execute("INSERT INTO delivery_obligations VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                       (oid, "k", "telegram", "123", None, content, state, 1,
                        created or self.now - 600, updated or self.now - 300, err))
        self.c.commit()

    def state(self, oid):
        return self.c.execute("SELECT state FROM delivery_obligations WHERE obligation_id=?", (oid,)).fetchone()[0]

    def test_delivers_and_marks_delivered(self):
        self.add("a")
        with mock.patch.object(retry, "get_token", return_value="T"), \
                mock.patch.object(retry, "send_message", return_value=(True, "")) as send:
            n = retry.run(self.db, self.tries, now=self.now)
        self.assertEqual(n, 1)
        self.assertEqual(self.state("a"), "delivered")
        send.assert_called_once()

    def test_failed_send_stays_failed_and_counts_try(self):
        self.add("a")
        with mock.patch.object(retry, "get_token", return_value="T"), \
                mock.patch.object(retry, "send_message", return_value=(False, "timeout")):
            retry.run(self.db, self.tries, now=self.now)
        self.assertEqual(self.state("a"), "failed")
        self.assertEqual(retry.load_tries(self.tries)["a"], 1)

    def test_ignores_other_errors_old_rows_and_fresh_updates(self):
        self.add("other", err="400:bad request")
        self.add("old", created=self.now - 7 * 3600)
        self.add("fresh", updated=self.now - 2)
        with mock.patch.object(retry, "get_token", return_value="T"), \
                mock.patch.object(retry, "send_message", return_value=(True, "")) as send:
            retry.run(self.db, self.tries, now=self.now)
        send.assert_not_called()

    def test_long_message_is_chunked(self):
        parts = retry.split_text("palabra " * 1200)
        self.assertGreater(len(parts), 1)
        self.assertTrue(all(len(p) <= retry.CHUNK for p in parts))

    def test_known_good_ip_first_dns_last(self):
        order = retry.ip_order(None)
        self.assertEqual(order[0], "149.154.167.99")
        self.assertIsNone(order[-1])


if __name__ == "__main__":
    unittest.main()
