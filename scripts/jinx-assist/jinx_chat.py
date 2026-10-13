"""Gateway de chat de Jinx para los teléfonos (comando forzado por ssh).

Lee UNA línea JSON de stdin y escribe eventos NDJSON en stdout. Ver
docs/superpowers/specs/2026-10-13-jinx-assistant-text-design.md.
No hace getUpdates ni toca webhooks: sólo sendMessage.
"""
import contextlib
import fcntl
import hashlib
import importlib.machinery
import importlib.util
import json
import os
import subprocess
import sys
import urllib.request
from dataclasses import dataclass
from typing import Callable

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from jinx_thread import Thread  # noqa: E402

HOME = os.path.expanduser("~")
BASE = os.path.join(HOME, "jinx-emergencia", "telegram")
SRCS = ("s21", "s22")
TG_MAX = 4000


@dataclass
class Deps:
    thread: Thread
    brain: object          # .reply(messages) -> (mode, text); lanza si falla
    mirror: object         # .send(text) -> bool
    core_text: str
    memory_text: str
    secrets: Callable[[], dict]
    base: str


def _err(msg):
    return {"ev": "error", "msg": msg}


def _mirror_pending(deps):
    """Intenta espejar, en orden, lo que quedó sin espejar. Para al primer fallo."""
    done = []
    for d in deps.thread.unmirrored():
        prefix = "🟠 " if d["src"] == "jinx" else "📱 "
        if not deps.mirror.send((prefix + d["text"])[:TG_MAX]):
            break
        done.append(d["id"])
    if done:
        deps.thread.mark_mirrored(set(done))
    return done


@contextlib.contextmanager
def _cid_lock(base, cid):
    """Serializa los envíos del mismo cid (un reintento espera a la primera generación y la reutiliza).

    Se usa un número fijo de archivos de candado (hash del cid) para que no se acumulen.
    """
    bucket = int(hashlib.sha1(cid.encode("utf-8")).hexdigest(), 16) % 64
    with open(os.path.join(base, "inflight-%02d.lock" % bucket), "a+") as lf:
        fcntl.flock(lf, fcntl.LOCK_EX)
        try:
            yield
        finally:
            fcntl.flock(lf, fcntl.LOCK_UN)


def _mirrored(deps, ids):
    by_id = {d["id"]: d for d in deps.thread.read()}
    return all(by_id.get(i, {}).get("mirrored") for i in ids)


def _send(req, deps):
    text = req.get("text")
    cid = req.get("cid")
    src = req.get("src", "s21")
    if not isinstance(text, str) or not text.strip() or not isinstance(cid, str) or not cid:
        yield _err("send necesita text y cid")
        return
    if src not in SRCS:
        yield _err("src desconocido")
        return
    ids = ["%s:%s" % (cid, src), "%s:jinx" % cid]
    with _cid_lock(deps.base, cid):
        cached = deps.thread.answer_for(cid)
        if cached:
            _mirror_pending(deps)
            yield {"ev": "ack"}
            yield {"ev": "done", "mode": "cached", "text": cached["text"],
                   "mirrored": _mirrored(deps, ids)}
            return
        deps.thread.append(src, text, cid=cid)
        yield {"ev": "ack"}
        try:
            mode, out = deps.brain.reply(deps.thread.context())
        except Exception as e:  # noqa: BLE001 - el modelo puede fallar de mil formas
            yield _err("no pude generar respuesta: %s" % e)
            return
        out = (out or "").strip() or "(sin respuesta)"
        line = deps.thread.append("jinx", out, cid=cid)
        _mirror_pending(deps)
        yield {"ev": "done", "mode": mode, "text": line["text"], "mirrored": _mirrored(deps, ids)}


def handle(req, deps):
    op = req.get("op")
    if op == "send":
        yield from _send(req, deps)
    elif op == "history":
        n = req.get("n", 40)
        n = n if isinstance(n, int) and 0 < n <= 500 else 40
        yield {"ev": "history", "items": deps.thread.read()[-n:]}
    elif op == "merge":
        lines = req.get("lines")
        have = req.get("have")
        if not isinstance(lines, list) or not isinstance(have, list):
            yield _err("merge necesita lines y have")
            return
        added = deps.thread.merge(lines)
        _mirror_pending(deps)
        sent_ids = {d["id"] for d in lines if isinstance(d, dict) and isinstance(d.get("id"), str)}
        # Todo lo que el teléfono mandó y que YA está espejado en el hilo (por esta llamada o antes).
        mirrored_ids = [d["id"] for d in deps.thread.read() if d["id"] in sent_ids and d["mirrored"]]
        yield {"ev": "merged", "added": len(added),
               "items": deps.thread.since({h for h in have if isinstance(h, str)}),
               "mirrored_ids": mirrored_ids}
    elif op == "core":
        yield {"ev": "core", "core": deps.core_text, "memory": deps.memory_text}
    elif op == "provision":
        flag = os.path.join(deps.base, "ALLOW_PROVISION")
        if not os.path.exists(flag):
            yield _err("provision no autorizado")
            return
        s = deps.secrets()
        os.remove(flag)
        yield {"ev": "secrets", "openrouter_key": s.get("openrouter_key", ""),
               "telegram_token": s.get("telegram_token", ""), "chat": s.get("chat", "")}
    else:
        yield _err("op desconocida")


