#!/bin/sh
# Starts Synchaven without root rights.
#
# If the container runs as root (default), the data directory is given the IDs from PUID/PGID
# (default 10001) and the program is then started with these IDs. This way
# both named volumes and bind mounts like ./data:/data work – for a
# bind mount, set PUID/PGID to the owner's IDs (`id -u` / `id -g`).
#
# If the container is already started with `user:` / `--user`, the program starts directly.

set -eu

if [ "$(id -u)" != "0" ]; then
    exec synchaven "$@"
fi

PUID="${PUID:-10001}"
PGID="${PGID:-10001}"
DATA_DIR="${DATA_DIR:-/data}"

case "$PUID$PGID" in
    '' | *[!0-9]*)
        echo "Fehler: PUID und PGID müssen Zahlen sein (PUID='$PUID', PGID='$PGID')." >&2
        exit 1
        ;;
esac

mkdir -p "$DATA_DIR"
# Only adjust what differs, so large data directories aren't traversed on every start
find "$DATA_DIR" \( ! -user "$PUID" -o ! -group "$PGID" \) -exec chown "$PUID:$PGID" {} +

exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups -- synchaven "$@"
