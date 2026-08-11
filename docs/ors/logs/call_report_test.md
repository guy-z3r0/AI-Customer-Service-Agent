# The call report — test script

Run these in order from the repository root. Each step says what you should see.

**Before you start:** the stack is running and you can open the panel. Steps 1–11
need nothing but calls already in the history — a handful is enough, and they do
not have to be recent, because step 4 is about choosing the days they are in.
Step 12 needs a printer dialog, which every browser has whether or not a printer
is attached.

**Steps 4, 6 and 12 decide this pass**: a range that holds what it says it holds,
a silent call that is not reported as a conversation, and a page that prints as a
document rather than as a screenshot of the app.

---

## A. It builds

**1.** Both test suites.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, `Tests run: 208, Failures: 0, Errors: 0` — up from
184. The twenty-four new ones are `CallReportTest` (11), `ReplyTimesTest` (8),
`CallReportRoutingTest` (4) and one more case in `ApiRequiresLoginTest`.

```bash
cd python-voice && python -m pytest
```

*Expected:* `51 passed` — unchanged. Nothing on the Python side was touched.

---

## B. Asking for one

**2.** Open **Call history**.

*Expected:* above the table, a **Generate report** button. It is the only primary
button on the page, which is the contract's rule and also the right emphasis —
it is the one thing on that screen you cannot do anywhere else.

**3.** Press it.

*Expected:* a dialog headed *Report on call history* with three fields: a first
day and a last day, both already filled in with the last thirty days ending
today, and a business menu that opens on **Every business**.

**4.** Set the first day to a day you know has calls on it and the last day to
that same day. Leave the business on **Every business**. Press **Make the
report**.

*Expected:* the report opens, and the address bar reads
`#/report/<that day>/<that day>/all`. Under the heading it says **Every
business** and the range with the same date twice. **Every call in this range**
at the bottom holds exactly the calls from that one day and no others — the last
day is counted in full, so a call at eleven at night is in it.

*This is the step that decides the range is real.* A range that quietly ran to
midnight at the start of the last day would drop most of a day's calls, and with
one day chosen it would come back empty.

**5.** Press **Choose another range**, pick a range of a week or more, and choose
one business from the menu.

*Expected:* the report reloads for that business alone. The heading names it, and
the **Business** column has gone from the table at the bottom — in a report about
one business it would be the same name on every row.

---

## C. What it counts

**6.** Look at **How the calls turned out** against the calls listed underneath.

*Expected:* five rows, always all five, including any with a zero beside them.
Every call that nobody spoke on is counted under **Nobody spoke** — not under
the mode its row would otherwise show, because a call that rang out is still
holding the mode it opened in and counting that as a conversation would put a
conversation that never happened into the report. The badge on that call's own
row in the table below says the same thing.

*This is the step that decides the counting.* Add up the five numbers: they must
equal the **Calls** tile at the top, and **Answered** plus **Nobody spoke** must
equal it too.

**7.** Compare the **Usual reply** tile with the dashboard's own **Usual reply**,
with the report's range set wide enough to cover every call there has ever been.

*Expected:* the same number, to the millisecond. They are worked out by the same
code now (`utils/ReplyTimes`), and this is the check that says so. A tile reading
`No calls yet` means no turn in the range was timed end to end, which is not the
same as a reply of zero.

**8.** Look at **Calls by day**.

*Expected:* one row per day that had a call, oldest first, and no rows for the
quiet days. The date on a call near midnight is the date it was in the business's
own timezone, not the server's — an evening call in London is the next morning in
Dhaka, and this app is written for Dhaka.

**9.** Look at **What the calls left to do**.

*Expected:* every action item from every written-up call in the range, each with
the time of the call it came from. With more than one business in the report, the
business is named too. If no call in the range was written up, one line saying
nothing was left to follow up — not an empty panel.

---

## D. When there is nothing to report

**10.** Choose a range you know has no calls in it — a week before you first ran
this app.

*Expected:* the heading, the range and the date it was worked out, then a single
empty state saying *No calls in this range* with a button to choose another. No
tiles reading zero, no empty tables, no breakdown of nothing.

**11.** Edit the address bar by hand to `#/report/not-a-date/2026-08-11/all`.

*Expected:* a toast saying *One of the values in that web address is not the kind
of value it should be*, and the page says it could not load. **Not** *"Something
went wrong on the server."* — that was the answer before this pass, along with a
stack trace in the log, and a mistyped date is the caller's mistake rather than a
server failure.

---

## E. On paper

**12.** With a report on screen, press **Print or save as PDF** (or Ctrl+P).

*Expected*, in the preview:

- Black text on white. Not the dark panel, and not light grey text on white.
- No side rail, no top bar, no status bar, and none of the three buttons.
- The heading, the business, the range and the line saying when it was worked
  out — everything needed to know what the sheet is without the app.
- The tiles as plain labelled numbers with no boxes around them, and the
  **Usual reply** number in the same ink as the rest: the accent exists to be
  found at a glance among five others, which is not what a printed page is for.
- The bars gone from the breakdowns, with their numbers still there.
- Nothing cut off with an ellipsis. Long text wraps and runs its full length,
  because on a screen a truncated cell can still be hovered and on paper it
  cannot.
- No panel split across two sheets.

*This is the step that decides the pass.* The report exists to be handed to
somebody who does not have this app open; a page that prints as a dark
screenshot with a navigation rail down the side is not that.

**13.** Turn on **Background graphics** in the print dialog and look again.

*Expected:* the outcome badges come back in their colours, and everything else is
unchanged. That is the only thing on the sheet that depends on the setting, and
it reads correctly either way.
