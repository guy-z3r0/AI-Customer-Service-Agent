# Live call fixes — test script

Four faults from one four-minute call to Bengal Power System. Run these in order
from the repository root. Each step says what you should see.

**Before you start:** a real model key in Settings, and a microphone. Steps 1–2
need neither.

**Steps 3, 5 and 7 decide this pass**: a call that ends when the agent says it
is ending, an agent that asks what the matter is before fetching a colleague,
and a caller who is not treated as somebody else with the same name.

---

## A. It builds

**1.** Both test suites.

```bash
cd java-backend && mvn clean package
```

*Expected:* `BUILD SUCCESS`, `Tests run: 184, Failures: 0, Errors: 0`. Sixteen
came with this pass, including `FarewellSenseTest` — every line in it is from
the call that prompted this.

```bash
cd python-voice && python -m pytest
```

*Expected:* `51 passed` — unchanged. Nothing on the Python side was touched.

**2.** Start the stack.

```bash
docker compose up --build
```

*Expected:* all three containers healthy, and no new migration.

---

## B. The call ends when it says it does

**3.** Place a browser call, say "thank you, that is all I needed", and let the
agent say goodbye. **Then leave the page alone for thirty seconds and look at
it again.**

*Expected:* **State** reads *Call ended* and **Call length** is frozen at the
length the call actually was. Before this pass it went on counting for as long
as the page was open — a four-minute call reading 4:48 and climbing.

**4.** While still on that finished call, look at your browser's tab.

*Expected:* the recording indicator is **gone**. This is the half of the same
bug that was never on screen: the microphone stayed open after the agent hung
up, because only the **End call** button released it.

**5.** Place another call and let the agent reach a natural goodbye — "is there
anything else?", "no, thank you".

*Expected:* it says its farewell **and puts the phone down**. You should not
have to ask whether it is still there. In the backend log the call ends with
`agent_said_goodbye` when the model wrote the goodbye but did not ask to hang
up; a model that asks properly still ends with its own reason, and both are
correct.

---

## C. Asking for a person

**6.** Place a call and say, as your first request: *"I want to talk with your
manager."*

*Expected:* it asks **what the matter is about** before agreeing to anything. It
does not say a colleague will follow up, and the **Screening** badge stays on
*New caller* rather than turning amber.

**7.** Tell it: *"I was overcharged on my last invoice and I want it refunded."*

*Expected:* now it hands the call over — the badge turns to **Needs a person**,
it says a colleague will follow up, and it stays on the line to take details.
Open the call in **Call history** afterwards: the escalation line says what the
matter was, not merely that a manager was asked for.

**8.** Place a third call and ask for a manager, then, when asked what it is
about, say something the knowledge base covers — *"I just want to know your
delivery charge."*

*Expected:* it answers the question. A caller who says they want a person and
turns out to want a price gets the price. If you insist after being asked once,
you still get a person — that is the other half of the rule.

---

## D. Who the caller is

**9.** Place a call as a stranger and give a name and a number when asked.

*Expected:* the transcript note reads **Written down as a new customer: <name>**,
not *Recognised*. Nothing was recognised — the agent wrote down what you told
it, and there may well be another customer with that name.

**10.** Hang up, then place a new call and give **only the phone number** of a
customer who is on the books.

*Expected:* it is not enough. The agent asks for your name as well, and only
confirms you once both agree. A number alone used to be proof.

**11.** Give that number with **the wrong name**.

*Expected:* it does not recognise you, and it does **not** say the number was
right — a near miss that named the customer would tell a guesser exactly what
they had found.

**12.** Give the number with the right name, or just your first name if the
record has two.

*Expected:* **Recognised: <name>**, the screening moves to *Known customer*, and
part of a name matches the whole of it — nobody reads their own record out.

**13.** Give a number that is already on the books, but claim to be new.

*Expected:* no second record is written for that number. One person with two
histories is one wrong callback.

---

## What is not covered here

Whether the model *chooses* to ask what a manager is wanted for is its judgement
and can vary between calls; the refusal in step 6 is the part that does not
vary, because the tool will not hand the call over without it. Step 5 has the
same shape: `FarewellSense` catches the goodbye the model wrote, but which words
it writes are still its own.