def run_line(raw, deps):
    raw = (raw or "").strip()
    if not raw:
        yield _err("petición vacía")
        return
    try:
        req = json.loads(raw)
    except ValueError:
        yield _err("petición no es JSON")
        return
    if not isinstance(req, dict) or "op" not in req:
        yield _err("petición sin op")
        return
    try:
        yield from handle(req, deps)
    except Exception as e:  # noqa: BLE001
        yield _err("error interno: %s" % e)


# ---------- implementaciones reales ----------

class TelegramMirror:
    def __init__(self, token, chat, opener=urllib.request.urlopen):
        self.token, self.chat, self.opener = token, chat, opener

    def send(self, text):
        if not self.token or not self.chat or self.opener is None:
            return False
        data = json.dumps({"chat_id": str(self.chat), "text": text[:TG_MAX]}).encode()
        req = urllib.request.Request(
            "https://api.telegram.org/bot%s/sendMessage" % self.token, data=data,
            headers={"Content-Type": "application/json"})
        try:
            with self.opener(req, timeout=15) as r:
                return r.status == 200
        except Exception:  # noqa: BLE001 - sin red, 4xx, timeout: se reintenta luego
            return False


def _load_jinx():
    path = os.path.join(HOME, "bin", "jinx")
    loader = importlib.machinery.SourceFileLoader("jinxmod", path)
    spec = importlib.util.spec_from_loader("jinxmod", loader)
    mod = importlib.util.module_from_spec(spec)
    loader.exec_module(mod)
    return mod


def _vault_get(jx, key):
    try:
        out = subprocess.run(
            [jx.VAULT_PY, "-c", "from vaultknox.autonomous_secrets import "
             "get_secret as g; print(g(%r))" % key],
            capture_output=True, text=True, timeout=10)
        return out.stdout.strip()
    except Exception:  # noqa: BLE001
        return ""


def _read(path):
    with open(path, encoding="utf-8") as f:
        return f.read()


class JinxBrain:
    """Mismo cerebro de respaldo que el relay: OpenRouter, si no, Ollama local."""

    def __init__(self, jx):
        self.jx = jx
        self.core = _read(jx.CORE_PATH if os.path.exists(jx.CORE_PATH) else jx.SOUL_PATH)
        self.mem, _ = jx.load_synced_memory()
        self.key = os.environ.get("JINX_OR_KEY") or jx.get_api_key()

    def system(self, mode):
        core = self.core
        if mode == "or":
            core = core.replace("en un modelo local pequeño", "sobre un modelo capaz vía OpenRouter")
        return self.jx.PREAMBLE[mode] + "\n\n---\n\n" + core + (self.mem if mode == "or" else "")

    def reply(self, messages):
        jx = self.jx
        mode = "or" if (self.key and jx.openrouter_reachable(self.key)) else "local"
        msgs = [{"role": "system", "content": self.system(mode)}] + messages
        try:
            if mode == "or":
                return mode, "".join(jx.stream_openrouter(msgs, self.key))
            jx.pick_local_backend()
            return mode, "".join(jx.strip_think(jx.stream_ollama(msgs)))
        except Exception:  # noqa: BLE001 - OpenRouter cayó a medias -> local
            if mode != "or":
                raise
            jx.pick_local_backend()
            msgs[0]["content"] = self.system("local")
            return "local", "".join(jx.strip_think(jx.stream_ollama(msgs)))


def build_real_deps(base=BASE):
    jx = _load_jinx()
    token = os.environ.get("JINX_TG_TOKEN") or os.environ.get("TELEGRAM_BOT_TOKEN") \
        or _vault_get(jx, "TELEGRAM_BOT_TOKEN")
    chat = os.environ.get("JINX_TG_CHAT") or os.environ.get("TELEGRAM_ALLOWED_CHAT") \
        or _vault_get(jx, "TELEGRAM_ALLOWED_CHAT")
    brain = JinxBrain(jx)
    memory = jx.load_synced_memory()[0]
    return Deps(Thread(base), brain, TelegramMirror(token, chat),
                brain.core, memory,
                lambda: {"openrouter_key": brain.key or "", "telegram_token": token, "chat": chat},
                base)


def main(stdin=sys.stdin, stdout=sys.stdout):
    raw = stdin.readline()
    try:
        req = json.loads(raw) if raw.strip() else {}
    except ValueError:
        req = {}
    # Sólo se construye el cerebro real si la op lo necesita (history no).
    needs_real = isinstance(req, dict) and req.get("op") in ("send", "core", "provision", "merge")
    try:
        deps = build_real_deps() if needs_real else Deps(
            Thread(BASE), None, TelegramMirror("", ""), "", "", dict, BASE)
    except Exception as e:  # noqa: BLE001
        stdout.write(json.dumps(_err("gateway sin cerebro: %s" % e)) + "\n")
        return 0
    for ev in run_line(raw, deps):
        stdout.write(json.dumps(ev, ensure_ascii=False) + "\n")
        stdout.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
