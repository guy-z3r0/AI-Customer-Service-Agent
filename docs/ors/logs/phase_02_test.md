# Phase 2 test script — Voice loop v2

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 1's test script passed, Docker Desktop is running,
and you have a working microphone. Use headphones for steps 8 onward — speakers
make the agent hear itself, which is the exact thing step 11 is checking.

---

## A. Three services come up

**1.** Rebuild and start everything.

```bash
docker compose up --build
```

*Expected:* three services report ready — `postgres`, `java-backend`, and now
`python-voice`, whose log ends with `Voice server ready. Java backend at
http://java-backend:8080`. No stack traces.

**2.** Ask the voice server directly what it would use.

```bash
curl -s http://localhost:8090/health
```

*Expected:* `{"status":"up","activeCalls":0,"stt":"fallback","tts":"fallback","googleCredentials":"missing"}`.
Fallback is correct with no Google key — that is the whole point of it.

**3.** Check the backend can see it too.

```bash
curl -s http://localhost:8080/api/health
```

*Expected:* `"voiceServer":"up"`.

**4.** Open http://localhost:8080.

*Expected:* the status line at the bottom now reads
`Voice server: up` where Phase 1 said `arrives in Phase 2`.

---

## B. The page before a call

**5.** Click **Live call** in the rail.

*Expected:* a dial card with three buttons — **Start browser call** in accent,
**Twilio call** greyed out, **End call** greyed out. Below it: State "Not on a
call", the active business's name, and dashes for call id, turns and median
reply time. The transcript panel reads "Nothing has been said yet".

**6.** Hover the **Twilio call** button.

*Expected:* a tooltip reading "Add Twilio credentials in Settings to enable this
(Phase 7)." It stays disabled — telephony is Phase 7 and says so rather than
failing when pressed.

**7.** Go to **Businesses** and make sure one is active, then come back.

*Expected:* the Business row on the Live Call page shows that business's name.
If no business is active, step 8 will refuse with "Choose an active business
before starting a call." — which is itself worth seeing once.

---

## C. The call itself

**8.** Put headphones on. Press **Start browser call** and allow the microphone
when the browser asks. Say, clearly and then stop: *"hello there"*.

*Expected, within about two seconds of you stopping:*
- State changes to "Listening", then "Agent speaking" while it replies
- You hear a voice say "You said: hello there"
- Two rows appear in the transcript: a blue **Caller** row with your words, and
  a green **Agent** row with the reply
- A timing badge sits on the agent row — green under 2000 ms, pink over
- Turns becomes 1 and Median reply time shows a number

**9.** Say two more sentences, pausing between them.

*Expected:* three turns total, each echoed audibly, each with its own timing
badge. The median updates. The transcript scrolls to keep the newest line in
view.

**10.** Press **End call**.

*Expected:* State becomes "Call ended", **Start browser call** is live again,
**End call** greys out, and the transcript stays on screen.

---

## D. The rules that make it a call and not a loop

**11.** Start another call. While the agent is speaking its reply, talk over it.

*Expected:* the agent finishes its sentence — it is not interrupted — and what
you said during it is ignored entirely. No new turn appears from it. This is the
half-duplex gate, and without it the agent answers its own voice forever.

**12.** Start a call and cough, or tap the desk, instead of speaking.

*Expected:* nothing happens. A noise shorter than a quarter second is not
treated as a sentence.

**13.** Start a call and then click **Businesses** in the rail without pressing
End.

*Expected:* the call hangs up on its own. Check it was recorded as ended:

```bash
docker compose exec postgres psql -U agent agent -c "SELECT started_at, ended_at, termination_reason FROM call_record ORDER BY started_at DESC LIMIT 3;"
```

*Expected:* every row has an `ended_at`, and the one you just left shows
`left_page`.

---

## E. It was all written down

**14.** Look at the transcript that was stored.

```bash
docker compose exec postgres psql -U agent agent -c "SELECT seq, role, text FROM call_message ORDER BY call_id, seq LIMIT 10;"
```

*Expected:* alternating `CALLER` and `AGENT` rows matching what you said and
heard. No half-finished sentences — the live guesses shown while you were
speaking are deliberately not stored.

**15.** Check the timings were kept per turn.

```bash
docker compose exec postgres psql -U agent agent -c "SELECT seq, role, t_stt_final, t_tts_first FROM call_message WHERE role = 'AGENT' ORDER BY seq LIMIT 5;"
```

*Expected:* both timestamps present on every agent row, with `t_tts_first`
after `t_stt_final`. The difference is the number on the badge you saw.

**16.** Open a second browser tab on the panel's Live Call page, then place a
call from the first.

*Expected:* the transcript appears in both tabs. The live feed is a broadcast,
so anyone watching sees the call.

---

## F. It works with no cloud account, and better with one

**17.** You have been in fallback mode this whole time. Confirm it:

```bash
docker compose logs python-voice | grep -i "provider\|fallback\|google"
```

*Expected:* no errors about missing credentials — the free providers were
chosen quietly, which is what "auto" means.

**18.** If you have a Google Cloud key, put it at `secrets/gcp-credentials.json`
and restart:

```bash
docker compose restart python-voice
curl -s http://localhost:8090/health
```

*Expected:* `"stt":"gcp"`, `"tts":"gcp"`, `"googleCredentials":"present"`.

**19.** Place another call.

*Expected:* the same call, three differences — a natural-sounding voice, faster
replies, and a live grey row that updates *while* you are still speaking, which
fallback mode cannot do.

**20.** Now break it on purpose: rename the credentials file and restart the
voice server.

```bash
mv secrets/gcp-credentials.json secrets/gcp-credentials.json.off
docker compose restart python-voice
```

*Expected:* `/health` says `fallback` again, and a call still works. Losing a
credential downgrades the call; it never stops it.

---

## G. The tests that need nothing at all

**21.** Run the voice server's own tests.

```bash
cd python-voice && python -m pytest -q
```

*Expected:* 11 passed. These need no microphone, no network and no account —
they cover the resampling, the μ-law codec, the endpointer, and the turn-taking
with stand-ins in place of the recogniser and the voice.

---

## Pass criteria

Steps 1–17 and 21 behave as described, with every reply audible and the median
reply time under 2000 ms in step 19 (Google mode). Steps 18–20 are skipped if
you have no Google account, and the phase still passes — that is the point of
the fallback.
