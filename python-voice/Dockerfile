FROM python:3.11-slim

# espeak-ng is what pyttsx3 drives on Linux. Without it the offline fallback
# voice has nothing to speak with, and a demo with no Google key goes silent.
# libsndfile is needed by the audio helpers; flac by SpeechRecognition.
RUN apt-get update && apt-get install -y --no-install-recommends \
        espeak-ng libespeak-ng1 libsndfile1 flac \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# Nothing here needs root, and a process that carries call audio is exactly the
# one you want unable to write to its own image. The uid is fixed so a mounted
# volume's ownership is predictable rather than whatever the daemon assigned.
#
# The group is created by hand, with a name of its own. Left to itself useradd
# makes a group named after the user — and espeak-ng's packages already bring a
# group called "voice", so that failed and the image was never built at all.
RUN groupadd --system --gid 10001 voiceserver \
    && useradd --system --uid 10001 --gid 10001 --no-create-home voiceserver \
    && chown -R 10001:10001 /app
USER 10001

EXPOSE 8090
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8090"]
