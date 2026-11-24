# Self-hosting image: the API the Android app talks to, plus the ingest worker.
FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 FASTEMBED_CACHE=/data/fastembed
COPY requirements.txt worker/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt -r worker/requirements.txt uvicorn
COPY pulse/ pulse/
COPY worker/ worker/
COPY api/ api/
EXPOSE 8000
CMD ["uvicorn", "pulse.app:app", "--host", "0.0.0.0", "--port", "8000"]
