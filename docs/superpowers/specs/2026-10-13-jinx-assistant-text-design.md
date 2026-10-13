# Jinx as the phone's assistant — phase 1: two-way text

Date: 2026-10-13 · Status: draft for review · Branch: `fix/tertiary-collapsed`

## Goal

On the S21 Ultra and S22 Ultra, Jinx can be chosen as the *Digital assistant app*
(Settings → Apps → Choose default apps). Long-pressing the side key opens a panel where
Alan writes to Jinx and reads the answer, **in the same conversation he has with Jinx on
Telegram**, and sees what Claude Code sessions and Jinx's pendientes are doing.

Out of scope for phase 1: voice (STT/TTS), "Hey Jinx", reading the screen, acting in
other apps. They come in later phases and must not need a redesign of this one.

## Decisions taken (2026-10-13)

1. **Mirror to Telegram: yes.** What is said at the phone appears in the Telegram chat
   (`📱 …` for Alan's text, `🟠 …` for Jinx's answer), so Telegram never loses context.
2. **The gateway lives on the laptop, temporarily.** The real brain (Hermes, on `server`)
   is down since 2026-10-09. The gateway answers with the fallback Jinx (JINX-CORE +
   synced memory, OpenRouter → local Ollama). It moves to / forwards to Hermes when the
   server is back (see "Open questions").

## What exists today (verified)

| Piece | Where | Used for |
|---|---|---|
| ssh with the phone's own key, one command per connection | `coucou/phone/Ssh.kt`, `PhoneKey.kt`, `PhoneSettings.kt` (`LAPTOP` profile, host key pinned) | pendientes (`JinxClient.kt`), Claude Code stream (`agents/AgentRemote.kt`) |
| Jinx's messages on the island, with Mochi's mood | `coucou/presence/JinxMsgUi.kt` (`mochi:<Mood>:<kind>`) | answers shown when the island is visible |
| Compose outside an Activity | `service/OverlayViewTreeOwners.kt` | optional |
| Telegram relay with brain, history (20 msgs) and logs | `~/bin/jinx-telegram`, `~/jinx-emergencia/telegram/history-<device>.json`, `logs/telegram-*.jsonl` | fallback Jinx |
| Device priority and heartbeat (laptop > s21 > s22) | `jinx-telegram` `BEATS` | who answers Telegram |

Two facts that shape the design:

