#!/usr/bin/env bash
# Builds the Synchaven Docker image as
#   ghcr.io/daved09/synchaven:<version>
# The version comes from version.properties (key "version"). The backend reads it itself at
# compile time (backend/build.rs); here it serves as the image tag and is stored as
# SYNCHAVEN_VERSION in .env so that `docker compose up -d` starts exactly this image.

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

REPOSITORY="ghcr.io/daved09/synchaven"

if ! command -v docker >/dev/null 2>&1; then
    echo "Fehler: docker ist nicht installiert oder nicht im PATH." >&2
    exit 1
fi

# ---------- Read the version from version.properties ----------
if [[ ! -f version.properties ]]; then
    echo "Fehler: version.properties fehlt." >&2
    exit 1
fi
VERSION="$(sed -n 's/^[[:space:]]*version[[:space:]]*[=:][[:space:]]*//p' version.properties \
    | head -n1 | tr -d '\r' | sed 's/[[:space:]]*$//')"
if [[ -z "$VERSION" ]]; then
    echo "Fehler: Kein Eintrag 'version=…' in version.properties gefunden." >&2
    exit 1
fi
# Allowed characters for a Docker tag
if ! [[ "$VERSION" =~ ^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$ ]]; then
    echo "Fehler: Version '${VERSION}' ist als Docker-Tag ungültig (erlaubt: A-Z a-z 0-9 _ . -)." >&2
    exit 1
fi

IMAGE="${REPOSITORY}:${VERSION}"
REVISION="$(git rev-parse --short HEAD 2>/dev/null || echo unknown)"

echo "==> Baue ${IMAGE}"
docker build \
    -t "$IMAGE" \
    --label "org.opencontainers.image.title=Synchaven" \
    --label "org.opencontainers.image.version=${VERSION}" \
    --label "org.opencontainers.image.revision=${REVISION}" \
    --label "org.opencontainers.image.created=$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    .

# ---------- Store the version for docker-compose.yml ----------
# Other entries in .env are kept
if [[ -f .env ]] && grep -q '^SYNCHAVEN_VERSION=' .env; then
    sed -i "s/^SYNCHAVEN_VERSION=.*/SYNCHAVEN_VERSION=${VERSION}/" .env
else
    echo "SYNCHAVEN_VERSION=${VERSION}" >> .env
fi

echo
echo "==> Fertig: ${IMAGE}"
echo "    Starten:   docker compose up -d"
echo "    Hochladen: docker push ${IMAGE}"
