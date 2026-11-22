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
    && rm -rf src target/release/deps/synchaven* target/release/synchaven* target/release/.fingerprint/synchaven-*
COPY backend/ ./
# build.rs liest die Version aus version.properties
COPY version.properties ./
# touch: COPY übernimmt alte Zeitstempel, Cargo soll das echte Programm neu bauen
RUN touch src/main.rs && cargo build --release --locked

# ---------- 3. Laufzeit-Image ----------
FROM debian:bookworm-slim
RUN useradd --system --uid 10001 --home-dir /data synchaven \
    && mkdir -p /data /app/static && chown synchaven:synchaven /data
COPY --from=api /app/target/release/synchaven /usr/local/bin/synchaven
COPY --from=web /web/dist /app/static
COPY docker/entrypoint.sh /usr/local/bin/docker-entrypoint.sh
RUN chmod 755 /usr/local/bin/docker-entrypoint.sh

ENV BIND_ADDR=0.0.0.0:8080 \
    DATA_DIR=/data \
    STATIC_DIR=/app/static \
    RUST_LOG=synchaven=info,tower_http=warn \
    PUID=10001 \
    PGID=10001

# Kein USER: das Entrypoint-Skript passt als root die Rechte von /data an (PUID/PGID)
# und startet das Programm danach ohne Root-Rechte.
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 CMD ["synchaven", "healthcheck"]
ENTRYPOINT ["docker-entrypoint.sh"]
