# Turn-taking test script — the nine faults from live calls

Run these in order from the repository root. Each step says what you should see.

**Before you start:** Phase 7's test script passed and there is a real model key
in Settings. Steps 1–3 need nothing else. Steps 4–12 need a microphone. Steps
13–15 are about Bangla and say for themselves which need a Google key.

**Steps 5, 7 and 9 decide this pass**: a reply that is not cut off, an agent that
does not talk over you, and a call that ends itself instead of asking for ever.

---

## A. It builds

**1.** Both test suites.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, `Tests run: 148, Failures: 0, Errors: 0`.

```bash
cd python-voice && python -m pytest
```

*Expected:* `51 passed`.

**2.** Start the stack.

```bash
docker compose up --build
```

*Expected:* all three containers healthy, no new migration — this pass adds no
database change.

**3.** Open **Settings**.

*Expected:* **Bangla voice** no longer wears a `PLACEHOLDER` badge. Its menu
either lists Bangla voices, or reads **"No voice installed for this language"**
— which is a statement about this computer, not a fault. If there is no Google
key where the path says, a red line at the top of the page says so, and names
any file in `secrets/` whose name is nearly right.

---

## B. Speech that is not cut off

**4.** Start a browser call and let the greeting play out without speaking.

*Expected:* you hear the greeting and both halves of the language question, each
to its last word. The state line reads **Agent speaking** until the sound has
actually stopped, then **Listening**.

**5.** Ask something the agent can answer, then let it finish and say
"goodbye, thank you".

*Expected:* the farewell is spoken **in full** before the call ends. Nothing is
clipped mid-word. Compare with the transcript on screen: what is written is what
you heard, all of it.

**6.** Watch the transcript through three or four exchanges.

*Expected:* **every reply ends with a question.** "Would you like me to book
that?", "Is there anything else?" — you are never left without something to say
next. The only reply without one is the goodbye.

---

## C. Nobody talks over anybody

**7.** Ask a question, and while answering it take a slow twenty-five seconds —
read an address out, pause between words.

*Expected:* the agent stays silent for all of it. It does **not** ask "are you
still there?" over you, and your sentence arrives whole in the transcript. Set
**Warn after silence** to 15 in Settings first if you want to be sure the
watchdog was awake.

**8.** Now say nothing at all after the agent finishes.

*Expected:* after the configured wait it asks whether you are still there, once,
and after the second wait it says goodbye and hangs up. The timing is measured
from when its own audio finished playing, not from when it was sent.

---

## D. A call that ends itself

**9.** Start a call, let the greeting finish, and then make noise that is not
speech — tap the desk, leave a fan running — without saying any words.

*Expected:* the agent asks you to repeat yourself **twice**, and then ends the
call with the "I cannot hear anything" farewell. It does not ask a third,
fourth or twentieth time. Call History shows the call ended for
`nothing_heard`.

**10.** Place a call and swear at the agent once, in either language.

*Expected:* it warns you — politely, and still ending with a question — and
stays on the line. The line you said is in the transcript.

**11.** Swear a second time on the same call.

*Expected:* it says it is ending the call because of the language used, speaks
that farewell in full, and hangs up. Call History shows
`abusive_language`.

**12.** Place a call and ask for a poem, then a joke, then a riddle.

*Expected:* the first is declined with a sentence saying what it can help with.
By the second or third the screening badge turns to **Wrong number** and the
call is ended politely. This one is the model's judgement rather than a rule, so
it is the one step here that can vary between calls.

---

## E. Bangla

**13.** Start a call, answer the language question with "Bangla", and hold a
short exchange in Bangla.

*Expected:* the transcript is in Bengali script and the reply is spoken by a
Bangla voice. With no Google key the voice will be an English one reading
Bengali letters — that is this machine having no Bangla voice, and Settings says
so at the top of the page.

**14.** On that same call, switch to English: say "can we continue in English"
or simply carry on in English for a sentence.

*Expected:* the transcript switches to Latin script within one exchange, a
violet note in the transcript says the call switched to English, and everything
after it is recognised as English. This no longer depends on the model choosing
to act: the script your words came back in is what moves the call.

**15.** Switch back to Bangla the same way.

*Expected:* it follows you back. The **Language** fact on the left tracks it
both ways.

---

## F. Google speech, if you have a key

**16.** Put a service-account JSON at the path in **Settings → Google
credentials file**, then reload Settings.

*Expected:* within a minute — no restart of anything — the red line is gone, the
voice menus list Google's `bn-IN-*` and `en-US-*` voices, and the Dashboard's
speech capability reads **Ready** rather than **Free fallback**.

**17.** Choose "Whichever the provider picks" for the Bangla voice and place a
Bangla call.

*Expected:* a Google Bangla voice speaks, chosen by Google. The option names
which voice it will be while you are choosing it.

---

## What is not covered here

A real Twilio call, and the OneCore path on a machine that actually has a Bangla
voice installed in Windows. The OneCore renderer was verified on this machine
against an English voice SAPI does not expose — it produced 4.4 seconds of
16 kHz audio from a voice `pyttsx3` refuses outright — but Windows ships no
Bangla voice to try it with, and Microsoft does not offer one. For Bangla,
Google Cloud is the answer, and step 17 is the one that proves it.
