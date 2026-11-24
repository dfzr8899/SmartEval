FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
      gcc g++ default-jdk-headless nodejs \
    && rm -rf /var/lib/apt/lists/* \
    && pip install --no-cache-dir fastapi==0.115.12 uvicorn==0.34.2 \
    && useradd --uid 1000 --create-home --shell /usr/sbin/nologin runner \
    && mkdir -p /app /work && chown 1000:1000 /app /work

WORKDIR /app
COPY app.py /app/app.py
USER 1000:1000
EXPOSE 2358
ENTRYPOINT ["python", "-m", "uvicorn", "app:app", "--host", "0.0.0.0", "--port", "2358"]
