# Phase 6 log — handoff, logging, summaries, PII, metrics

**Closed:** 2026-08-05
**Status:** built and **run against a live stack**. The backend was started on the
embedded PostgreSQL (`-Dspring-boot.run.profiles=dev`), two calls were driven
through the turn socket, and every surface this phase adds was read back from a
real database. See "Verified" for exactly what was exercised and "What is not
verified" for what a model API key and an SMTP server would still add.

---

## What ships

A call is no longer over when the caller hangs up. Every finished call is read
back, written up, and filed. A call the agent could not settle is emailed to the
colleague who has to pick it up, with the summary, the reason it was handed over
and the transcript in the body — or, on a fresh install with no SMTP
credentials, written to the log in full so the escalation is visible rather than
lost.

The Call History page is the record: every call, who it was with, how it was
screened, what the model made of it, what it left for somebody to do, and the
whole transcript with the reply time on each turn. One call downloads as a text
file a colleague can read with nothing installed.

And the caller's personal details stop travelling. An ID number, a phone number,
an email address or an amount of money spoken on a call is replaced with a
`[MASKED_…]` marker before the sentence reaches the model, the summary or the
email. The transcript keeps what was really said, because the operator watching
the call is supervising it.

The Dashboard finished: the median reply time and the slowest one in ten, and
the four screening outcomes as a distribution.

---

## Files added

**Java** — `utils/PiiMasker.java`, `services/MailService.java`,
`services/PostCallService.java`, `services/CallHistoryService.java`,
`api/CallHistoryController.java`, and `utils/LangPages.java` (see the decisions
below).

**Panel** — `js/pages/history.js`. `parts.css` gained the contract's `progress`
part, used as a one-row chart on the Dashboard.

**Tests** — `PiiMaskerTest` (8) and `PostCallServiceTest` (4), plus two more in
`ToolExecutorTest` for the new tool. **55 Java tests**, 17 Python.

## Files changed

`ToolRegistry` and `ToolExecutor` gained `escalate_to_human` — seven tools now.
`ConversationBrain` masks the caller's line on its way into the model's history
and hands the finished call to `PostCallService`. `CallLogService.end` returns
whether this call is what closed the record, so a call ended twice is written up
once. `CallDtos` grew the shapes the history page reads. `MetricsService` gained
percentiles and the mode distribution, and now takes its recent-calls list from
`CallHistoryService` instead of building a second one. `Prompts` gained the
summary request and the escalation tool's standing orders. `Lang` gained 62
strings in both languages. `app.js` routes `#/history` and `#/history/<id>`;
`dashboard.js` shows the two reply times, the distribution and a way into each
call.

`.gitignore` stopped ignoring `docs/ors/logs/` — see the decisions below.

## Files deleted

None. `soonPage` and the `soon.history` string went with the page they were
standing in for.

---

## Decisions worth knowing

**Masking is about what leaves the building, not about what is stored.** The
transcript in the database keeps the ID number the caller read out; the model's
history, the written summary and the escalation email get `[MASKED_NID]`. Those
are two different audiences. An operator watching a live call is supervising it
and has to see what was said; a vendor's server does not need it, and neither
does an inbox. BUILD_SPEC's wording — "applied to caller text before LLM +
before summary" — is exactly this, and it is worth being explicit that a
transcript is deliberately not masked.

**The masker leaves a number it cannot account for.** A run of digits is a phone
number if it is a Bangladeshi mobile in any of the three ways people write one, a
national ID if it is 10, 13 or 17 digits, and otherwise it is left alone. An
order number, a year and a date survive, because a model that can see no numbers
cannot answer the question it was asked. `2026-08-04` is in the tests for that
reason.

**Amounts are masked, and that is a real cost.** BUILD_SPEC asks for it and this
follows it: "I paid 2500 taka" reaches the model as "I paid [MASKED_AMOUNT]".
That protects a balance read out over the phone, and it does blunt an ordinary
service conversation about a price. If you would rather have the numbers, the
change is one line — drop `AMOUNT_PATTERN` from `PiiMasker.mask`. Flagging it
because it is the kind of trade-off that should be chosen, not inherited.

**Escalation is decided from the database, not from a live session.** The tool
moves the call to COMPLEX_REQUEST and the mode machine writes that transition
down with its reason. `PostCallService` then reads the call back the same way
the history page does. Nothing is carried in memory between the hangup and the
email, so a call that ends while the process is restarting is still a call whose
record says a person was promised.

**`mode_path` is filled in from the transitions, not asked of the model.** The
structured summary has the shape BUILD_SPEC names — caller, intent, outcome,
mode path — but the path a call took through the four modes is a fact this app
already holds. Asking the model to infer it from the words would be inviting a
guess about something already known.

**The write-up runs on its own thread, and a failure is not a lost call.** The
caller has gone; nothing is waiting. If the model is unreachable, the key is a
placeholder, or the JSON comes back unreadable, the summary says so in both
languages and the transcript stands as the record. Every call gets a summary
row — that is what makes "written up" a column you can trust on the list.

**The summary is written in the language the call was held in.** A Bangla call
gets a Bangla summary, a Bangla escalation email and a Bangla download. Mixing
an English summary into a Bangla email would be worse than either.

**Reading a call back is not the same job as writing it down.**
`CallLogService` writes a call while it happens and broadcasts every line to the
panel. `CallHistoryService` only reads, has no live feed, and answers questions
nobody asks until the call is over. BUILD_SPEC lists "export txt" under
`CallLogService`; splitting it kept both files near the 300-line target instead
of pushing one to four hundred lines with two unrelated halves in it.

