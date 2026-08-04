# AI Customer Service Agent

A bilingual voice agent that answers a small business's phone. It greets the
caller, works out what kind of call it is, answers from that business's own
knowledge base, recognises customers it has met before, writes new ones down,
hands the difficult ones to a person by email, and files a written summary of
every call.

English and Bangla, chosen by the caller at the greeting and switchable
mid-call. Placed from a browser tab by default, or over a real telephone line
when Twilio credentials are configured.

Everything a business needs — its details, its knowledge, its persona, its
customers, its opening hours, its escalation contacts, every API key — is
editable in the browser while the app is running. There is no configuration
file to redeploy and no code to change to onboard a second business.

---

## Running it

You need **Docker Desktop**, and nothing else:

```bash
cp .env.example .env
docker compose up --build
```

Open **http://localhost:8080**. That is the whole install. Every credential
ships as a fake `PLACEHOLDER_` value and every feature that needs one switches
itself off politely until you fill it in, so the panel runs, the database is
seeded, and you can look around before you have an API key.

No Docker? `./run-local.ps1` on Windows starts a real PostgreSQL as a child
process of the backend, plus the voice server, in two console windows. It needs
Java 21 and Python 3.10+.

To make it actually talk you need one language-model key.
**[docs/SETUP.md](docs/SETUP.md)** is the first-run walkthrough;
**[docs/KEYS_FOR_TESTING.md](docs/KEYS_FOR_TESTING.md)** covers the optional
speech and email credentials, with links.

---

## What it does

| | |
|---|---|
| **Answers from the business, not from the internet** | The prompt is assembled per call from that business's about text, services, prices, policies, FAQs, contact details and opening hours. The standing orders say to use nothing else and to never invent a price. |
| **Screens the call four ways** | New caller, known customer, wrong number, or something a person has to take over. The agent reclassifies mid-call through a tool call, and a legality table refuses moves that make no sense — from the model *and* from the operator. |
| **Speaks both languages** | The greeting asks which, in both, each half in its own voice. A caller can switch at any point. Banglish gets a re-prompt rather than silence. |
| **Knows who is calling** | A customer code read out, or the number a call came in on, identifies a caller mid-conversation. A stranger who gives a name and number is written to the customer records during the call. |
| **Hands over properly** | A refund it cannot approve or a caller who asks for a person moves the call to handover, and the colleague gets an email with a summary, the reason, and the transcript when the call ends. |
| **Keeps personal details out of the post** | ID numbers, phone numbers, email addresses and money amounts are masked before anything reaches the model, the summary or the email. The transcript keeps what was really said, because the operator is supervising the call. |
| **Answers in under two seconds** | Streaming throughout: speech recognition streams, the model streams, and each sentence goes to the voice the moment it is finished rather than when the whole reply is. Every turn's timings are stored, so a slow reply can be blamed on the right stage. |

---

## How it fits together

```
browser tab ──mic audio──▶ python-voice ──words──▶ java-backend ──▶ Gemini / OpenAI
  or a phone ──Twilio──▶      :8090        WS       :8080  │           (swappable)
                                ▲                          ▼
                            speech ◀──sentences──   PostgreSQL
```

Three processes. The **panel and the brain** are one Spring Boot app: REST for
every screen, a websocket per call to the voice server, and another to the
panel for the live transcript. The **voice server** is FastAPI and holds no
business knowledge at all — it carries audio, recognises speech, and speaks
whatever the brain sends it. **PostgreSQL** holds everything, with customer
phone numbers and email addresses encrypted in the columns.

Audio never touches Java, and text never touches the audio socket. That is
where the two-second budget is won.

| Folder | What is in it |
|---|---|
| `java-backend/src/main/java/com/ulab/agent/` | `api/` controllers and websockets · `brain/` the conversation, screening and tools · `brain/llm/` Gemini and OpenAI behind one interface · `services/` the domain · `domain/` + `repo/` the data · `utils/` strings, prompts, PII masking |
| `java-backend/src/main/resources/static/` | The control panel: vanilla JS, no build step |
| `java-backend/src/main/resources/db/migration/` | Flyway migrations, including the seeded example businesses |
| `python-voice/` | `server.py` · `session.py` turn-taking · `transports/` browser and Twilio · `pipeline/` VAD, speech recognition, speech synthesis, and their free fallbacks |
| `docs/ors/` | The build record: proposal, spec, phases, architecture, and a log + test script per phase |

---

## Things worth knowing

**Nothing is hard-wired to one vendor.** Gemini and OpenAI sit behind one
interface, and which one answers is read fresh at the start of every call —
from the business's own settings first, the global Settings page second.
Changing it in the panel changes the next call.

**It works with no accounts at all.** Without Google Cloud credentials the
voice server falls back to a free offline recogniser and voice. Without a model
key the agent still answers the phone and says, out loud and in both languages,
that it cannot answer questions. Without SMTP an escalation is written to the
log in full rather than lost. A demo never fails because something was not
configured; it degrades in a way you can see.

**The panel holds no words.** Every string a person reads comes from
`utils/Lang.java` over `GET /api/lang?lang=en|bn` — 301 entries, both
languages. Changing a wording is a change to one catalogue and nothing else.

**Two example businesses ship with it.** Template Business is a generic shop
whose knowledge base is deliberately obvious filler. Demo Courier is a parcel
service with different hours, prices, policies and persona. Switching between
them in the top bar changes what the agent says, who it recognises and how it
introduces itself, with no code involved — that is the point of having a
second one.

---

## Documentation

| | |
|---|---|
| [docs/SETUP.md](docs/SETUP.md) | First run, the panel tour, entering keys, editing a business |
| [docs/KEYS_FOR_TESTING.md](docs/KEYS_FOR_TESTING.md) | Google speech and SMTP credentials, step by step, with links |
| [docs/ors/architecture.md](docs/ors/architecture.md) | What each phase built and the rules it set |
| [docs/ors/logs/](docs/ors/logs/) | A log and a runnable test script for every phase |
| [STYLE-CONTRACT.md](STYLE-CONTRACT.md) | The design contract the panel is built from |

Built in seven phases; `docs/ors/PROJECT_STATE.md` says where it stands.
