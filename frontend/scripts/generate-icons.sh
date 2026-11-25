#!/bin/sh
# Renders the PNG app icons in public/ from the SVG sources in icons/.
# iOS ignores SVG icons for the home screen and fills transparent areas with black,
# so it needs an opaque PNG (apple-touch-icon); Chrome wants 192/512 px PNGs in the manifest.
#
# Requires rsvg-convert (librsvg). Run from frontend/: sh scripts/generate-icons.sh
set -eu
cd "$(dirname "$0")/.."

render() { rsvg-convert -w "$2" -h "$2" "icons/$1" -o "public/$3"; }

render app-icon-ios.svg 180 apple-touch-icon.png
render app-icon-any.svg 192 icon-192.png
render app-icon-any.svg 512 icon-512.png
render app-icon-maskable.svg 512 icon-maskable-512.png
