# syntax=docker/dockerfile:1

# ---------- 1. Build the web UI ----------
FROM node:24-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# ---------- 2. Build the backend ----------
FROM rust:1-slim-bookworm AS api
WORKDIR /app
# Build dependencies separately so they stay in the layer cache
COPY backend/Cargo.toml backend/Cargo.lock ./
RUN mkdir src && echo 'fn main() {}' > src/main.rs \
    && cargo build --release --locked \
    && rm -rf src target/release/deps/synchaven* target/release/synchaven* target/release/.fingerprint/synchaven-*
COPY backend/ ./
# build.rs reads the version from version.properties
COPY version.properties ./
# touch: COPY keeps old timestamps, Cargo should rebuild the real program
RUN touch src/main.rs && cargo build --release --locked

# ---------- 3. Runtime image ----------
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

# No USER: the entrypoint script adjusts the ownership of /data as root (PUID/PGID)
# and then starts the program without root rights.
VOLUME ["/data"]
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=5s --start-period=5s --retries=3 CMD ["synchaven", "healthcheck"]
ENTRYPOINT ["docker-entrypoint.sh"]