**The history shapes went into `CallDtos` rather than a new dto file.** The
`api/dto/` cap was reached in Phase 2 and the container pattern is established.
A call's list row, its transcript lines and its summary are the same subject as
the rest of that file.

**One shape for "a call in a table".** The Dashboard's recent-calls list and the
history list show the same six facts, so `MetricsDtos.RecentCall` was deleted
and both use `CallDtos.CallListItem`. One shape is one thing to keep in step.

**Turns are counted from the caller's lines.** The old count included the
agent's, which roughly doubled it and made a two-minute call look long.

**A second file for the strings, and this one needs your agreement.** CLAUDE.md
says user-facing strings live in `utils/Lang.java` only; the same file says no
file may pass 500 lines. At 301 bilingual entries those two rules cannot both
hold, and this phase is where they collided — `Lang.java` reached 572 lines. The
per-page half of the catalogue is now `utils/LangPages.java`, package-private and
reached only through `Lang.addAll`. Both languages are still required on every
entry, and a wording is still changed in exactly one place. If you would rather
keep one file and raise the cap for it, that is a two-minute revert.

**`.gitignore` had been eating every phase log since Phase 1.** Line 24's
`logs/` is meant for runtime logs and was also matching `docs/ors/logs/`, so
none of the eleven earlier log and test-script files had ever been committed —
a clean clone had the build record missing. BUILD_SPEC's definition of done asks
for exactly those files, so one negation line was added. Found while checking
what this phase would actually commit.

**The dashboard says "Off", not "Needs a key".** Running it live is what made
this obvious: the voice server was off because nobody had started it, and the
panel told the operator to go and find a key. Each capability now carries a line
saying what would actually fix it.

**Only one number on the screen wears the accent.** The contract allows exactly
one, and the median reply time keeps it. The slowest-one-in-ten sits beside it in
plain type. The distribution bars use the data palette instead, which the
contract reserves for charts.

---

## Verified

Against a live stack — backend on the embedded PostgreSQL, Flyway at V3, two
calls driven through `/ws/turn/{callId}` by a script standing in for the voice
server:

- **A call that needs a person.** Screened to COMPLEX_REQUEST mid-call, written
  up on hangup, and the escalation email built and logged (no SMTP configured)
  to the seeded contact, subject "A caller needs a person — Template Business".
- **Masking, end to end.** The caller said "my NID is 1990123456789 and my
  number is 01712345678" and "I paid 2500 taka". The stored transcript has all
  three verbatim; the email body has `[MASKED_NID]`, `[MASKED_PHONE]` and
  `[MASKED_AMOUNT]`; the brain logged that each turn had been masked.
- **An ordinary call.** Written up, `mode_path` "NEW_CUSTOMER", **no** email.
- **`GET /api/calls`, `/api/calls/{id}`, `/api/calls/{id}/export`** — the export
  came back as `text/plain;charset=UTF-8` with
  `attachment; filename="call-<id>.txt"`, Bengali script intact.
- **The panel**, in a browser, with no console errors: the history list, one
  call in full (facts, summary, structured fields, screening steps, transcript),
  and the Dashboard with the distribution and both reply times.
- **The catalogue**: 301 entries, every one with a Bangla side in Bengali script
  except `settings.badge_placeholder`, which is the literal word PLACEHOLDER.
  All 176 keys the panel asks for exist. Checked again after the file split, over
  the API, in the browser.
- `mvn -o test` — **55 Java tests pass**. The masker is tested against national
  ID numbers in three lengths and both scripts, phone numbers in four forms,
  email addresses, amounts with the currency on either side, and four things it
  must leave alone. The summary parser is tested against plain JSON, JSON in a
  code fence with a preface, JSON missing its parts, and four replies it must
  refuse.
- No file passes 500 lines. The largest are `ConversationBrain` 446, `parts.css`
  407, `LangPages` 403, `live_call.js` 402.

## What is not verified

- **A real summary.** No model API key in this environment, so every summary
  written so far is the fallback line. What is untested is the model's JSON
  coming back in the asked-for shape — the parser is tested, the prompt is not.
- **A real email.** SMTP is all placeholders, so `MailService.deliver` has never
  opened a connection. What ran is the path that logs it instead.
- **A model choosing `escalate_to_human` on its own.** The live run used the
  operator's override to reach COMPLEX_REQUEST; the tool itself is unit-tested.
- **The voice path.** No microphone and no voice server in this run; the calls
  were driven over the turn socket directly. Nothing in this phase touches audio,
  but the reply-time tiles stayed empty because no turn had a `tTtsFirst`.
- **Bangla output of the new surfaces.** The strings exist and are served
  correctly; nobody has read a Bangla summary, email or download.

`phase_06_test.md` walks all of it. Steps 6, 9 and 12 are the three that decide
the phase.

---

## Commit log

- Mask a caller's ID, phone, email and amounts before they reach a model, a summary or an email, and leave the transcript alone
- Write every finished call up, and email the colleague a call was promised to — or log it in full when there is no SMTP
- Give the agent a seventh tool: hand this call to a person, with the reason a colleague will read
- Read calls back over /api/calls and build the history page: the record, the write-up and a download
- Finish the dashboard with reply-time percentiles and how calls end up, and split the string catalogue at the 500-line cap
- Stop .gitignore swallowing docs/ors/logs, which had kept every phase record out of the repository
