#!/bin/sh
# Startet Synchaven ohne Root-Rechte.
#
# Läuft der Container als root (Standard), werden dem Datenverzeichnis die IDs aus PUID/PGID
# (Standard 10001) gegeben und das Programm anschließend mit diesen IDs gestartet. So
# funktionieren sowohl benannte Volumes als auch Bind-Mounts wie ./data:/data – für einen
# Bind-Mount PUID/PGID auf die IDs des Besitzers setzen (`id -u` / `id -g`).
#
# Wird der Container bereits mit `user:` / `--user` gestartet, startet das Programm direkt.

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
# Nur Abweichendes anpassen, damit große Datenverzeichnisse nicht bei jedem Start durchlaufen werden
find "$DATA_DIR" \( ! -user "$PUID" -o ! -group "$PGID" \) -exec chown "$PUID:$PGID" {} +

exec setpriv --reuid="$PUID" --regid="$PGID" --clear-groups -- synchaven "$@"
