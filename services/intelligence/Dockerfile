FROM python:3.12-slim
WORKDIR /app
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-chi-sim libreoffice-writer && rm -rf /var/lib/apt/lists/*
COPY services/intelligence/requirements.txt ./
RUN pip install --no-cache-dir -r requirements.txt && useradd --uid 1000 --no-create-home smarteval
COPY services/intelligence/smarteval_ai ./smarteval_ai
ENV HOME=/tmp XDG_CACHE_HOME=/tmp/.cache
USER 1000:1000
ENTRYPOINT ["python", "-m", "uvicorn", "smarteval_ai.main:app", "--host", "0.0.0.0", "--port", "8000"]
