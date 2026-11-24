# Self-hosting image: API + built PWA + ingest worker in one image.
FROM node:22-slim AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci
COPY web/ ./
RUN npm run build

FROM python:3.12-slim
WORKDIR /app
ENV PYTHONUNBUFFERED=1 FASTEMBED_CACHE=/data/fastembed
COPY requirements.txt worker/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt -r worker/requirements.txt uvicorn
COPY pulse/ pulse/
COPY worker/ worker/
COPY api/ api/
COPY --from=web /web/dist web/dist
EXPOSE 8000
CMD ["uvicorn", "pulse.app:app", "--host", "0.0.0.0", "--port", "8000"]
