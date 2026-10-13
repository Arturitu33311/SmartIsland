import os
import sys
import tempfile
import unittest

sys.path.insert(0, os.path.dirname(__file__))
from jinx_thread import Thread
import jinx_chat
from jinx_chat import Deps, handle, run_line


class FakeBrain:
    def __init__(self, text="respuesta", fail=False):
        self.calls = []
        self.text = text
        self.fail = fail

    def reply(self, messages):
        self.calls.append(messages)
        if self.fail:
            raise RuntimeError("modelo caído")
        return "or", self.text


class FakeMirror:
    def __init__(self, ok=True):
        self.ok = ok
        self.sent = []

    def send(self, text):
        self.sent.append(text)
        return self.ok


class GatewayTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.thread = Thread(self.dir.name)
        self.brain = FakeBrain()
        self.mirror = FakeMirror()
        self.deps = Deps(self.thread, self.brain, self.mirror, "CORE", "MEM",
                         lambda: {"openrouter_key": "k", "telegram_token": "t", "chat": "42"},
                         self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def events(self, req):
        return list(handle(req, self.deps))

    def test_send_answers_stores_both_lines_and_mirrors(self):
        ev = self.events({"op": "send", "text": "hola", "cid": "c1", "src": "s21"})
        self.assertEqual(ev[0], {"ev": "ack"})
        self.assertEqual(ev[-1], {"ev": "done", "mode": "or", "text": "respuesta", "mirrored": True})
        ids = [d["id"] for d in self.thread.read()]
        self.assertEqual(ids, ["c1:s21", "c1:jinx"])
        self.assertEqual(self.mirror.sent, ["📱 hola", "🟠 respuesta"])
        self.assertEqual(self.thread.unmirrored(), [])

    def test_same_cid_twice_does_not_call_the_model_again(self):
        self.events({"op": "send", "text": "hola", "cid": "c1", "src": "s21"})
        ev = self.events({"op": "send", "text": "hola", "cid": "c1", "src": "s21"})
        self.assertEqual(len(self.brain.calls), 1)
        self.assertEqual(ev[-1], {"ev": "done", "mode": "cached", "text": "respuesta", "mirrored": True})
        self.assertEqual(len(self.thread.read()), 2)

    def test_context_given_to_the_model_includes_telegram_lines(self):
        self.thread.append("tg", "dime algo desde telegram", ts=1.0, mirrored=True)
        self.events({"op": "send", "text": "y ahora", "cid": "c2", "src": "s21"})
        sent = self.brain.calls[0]
        self.assertEqual([m["content"] for m in sent], ["dime algo desde telegram", "y ahora"])

    def test_model_failure_gives_error_and_no_jinx_line(self):
        self.brain.fail = True
        ev = self.events({"op": "send", "text": "hola", "cid": "c1", "src": "s21"})
        self.assertEqual(ev[-1]["ev"], "error")
        self.assertIn("modelo caído", ev[-1]["msg"])
        self.assertEqual([d["id"] for d in self.thread.read()], ["c1:s21"])

    def test_mirror_failure_still_answers_and_leaves_lines_unmirrored(self):
        self.mirror.ok = False
        ev = self.events({"op": "send", "text": "hola", "cid": "c1", "src": "s21"})
        self.assertEqual(ev[-1]["ev"], "done")
        self.assertEqual(len(self.thread.unmirrored()), 2)

    def test_next_send_mirrors_what_was_left_behind(self):
        self.mirror.ok = False
        self.events({"op": "send", "text": "uno", "cid": "c1", "src": "s21"})
        self.mirror.ok = True
        self.mirror.sent.clear()
        self.events({"op": "send", "text": "dos", "cid": "c2", "src": "s21"})
        self.assertEqual(self.thread.unmirrored(), [])
        self.assertEqual(self.mirror.sent[:2], ["📱 uno", "🟠 respuesta"])

    def test_telegram_text_is_cut_but_thread_keeps_it_whole(self):
        long = "x" * 10000
        self.events({"op": "send", "text": long, "cid": "c1", "src": "s21"})
        self.assertEqual(len(self.mirror.sent[0]), 4000)
        self.assertEqual(self.thread.read()[0]["text"], long)

    def test_send_without_text_or_cid_is_an_error(self):
        self.assertEqual(self.events({"op": "send", "cid": "c"})[-1]["ev"], "error")
        self.assertEqual(self.events({"op": "send", "text": "x"})[-1]["ev"], "error")
        self.assertEqual(self.thread.read(), [])

    def test_unknown_src_is_rejected(self):
        ev = self.events({"op": "send", "text": "x", "cid": "c", "src": "evil"})
        self.assertEqual(ev[-1]["ev"], "error")

    def test_history_returns_last_n(self):
        for i in range(5):
            self.thread.append("tg", "m%d" % i, ts=float(i))
        ev = self.events({"op": "history", "n": 2})
        self.assertEqual([d["text"] for d in ev[0]["items"]], ["m3", "m4"])

    def test_merge_adds_lines_returns_missing_and_mirrors_unmirrored(self):
        self.thread.append("tg", "de telegram", ts=1.0, mirrored=True)
        incoming = [{"id": "c9:s22", "ts": 5.0, "src": "s22", "text": "sin red",
                     "cid": "c9", "mirrored": False}]
        ev = self.events({"op": "merge", "lines": incoming, "have": []})[-1]
        self.assertEqual(ev["ev"], "merged")
        self.assertEqual(ev["added"], 1)
        self.assertEqual([d["text"] for d in ev["items"]], ["de telegram", "sin red"])
        self.assertEqual(ev["mirrored_ids"], ["c9:s22"])
        self.assertEqual(self.mirror.sent, ["📱 sin red"])

    def test_concurrent_sends_of_the_same_cid_call_the_model_once(self):
        import threading
        import time

        class SlowBrain(FakeBrain):
            def reply(self, messages):
                time.sleep(0.3)
                return super().reply(messages)

        self.deps.brain = SlowBrain()
        results = []

        def work():
            results.append(list(handle({"op": "send", "text": "hola", "cid": "c1", "src": "s21"}, self.deps)))

        ts = [threading.Thread(target=work) for _ in range(2)]
        [t.start() for t in ts]
        [t.join() for t in ts]
        self.assertEqual(len(self.deps.brain.calls), 1)
        self.assertEqual([r[-1]["text"] for r in results], ["respuesta", "respuesta"])
        self.assertEqual(len(self.thread.read()), 2)

    def test_done_reports_whether_the_lines_reached_telegram(self):
        ev = self.events({"op": "send", "text": "a", "cid": "c1", "src": "s21"})
        self.assertIs(ev[-1]["mirrored"], True)
        self.mirror.ok = False
        ev = self.events({"op": "send", "text": "b", "cid": "c2", "src": "s21"})
        self.assertIs(ev[-1]["mirrored"], False)
        ev = self.events({"op": "send", "text": "b", "cid": "c2", "src": "s21"})
        self.assertEqual(ev[-1]["mode"], "cached")
        self.assertIs(ev[-1]["mirrored"], False)

    def test_merge_reports_every_sent_line_that_is_mirrored_even_if_not_by_this_call(self):
        self.thread.append("s22", "ya espejada", cid="c9", ts=5.0, mirrored=True)
        incoming = [{"id": "c9:s22", "ts": 5.0, "src": "s22", "text": "ya espejada",
                     "cid": "c9", "mirrored": False}]
        ev = self.events({"op": "merge", "lines": incoming, "have": []})[-1]
        self.assertEqual(ev["added"], 0)
        self.assertEqual(ev["mirrored_ids"], ["c9:s22"])

    def test_core_returns_persona_and_memory(self):
        self.assertEqual(self.events({"op": "core"})[-1],
                         {"ev": "core", "core": "CORE", "memory": "MEM"})

    def test_provision_is_one_shot_and_gated_by_flag_file(self):
        self.assertEqual(self.events({"op": "provision"})[-1]["ev"], "error")
        flag = os.path.join(self.dir.name, "ALLOW_PROVISION")
        open(flag, "w").close()
        ev = self.events({"op": "provision"})[-1]
        self.assertEqual(ev, {"ev": "secrets", "openrouter_key": "k",
                              "telegram_token": "t", "chat": "42"})
        self.assertFalse(os.path.exists(flag))
        self.assertEqual(self.events({"op": "provision"})[-1]["ev"], "error")

    def test_run_line_survives_garbage(self):
        for raw in ["", "   ", "{no", "[1]", '{"op":"nope"}', '{"nop":1}']:
            out = list(run_line(raw, self.deps))
            self.assertEqual(len(out), 1, raw)
            self.assertEqual(out[0]["ev"], "error", raw)
        self.assertEqual(self.thread.read(), [])


class TelegramMirrorTest(unittest.TestCase):
    def test_posts_sendmessage_and_reports_ok(self):
        seen = {}

        class Resp:
            status = 200

            def read(self):
                return b'{"ok":true}'

            def __enter__(self):
                return self

            def __exit__(self, *a):
                return False

        def opener(req, timeout=0):
            seen["url"] = req.full_url
            seen["body"] = req.data
            return Resp()

        m = jinx_chat.TelegramMirror("TOK", "42", opener=opener)
        self.assertTrue(m.send("hola"))
        self.assertTrue(seen["url"].endswith("/botTOK/sendMessage"))
        self.assertIn(b'"chat_id": "42"', seen["body"])

    def test_network_error_is_false_not_an_exception(self):
        def opener(req, timeout=0):
            raise OSError("sin red")
        self.assertFalse(jinx_chat.TelegramMirror("T", "1", opener=opener).send("x"))

    def test_no_token_means_no_mirror(self):
        self.assertFalse(jinx_chat.TelegramMirror("", "", opener=None).send("x"))


if __name__ == "__main__":
    unittest.main()
