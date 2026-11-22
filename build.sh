#!/usr/bin/env bash
# Baut das Docker-Image der Kalender-Anwendung als
#   ghcr.io/daved09/kalender:<version>
# Die Version stammt aus backend/Cargo.toml. Zusätzlich wird sie als KALENDER_VERSION
# in .env eingetragen, damit `docker compose up -d` genau dieses Image startet.

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

REPOSITORY="ghcr.io/daved09/kalender"

if ! command -v docker >/dev/null 2>&1; then
    echo "Fehler: docker ist nicht installiert oder nicht im PATH." >&2
    exit 1
fi

VERSION="$(sed -n 's/^version *= *"\(.*\)"/\1/p' backend/Cargo.toml | head -n1)"
if [[ -z "$VERSION" ]]; then
    echo "Fehler: Version konnte nicht aus backend/Cargo.toml gelesen werden." >&2
    exit 1
fi

IMAGE="${REPOSITORY}:${VERSION}"
REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "==> Baue ${IMAGE}"
docker build \
    -t "$IMAGE" \
    --label "org.opencontainers.image.title=Kalender" \
    --label "org.opencontainers.image.version=${VERSION}" \
    --label "org.opencontainers.image.revision=${REVISION}" \
    --label "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    .

# Version für docker-compose.yml hinterlegen (andere Einträge in .env bleiben erhalten)
if [[ -f .env ]] && grep -q '^KALENDER_VERSION=' .env; then
    sed -i "s/^KALENDER_VERSION=.*/KALENDER_VERSION=${VERSION}/" .env
else
    echo "KALENDER_VERSION=${VERSION}" >> .env
fi

echo
echo "==> Fertig: ${IMAGE}"
echo "    Starten:   docker compose up -d"
echo "    Hochladen: docker push ${IMAGE}"
