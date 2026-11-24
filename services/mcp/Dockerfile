FROM python:3.12-slim
WORKDIR /app
COPY pyproject.toml ./
COPY smarteval_mcp ./smarteval_mcp
RUN pip install --no-cache-dir . && useradd --uid 1000 --no-create-home smarteval
USER 1000
EXPOSE 8090
CMD ["smarteval-mcp-http"]