- **Telegram's Bot API cannot send as Alan**, and only one consumer may call
  `getUpdates`. So the phone never talks to Telegram: it talks to a gateway, and the
  gateway mirrors with `sendMessage` (which does not conflict with the relay's polling).
- **The laptop's authorized key is pinned to one forced command**
  (`coucou-hook --remote`, a Rust binary that does not read `SSH_ORIGINAL_COMMAND`).
  A second command needs a dispatcher in front. The phone already sends a command
  name in the ssh exec string (`stream("coucou")`), so the Android side needs no change
  to be dispatched.

## Architecture

```
side key ─▶ JinxVoiceInteractionService ─▶ startAssistantActivity ─▶ JinxAssistActivity (Compose)
                                                                       │ JinxChatClient (ChatApi)
                                      ssh  laptop  "jinx-chat"         ▼
                          authorized_keys: command="coucou-dispatch"  ──▶ jinx-chat
                                                                           ├─ thread (flock'd files)
                                                                           ├─ Brain (shared with relay)
                                                                           ├─ mirror → Telegram sendMessage
                                                                           └─ sessions ← coucou state
```

### Laptop side

**`~/bin/coucou-dispatch`** (new, ~15 lines, forced command for the phone keys):
reads `SSH_ORIGINAL_COMMAND`: `coucou` → `exec coucou-hook --remote` (today's
behaviour, byte-for-byte); `jinx-chat` → `exec ~/bin/jinx-chat`; anything else → exit 2.
No shell is ever given. `authorized_keys` is edited by `jinx-phone-onboard` (it already
owns the phone-key lines); the old lines are backed up first.

**`~/bin/jinx-chat`** (new, Python): one request line on stdin, NDJSON events on stdout.

| op | request | events |
|---|---|---|
| `history` | `{"op":"history","n":40}` | `{"ev":"history","items":[{ts,src,text}]}` |
| `send` | `{"op":"send","text":"…","cid":"<uuid>"}` | `{"ev":"ack"}` `{"ev":"delta","t":"…"}`… `{"ev":"done","mode":"or\|local","text":"…"}` or `{"ev":"error","msg":"…"}` |
| `sessions` | `{"op":"sessions"}` | `{"ev":"sessions","items":[…],"pendientes":[…]}` |

`cid` makes `send` idempotent: a retry after a dropped connection returns the stored
answer instead of asking the model twice.

**Shared thread (`~/jinx-emergencia/telegram/`)**: today `Brain` in `jinx-telegram`
loads its history once at start and writes it after each reply, so two writers would
overwrite each other. Change:

- Extract `Thread` + `Brain` from `jinx-telegram` into `~/bin/jinx_thread.py`
  (importable by both). `Thread` takes an `flock` on `thread.lock`, **re-reads**
  `history-laptop.json` before every reply and writes it atomically (as now).
- New append-only `thread.jsonl` with `{ts, src: "tg"|"s21"|"s22"|"jinx", text}` for
  display. `history-*.json` stays what it is (model context, trimmed to 20).
- `jinx-telegram` is changed only to use `Thread` and to append to `thread.jsonl`;
  its loop, priority, 409 and webhook rules are untouched (its `--self-check` still
  forbids `deleteWebhook`/`setWebhook`/`logOut`).

**Mirror**: after the answer, `sendMessage` to the allowed chat with token/chat from the
vault (same source as the relay). Failure to mirror never fails the answer; it is
retried from `thread.jsonl` on the next request (`mirrored` flag per line). While the
relay is *idle* because the real server is up, the gateway still answers (it is a
separate, explicit entry point) and labels the badge "Jinx de respaldo".

**`sessions`**: reads the live Claude Code sessions and pendientes that the laptop's
Coucou already holds (same data `AgentUi.sessions/pending` shows). If the laptop's
Coucou is not running: `items: []` and the panel says so; it is not an error.

### Android side (`coucou/assist/`, new)

- `JinxVoiceInteractionService`, `JinxVoiceInteractionSessionService` and a stub
  `RecognitionService`, declared in the manifest with
  `res/xml/jinx_voice_interaction.xml` (`supportsAssist="true"`,
  `supportsLaunchVoiceAssistFromKeyguard="false"` in phase 1). The stub recogniser is
  there because the assistant list expects the attribute; **to verify on the emulator**
  that the app is listed without one.
- `JinxVoiceInteractionSession.onShow` → `startAssistantActivity(JinxAssistActivity)`.
  A normal Compose Activity is simpler to build and test than a session window.
- `JinxAssistActivity`: bottom panel with the thread (Alan / Jinx / source badge),
  an input box, Mochi in the mood of the last answer, a "respaldo" badge, and a small
  strip with Claude Code sessions and open pendientes. It works while the phone is
  locked only for reading; sending asks to unlock (`requestDismissKeyguard`).
- `ChatApi` (interface) + `SshChat` (real) + `JinxChatProtocol` (pure parse/format, unit
  tested like `JinxProtocol`). Reuses `Ssh.open(context, PhoneSettings.profile(LAPTOP))`;
  no new key, no new pairing screen.
- When the island is visible, the answer is also posted as a Jinx message
  (`mochi:<Mood>:info`) so Mochi shows it; the mood comes from the same mapper the
  island already uses.
- Offline / laptop unreachable: the panel shows the cached last thread (kept in
  app-private storage) and queues the text in an outbox that is sent on reconnect,
  with a visible "pendiente de enviar" state. It never fakes an answer.

## Fallback chain: the phones as gateways (requested 2026-10-13)

The same `jinx-chat` protocol must keep working when the laptop is off, because the
Telegram relay already falls back laptop > s21 > s22 and the assistant should not be
weaker than that. `jinx-chat`, `jinx_thread.py` and `jinx` are plain Python, so they run
unchanged in Termux on each phone (it is where `jinx-telegram` already runs).

`JinxChatClient` tries targets in this order and **remembers the last one that worked**
(tried first next time; each probe uses a 3 s connect, not `Ssh`'s 8 s):

| # | Target | How | Needs |
|---|---|---|---|
| 1 | Laptop | `Ssh` profile `LAPTOP`, command `jinx-chat` via `coucou-dispatch` | laptop on, Tailscale |
| 2 | **This phone's own Termux** | `127.0.0.1:8022`, same key, same command | Termux sshd up; works with **no Tailscale and no laptop** |
| 3 | The other phone's Termux | `100.x:8022` from `phones.conf` | that phone on, Tailscale |
| 4 | Offline | cached thread + outbox, nothing invented | — |

Rules that keep the chain honest:

- **One forced command per phone key in Termux too**: `command="…/jinx-chat-dispatch"`
  restricted the same way as on the laptop (`coucou` is not offered there; only
  `jinx-chat`). `jinx-phone-onboard` gets a `chat-key` subcommand for it (it already
  handles `coucou-key|laptop-key|relay|status`).
- **The thread is the single source of truth.** `history-*.json` (the model's
  20-message context) is *derived* from the tail of `thread.jsonl`, never merged on its
  own. So the only thing to reconcile between devices is `thread.jsonl`.
- **Reconciliation**: `jinx-sync-celular` (every 10 min, already multi-phone) pushes the
  laptop's `thread.jsonl` and pulls each phone's, then merges by `(ts, cid)` with dedupe
  and rewrites each device's copy. A message answered by a phone while the laptop was off
  reaches the laptop thread, so the next answer anywhere sees it. If a sync has not run,
  a device answers from what it has; it never blocks on another.
- **Mirror from a phone**: allowed only while that phone holds the Telegram turn or the
  relay is idle by the existing priority/heartbeat rules (so Hermes is never doubled);
  otherwise the line stays `mirrored:false` in `thread.jsonl` and is sent by whoever
  syncs it next. Phones already hold the bot token in `~/.jinx-secrets`.
- **Brain on a phone**: same fallback as the relay there — OpenRouter with
  `JINX_OR_KEY`, else local `gemma4:e2b` is *not* available (no Ollama in Termux), so
  with no internet a phone answers `error: sin modelo disponible` and the panel says so.
  A local model on the phone is out of scope here.

**Not working today (checked 2026-10-13):** laptop relay heartbeat `:18790` is up;
Termux sshd `:8022` refuses on the S21 and the S22, and the S21's relay heartbeat is
down. Also, the relay on a phone does not start itself after a reboot (Play-store
Termux has no Termux:Boot), and Termux needs "Unrestricted" battery. So phases 1a/1b:

- **1a** laptop gateway + the app (fully works with the laptop on).
- **1b** phone targets (#2, #3): needs Alan to start Termux sshd on each phone once
  and run `jinx-phone-onboard chat-key`; the app shows which targets answered
  ("laptop", "este teléfono", "otro teléfono") in the "respaldo" badge so a silent
  dead fallback is visible rather than discovered during an outage.

## Error handling

- Gateway unreachable / host key changed / key refused: reuse `Ssh.explain` messages;
  the panel shows them in plain words and keeps the text in the box.
- Model failure: `Brain` already falls back OpenRouter → local; if both fail the
  gateway returns `error` and nothing is appended to the thread as an answer.
- Two devices send at once: the `flock` serialises; each reply sees the other's turn.
- Telegram down: answer is delivered to the phone, mirror retried later.

## Security

- The phone's key can only run `coucou-dispatch`, which can only run two fixed programs.
  No shell, no arguments from the client reach a command line.
- `jinx-chat` never returns memory files, secrets or `USER.md`; only the thread, which
  is already in Alan's own Telegram chat.
- The vault token is read on the laptop only; it never goes to the phone.
- Text typed at the phone is mirrored to a chat only Alan is allowed in (the relay's
  allowlist applies to the chat id used for `sendMessage`).

## Testing

- Python: `jinx_thread.py` and `jinx-chat` get `--self-check` (history trim, flock
  re-read by two writers, `cid` idempotency, mirror failure does not fail the answer,
  `coucou-dispatch` rejects unknown commands). `jinx-telegram --self-check` keeps
  passing.
- Kotlin: unit tests for `JinxChatProtocol` (events, malformed lines, `error`), outbox
  ordering, mood mapping reuse. No network (fake `ChatApi`).
- Emulator `coucou34`: the app is listed under assistants, the side-key gesture opens
  the panel, a fake gateway answers, dark mode renders. Phones: **no taps are injected**
  (the user is using them); install with `install -r` on both, as usual.
- End to end on the laptop: a message from the panel appears in Telegram with `📱`,
  the answer with `🟠`, and Telegram's next message sees both in `history-laptop.json`.

## Samsung notes

Side key long-press defaults to Bixby. Alan sets *Side key → Press and hold → Digital
assistant* (wording varies by One UI version) and *Default apps → Digital assistant
app → Coucou/Jinx*. Phase 1 ships a short `docs/ASSISTANT.md` with these steps.

## Open questions (do not block phase 1)

1. **Hermes' API.** The server is down, so how to inject a message into Hermes' own
   Telegram session is unverified (a `:8642` HTTP server was noted in memory, never
   read). When the server returns: read `gateway/` in `hermes-agent`, then add a
   `HermesBackend` behind the same `jinx-chat` protocol. The phone does not change.
2. **Where the gateway ends up**: laptop (temporary) vs `server`. Decided later; the
   protocol is the same.
3. **S22 key**: the S22's Coucou key is not authorized yet (see ADD-A-PHONE.md);
   `jinx-phone-onboard` handles it. Needed for target #1 from the S22; targets #2/#3
   additionally need Termux sshd (see "Fallback chain").
4. **Model on the phone with no internet**: not solved (no Ollama in Termux). Options
   for later: llama.cpp in Termux, or an in-app model. Not in phase 1.

## Phases after this one (not designed here)

2 voice (STT/TTS, streaming answers) · 3 screen context (`AssistStructure`,
screenshot) · 4 actions (intents, then accessibility with confirmations) ·
5 hotword and Jinx-only extras.
