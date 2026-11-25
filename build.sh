#!/usr/bin/env bash
# Builds the Synchaven Docker image as
#   <image>:<version>
# <image> is SYNCHAVEN_IMAGE from the environment or .env (default: ghcr.io/daved09/synchaven),
# e.g. SYNCHAVEN_IMAGE=registry.example.org/synchaven for a private registry.
# The version comes from version.properties (key "version"). The backend reads it itself at
# compile time (backend/build.rs); here it serves as the image tag and is stored as
# SYNCHAVEN_VERSION in .env so that `docker compose up -d` starts exactly this image.

set -euo pipefail

cd "$(dirname "$(readlink -f "$0")")"

DEFAULT_IMAGE="ghcr.io/daved09/synchaven"

# Image name: environment variable before .env before the default
if [[ -z "${SYNCHAVEN_IMAGE:-}" && -f .env ]]; then
    SYNCHAVEN_IMAGE="$(sed -n 's/^SYNCHAVEN_IMAGE=//p' .env | head -n1 | tr -d '\r')"
fi
REPOSITORY="${SYNCHAVEN_IMAGE:-$DEFAULT_IMAGE}"

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

# ---------- Store image and version for docker-compose.yml ----------
# Other entries in .env are kept
set_env() {
    if [[ -f .env ]] && grep -q "^$1=" .env; then
        sed -i "s|^$1=.*|$1=$2|" .env
    else
        echo "$1=$2" >> .env
    fi
}
set_env SYNCHAVEN_IMAGE "$REPOSITORY"
set_env SYNCHAVEN_VERSION "$VERSION"

echo
echo "==> Fertig: ${IMAGE}"
echo "    Starten:   docker compose up -d"
echo "    Hochladen: docker push ${IMAGE}"
