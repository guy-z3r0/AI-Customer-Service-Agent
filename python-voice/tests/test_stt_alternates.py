"""When Google is asked to listen for a second language, and when it is not.

Asking is what makes Banglish transcribe at all — a caller switching between
Bangla and English mid-sentence — but Google does not accept the request with
every model in every region, and a refusal costs the caller the sentence they
just said. So the first refusal switches it off.

The bug (BUG-005) was that it switched off *permanently*, in a module-level
variable written from a recogniser's worker thread. One transient
InvalidArgument and Banglish stopped being recognised until somebody restarted
the server, with nothing in the panel to say why.

Run with:  cd python-voice && python -m pytest
"""

import os
import sys
import threading

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from pipeline import stt_gcp  # noqa: E402


def test_asking_for_both_languages_is_the_starting_position():
    assert stt_gcp._AlternateLanguages().allowed()


def test_a_refusal_switches_it_off():
    alternates = stt_gcp._AlternateLanguages()
    alternates.refused()
    assert not alternates.allowed(), "the caller should not repeat the same failure twice"


def test_it_comes_back_after_the_cooling_off_period(monkeypatch):
    alternates = stt_gcp._AlternateLanguages()
    alternates.refused()
    assert not alternates.allowed()

    # A region or a model that starts accepting it should be picked up the same
    # afternoon, not at the next restart.
    monkeypatch.setattr(stt_gcp, "ALTERNATES_RETRY_AFTER_S", 0)
    assert alternates.allowed()


def test_it_only_comes_back_once_per_refusal(monkeypatch):
    """Coming back has to clear the refusal, or the cooling-off period is
    measured from a moment that never moves."""
    alternates = stt_gcp._AlternateLanguages()
    alternates.refused()

    monkeypatch.setattr(stt_gcp, "ALTERNATES_RETRY_AFTER_S", 0)
    assert alternates.allowed()

    monkeypatch.setattr(stt_gcp, "ALTERNATES_RETRY_AFTER_S", 600)
    assert alternates.allowed(), "the refusal was spent on the retry above"


def test_recognisers_on_different_threads_do_not_tread_on_each_other():
    """Every caller of this is a recogniser worker thread, so it is shared
    state and is guarded like it."""
    alternates = stt_gcp._AlternateLanguages()
    answers = []

    def worker():
        for _ in range(200):
            answers.append(alternates.allowed())
            alternates.refused()

    threads = [threading.Thread(target=worker) for _ in range(4)]
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()

    assert len(answers) == 800, "no answer was lost to a race"


def test_the_shared_one_exists_and_starts_switched_on():
    """What GoogleSttStream actually reads."""
    assert isinstance(stt_gcp.ALTERNATES, stt_gcp._AlternateLanguages)
