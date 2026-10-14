import importlib.util
import os
import sys
import types
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))


def load_module(stub_calls=None):
    """Carga panel_server.py con stubs de los módulos de Hermes (briefing_matutino, openrouter_saldo)."""
    bm = types.ModuleType("briefing_matutino")
    bm.recolectar_clima = lambda: {"ok": True, "temp_actual_c": 20}
    bm.recolectar_tareas = lambda: {"ok": True, "tareas": []}
    ors = types.ModuleType("openrouter_saldo")
    ors.SECRETS = "/bin/true"
    ors.tres_valores = lambda jinx, zyn=None: {"cuenta": 9.0, "jinx": {"restante": 49.0, "limite": 50},
                                                "zynara": {"restante": 1.0}}
    sys.modules["briefing_matutino"] = bm
    sys.modules["openrouter_saldo"] = ors
    spec = importlib.util.spec_from_file_location("panel_server_under_test", os.path.join(HERE, "panel_server.py"))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod, ors


class NoZynaraTest(unittest.TestCase):
    def test_the_source_mentions_zynara_nowhere(self):
        src = open(os.path.join(HERE, "panel_server.py"), encoding="utf-8").read()
        self.assertEqual(src.lower().count("zynara"), 0)

    def test_status_payload_has_no_zynara_key(self):
        mod, _ = load_module()
        mod.read_emocion = lambda: {"affection": 70, "patience": 65, "trust": 76}
        mod.read_disco = lambda: {"libre_gb": 100}
        mod.read_gateway = lambda: {"activo": True}
        mod.read_uptime = lambda: {"texto": "1D"}
        mod.read_saldo = lambda: {"cuenta": 9}
        payload = mod.status_payload()
        self.assertNotIn("zynara", payload)
        for k in ("clima", "tareas", "emocion", "saldo", "disco", "gateway", "uptime", "build", "ts"):
            self.assertIn(k, payload)

    def test_saldo_does_not_ask_for_the_zynara_secret_and_drops_its_value(self):
        mod, ors = load_module()
        asked = []

        class R:
            stdout = "KEY\n"

        def fake_run(args, **kw):
            asked.append(list(args))
            return R()

        mod.subprocess.run = fake_run
        mod._saldo_cache.update(ts=0.0, val=None)
        out = mod.read_saldo()
        self.assertTrue(all("ZYNARA" not in " ".join(a).upper() for a in asked), asked)
        self.assertNotIn("zynara", out)
        self.assertEqual(out["cuenta"], 9.0)


if __name__ == "__main__":
    unittest.main()
