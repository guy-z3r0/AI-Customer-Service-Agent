#!/usr/bin/env python3
"""
Standalone Google Cloud Speech/TTS credential tester — AI Customer Service Agent.

Runs completely on its own: no Docker, no Java backend, no voice server. Just
your Google credentials file and these two packages. It walks through every
step the real app depends on, one at a time, and tells you exactly which one
breaks instead of just "it doesn't work."

Setup:
    pip install google-cloud-speech==2.28.0 google-cloud-texttospeech==2.21.1
    (same versions python-voice/requirements.txt already pins, so a pass here
    means a pass in the real app too)

Usage:
    python check_google_credentials.py
    python check_google_credentials.py --credentials "F:\\path\\to\\your-key.json"

With no --credentials flag, it looks for secrets/gcp-credentials.json next to
this script — the same default the app itself uses, so run this from wherever
you'd put this file in the repo (repo root is the natural place).

On success it writes test_en.wav and test_bn.wav next to itself — play them.
Actually hearing the Bangla one is the real test; everything before that is
just ruling out reasons it might not get that far.
"""

import argparse
import json
import os
import sys
import wave
from pathlib import Path

DEFAULT_CREDENTIALS = Path(__file__).resolve().parent / "secrets" / "gcp-credentials.json"
OUT_DIR = Path(__file__).resolve().parent

TEST_TEXT_EN = "Hello, this is a test of the customer service agent's voice."
TEST_TEXT_BN = "স্বাগতম, এটি একটি পরীক্ষা।"  # "Welcome, this is a test."

SAMPLE_RATE = 16000  # matches what the app asks Google for

PASS = "  PASS  "
FAIL = "  FAIL  "

results = []  # (label, bool_ok, detail_str)


def record(label, ok, detail=""):
    results.append((label, ok, detail))
    print(f"[{PASS if ok else FAIL}] {label}")
    if detail:
        for line in detail.splitlines():
            print(f"           {line}")
    return ok


def section(title):
    print()
    print(f"--- {title} " + "-" * max(0, 60 - len(title)))


# --------------------------------------------------------------- credentials --

def find_credentials(path_arg):
    section("Credentials file")
    path = Path(path_arg) if path_arg else DEFAULT_CREDENTIALS
    print(f"Looking for: {path}")

    if not path.exists():
        near = _near_misses(path)
        detail = "No file at that exact path."
        if near:
            detail += ("\nFound file(s) with a similar name in the same folder — "
                        "did you forget to rename the Google download?\n  "
                        + "\n  ".join(near))
        else:
            detail += f"\nThe folder {path.parent} doesn't have anything close to that name either."
        record("File exists at the expected path", False, detail)
        return None

    if path.stat().st_size == 0:
        record("File exists at the expected path", False, "The file is 0 bytes — empty download?")
        return None

    record("File exists at the expected path", True)
    return path


def _near_misses(wanted_path: Path) -> list:
    folder = wanted_path.parent
    if not folder.is_dir():
        return []
    wanted = wanted_path.name
    stem = wanted_path.stem
    return sorted(
        f.name for f in folder.iterdir()
        if f.name != wanted and (f.name.startswith(stem) or f.name.startswith(wanted))
    )


def check_json_shape(path: Path):
    section("Credentials file contents")
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except Exception as e:
        record("File parses as JSON", False, f"{type(e).__name__}: {e}")
        return None

    record("File parses as JSON", True)

    kind = data.get("type", "<missing>")
    project = data.get("project_id", "<missing>")
    email = data.get("client_email", "<missing>")
    ok = kind == "service_account" and "project_id" in data and "client_email" in data
    record("Looks like a service-account key",
           ok,
           f"type: {kind}\nproject_id: {project}\nclient_email: {email}")
    return data if ok else None


# ---------------------------------------------------------------------- TTS --

def check_tts_client(credentials_path: Path):
    section("Text-to-Speech")
    os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = str(credentials_path)
    try:
        from google.cloud import texttospeech
    except ImportError:
        record("google-cloud-texttospeech installed", False,
               "Run: pip install google-cloud-texttospeech==2.21.1")
        return None
    record("google-cloud-texttospeech installed", True)

    try:
        client = texttospeech.TextToSpeechClient()
    except Exception as e:
        record("TextToSpeechClient() builds", False, f"{type(e).__name__}: {e}")
        return None
    record("TextToSpeechClient() builds", True)
    return client


def check_tts_voice_list(client):
    from google.cloud import texttospeech
    try:
        voices = client.list_voices()
    except Exception as e:
        record("client.list_voices() succeeds", False,
               f"{type(e).__name__}: {e}\n"
               "This is a real call to Google, so a failure here usually means: "
               "the Text-to-Speech API isn't enabled on this project, billing "
               "isn't linked, or the service account lacks the Cloud Speech "
               "Client role.")
        return False

    bn = [v.name for v in voices.voices if any(c.startswith("bn-") for c in v.language_codes)]
    en = [v.name for v in voices.voices if any(c.startswith("en-US") for c in v.language_codes)]
    record("client.list_voices() succeeds", True,
           f"{len(voices.voices)} voices total — {len(en)} en-US, {len(bn)} bn-*"
           + (f"\nBangla voices include: {', '.join(bn[:3])}" if bn else
              "\nNo Bangla voice in the list at all — that would explain silence "
              "even with everything else working."))
    return len(bn) > 0


