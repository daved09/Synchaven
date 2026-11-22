# syntax=docker/dockerfile:1

# ---------- 1. Weboberfläche bauen ----------
FROM node:24-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 2. Backend bauen ----------
FROM rust:1-slim-bookworm AS api
WORKDIR /app
# Abhängigkeiten separat bauen, damit sie im Layer-Cache bleiben
COPY backend/Cargo.toml backend/Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs \
    && cargo build --release --locked \
    && rm -rf src target/release/deps/kalender* target/release/kalender* target/release/.fingerprint/kalender-*
COPY backend/ ./
# touch: COPY übernimmt alte Zeitstempel, Cargo soll das echte Programm neu bauen
RUN touch src/main.rs && cargo build --release --locked

# ---------- 3. Laufzeit-Image ----------
FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --home-dir /data kalender \
    && mkdir -p /data /app/static && chown kalender:kalender /data
COPY --from=api /app/target/release/kalender /usr/local/bin/kalender
COPY --from=web /web/dist /app/static

ENV BIND_ADDR=0.0.0.0:8080 \
    DATA_DIR=/data \
    STATIC_DIR=/app/static \
    RUST_LOG=kalender=info,tower_http=warn

USER kalender
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 CMD ["kalender", "healthcheck"]
ENTRYPOINT ["kalender"]
