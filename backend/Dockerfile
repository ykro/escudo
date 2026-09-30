# Escudo API (Cloud Run). Built by infra/10-deploy-api.sh from a staged context that
# contains this backend/ plus the shared contracts/ (events are validated against them).
FROM python:3.13-slim

COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /usr/local/bin/uv
WORKDIR /app
ENV UV_COMPILE_BYTECODE=1 UV_LINK_MODE=copy PYTHONUNBUFFERED=1

COPY pyproject.toml uv.lock .python-version ./
RUN uv sync --frozen --no-dev --no-install-project

COPY escudo ./escudo
COPY contracts ./contracts
RUN uv sync --frozen --no-dev

ENV PATH="/app/.venv/bin:$PATH" ESCUDO_CONTRACTS_DIR=/app/contracts PORT=8080
# --proxy-headers: Cloud Run terminates TLS; keep https in generated URLs.
CMD ["sh", "-c", "exec uvicorn escudo.main:create_app --factory --host 0.0.0.0 --port ${PORT} --proxy-headers --forwarded-allow-ips='*'"]