def check_tts_synthesize(client, language_code, text, out_name):
    from google.cloud import texttospeech
    label = f"Synthesize speech ({language_code})"
    try:
        response = client.synthesize_speech(
            input=texttospeech.SynthesisInput(text=text),
            voice=texttospeech.VoiceSelectionParams(language_code=language_code),
            audio_config=texttospeech.AudioConfig(
                audio_encoding=texttospeech.AudioEncoding.LINEAR16,
                sample_rate_hertz=SAMPLE_RATE,
            ),
        )
    except Exception as e:
        record(label, False, f"{type(e).__name__}: {e}")
        return None

    if not response.audio_content:
        record(label, False, "Call succeeded but returned zero bytes of audio.")
        return None

    out_path = OUT_DIR / out_name
    out_path.write_bytes(response.audio_content)
    record(label, True, f"Wrote {out_path} ({len(response.audio_content)} bytes) — play it.")
    return out_path


# ---------------------------------------------------------------------- STT --

def check_stt_client(credentials_path: Path):
    section("Speech-to-Text")
    os.environ["GOOGLE_APPLICATION_CREDENTIALS"] = str(credentials_path)
    try:
        from google.cloud import speech
    except ImportError:
        record("google-cloud-speech installed", False,
               "Run: pip install google-cloud-speech==2.28.0")
        return None
    record("google-cloud-speech installed", True)

    try:
        client = speech.SpeechClient()
    except Exception as e:
        record("SpeechClient() builds", False, f"{type(e).__name__}: {e}")
        return None
    record("SpeechClient() builds", True)
    return client


def check_stt_roundtrip(stt_client, wav_path: Path, language_code: str, expected_text: str):
    from google.cloud import speech
    label = f"Round-trip recognition ({language_code})"
    if wav_path is None:
        record(label, False, "Skipped — no audio to test with, TTS step above failed first.")
        return

    with wave.open(str(wav_path), "rb") as wav:
        pcm = wav.readframes(wav.getnframes())
        rate = wav.getframerate()

    try:
        response = stt_client.recognize(
            config=speech.RecognitionConfig(
                encoding=speech.RecognitionConfig.AudioEncoding.LINEAR16,
                sample_rate_hertz=rate,
                language_code=language_code,
            ),
            audio=speech.RecognitionAudio(content=pcm),
        )
    except Exception as e:
        record(label, False, f"{type(e).__name__}: {e}")
        return

    if not response.results:
        record(label, False,
               "Call succeeded but transcribed nothing. The audio it was fed "
               "was this script's own TTS output, so this points at the STT "
               "side specifically rather than the credentials.")
        return

    heard = response.results[0].alternatives[0].transcript
    record(label, True, f'Expected roughly: "{expected_text}"\nGoogle heard:     "{heard}"')


# --------------------------------------------------------------------- main --

def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                      formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--credentials", help="Path to the service-account JSON key. "
                                               "Defaults to secrets/gcp-credentials.json "
                                               "next to this script.")
    args = parser.parse_args()

    print("=" * 70)
    print("Google Cloud Speech/TTS credential check — AI Customer Service Agent")
    print("=" * 70)

    path = find_credentials(args.credentials)
    if path:
        check_json_shape(path)

    en_wav = bn_wav = None
    tts_client = check_tts_client(path) if path else None
    if tts_client:
        check_tts_voice_list(tts_client)
        en_wav = check_tts_synthesize(tts_client, "en-US", TEST_TEXT_EN, "test_en.wav")
        bn_wav = check_tts_synthesize(tts_client, "bn-IN", TEST_TEXT_BN, "test_bn.wav")

    stt_client = check_stt_client(path) if path else None
    if stt_client:
        check_stt_roundtrip(stt_client, en_wav, "en-US", TEST_TEXT_EN)
        check_stt_roundtrip(stt_client, bn_wav, "bn-BD", TEST_TEXT_BN)

    section("Summary")
    failed = [label for label, ok, _ in results if not ok]
    for label, ok, _ in results:
        print(f"[{PASS if ok else FAIL}] {label}")

    print()
    if not failed:
        print("Everything passed. Play test_en.wav and test_bn.wav next to this "
              "script to hear it for yourself. If the real app is still silent "
              "after this, the problem is in how the app is reading the setting "
              "(Docker vs. local path), not in Google.")
    else:
        print(f"First thing to fix: \"{failed[0]}\" — see the detail above it.")
        print("Everything listed as FAIL after that first one is likely just a "
              "consequence of it, not a separate problem.")

    sys.exit(1 if failed else 0)


if __name__ == "__main__":
    main()
