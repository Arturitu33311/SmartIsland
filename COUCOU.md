# Coucou on this SmartIsland fork

What was added on top of SmartIsland (branch `coucou`). Handoff and the sync model live with the PC app:
`windows/scripts/phone/README.md` and `docs/HANDOFF-2026-10-09.md` in Coucou's `linux-ask-user-question` worktree.

* `coucou/` — tasks with reminders (Mochi's desk), parser, store, exact alarms.
* `coucou/phone/` — the phone link: RSA key kept with the Android Keystore, host-key pinning, restricted ssh to
  the server (Jinx's pendientes gateway) and to the laptop (`coucou-hook --remote`). `Ssh.installProvider()` swaps
  Android's stripped "BC" for the real BouncyCastle once, at app start, atomically — do not touch the providers
  elsewhere: the platform's TLS needs "BC" (it holds the default BKS key store).
* `coucou/agents/` — Claude Code on the island (sessions, permissions, questions) and the task meeting
  (`tasks_sync`) over the same laptop link.
* `coucou/presence/` — Jinx's texts on the phone (written by other models; see the risks in the handoff).
* Island: modes ordered by `IslandMode.priority`; Coucou's own cards (`isCoucou`) never open Smart Island and are
  never torn down as "an app's notifications".

Tasks: each device works alone; the newest change wins when they meet (`TaskLogic.merge`, `reconcile`).
`app/src/test/resources/merge-vectors.json` must stay identical to the PC's `windows/scripts/phone/merge-vectors.json`.

Debugging tips: a crashed accessibility service shows in `dumpsys accessibility` ("Crashed services");
the crash itself is in `dumpsys dropbox --print data_app_crash` (a `logcat -c` erases the rest).
