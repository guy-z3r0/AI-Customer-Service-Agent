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

EXPOSE 8090
CMD ["uvicorn", "server:app", "--host", "0.0.0.0", "--port", "8090"]
