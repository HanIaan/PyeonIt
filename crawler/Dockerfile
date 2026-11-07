# 감시 루프 서버 이미지: docker compose --profile monitor up -d crawler
FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY src ./src
RUN pip install --no-cache-dir . && playwright install --with-deps chromium
ENV PDF_STORAGE_DIR=/data/pdfs PYTHONUNBUFFERED=1
CMD ["python", "-m", "pyeonit_crawler.monitor"]
