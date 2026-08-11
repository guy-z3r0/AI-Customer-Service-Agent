# Business setup files, and the Bangla voice list — test script

Run these in order from the repository root. Each step says what you should see.

**Before you start:** the stack is running and you can open the panel. Steps 1–7
need nothing else. Step 8 needs the Google credentials file in place, because it
is about what Google's catalogue contains.

**Steps 3 and 5 decide this pass**: a file that goes back in without being
edited, and a replace that does not quietly double everything.

---

## A. It builds

**1.** Both test suites.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, `Tests run: 184, Failures: 0, Errors: 0`. Twelve of
those are `BusinessTransferTest`, which is new.

```bash
cd python-voice && python -m pytest
```

*Expected:* `51 passed` — unchanged. Nothing on the Python side was touched.

---

## B. Out and back

**2.** Go to **Businesses** and press **Download setup** on Demo Courier.

*Expected:* a file called `business-demo-two.json` lands in your downloads. Open
it in any text editor: the name and address at the top, then the persona with
both greetings, then fourteen knowledge entries with their `kind` and
`sortOrder`, then the handover contact. The opening hours are nested days, not a
line of quoted JSON. **There are no customers in it and no call history** — that
is deliberate, and section D of this script is where it matters.

**3.** Press **Import a setup file**, choose that same file with nothing edited,
leave the choice on **Add it as a new business**, and save.

*Expected:* a toast saying *Demo Courier imported — 14 knowledge entries*, and a
second Demo Courier in the table with its own handle. Open **What it says** on
it: all four knowledge tabs, the persona and the hours match the original,
including the Bangla greeting. Nothing has become the active business.

*If this step fails with a complaint about an email address*, the handover
contact is still on its seeded `PLACEHOLDER_` value and something has un-fixed
the rule that lets a placeholder through — that is the bug this pass found.

**4.** Open the downloaded file in a text editor, change one service's text, save
it, and import it again as a new business.

*Expected:* a third business whose Services tab shows your edit. A setup file is
meant to be editable by hand; this is the step that says so.

---

## C. Replacing

**5.** Import the same file again, this time choosing **Replace an existing
business** and picking the copy you made in step 3. Do it **twice**.

*Expected:* both times, a toast saying 14 knowledge entries. Open the editor: it
still has fourteen, not twenty-eight. A replace empties before it writes, and
running it twice has to leave the same result as running it once.

**6.** Look at the replaced business's row.

*Expected:* its handle is unchanged — that belongs to this installation, not to
the file — and its customer count is whatever it was. Its name is now the file's.

---

## D. Refusing

**7.** Try to import something that is not one of these files: any other `.json`
you have, and then a file whose first line you have edited to say
`"format": "something-else"`.

*Expected:* both are refused, before anything is created, with
*"That file is not a business exported from this app."* Go back to
**Businesses**: nothing new is there. A file that is not JSON at all is refused
in the panel before the server is asked.

---

## E. The Bangla voice list

**8.** Open **Settings** with the Google credentials file in place, and look at
**Bangla voice**.

*Expected:* the menu lists Google's Bengali voices, and every one of them is
`bn-IN`. Under it, a line saying that Google publishes no Bangladeshi Bengali
(`bn-BD`) voice and that recognition uses `bn-BD` anyway.

That line is the whole of this fix. Asked for its catalogue, Google returns 38
Bengali voices — four Standard, four WaveNet, thirty Chirp 3 HD — and every one
is Indian. There is no `bn-BD` voice to choose, from Google or from Windows, so
what changed is that the panel says so instead of leaving you to look for one.
