#!/usr/bin/env python3
"""Erzeugt die Launcher-Icons mit dem Tag des Monats (1–31).

Die App schaltet täglich zwischen 31 activity-aliases um (siehe DynamicLauncherIcon.kt);
jeder Alias zeigt eines dieser Icons. Die Ziffern werden als Pfade aus Roboto Bold
übernommen, weil Vector-Drawables keinen Text kennen.

Aufruf (aus dem Repo-Wurzelverzeichnis, Frontend-Abhängigkeiten installiert):
    python3 Android/tools/generate_day_icons.py
"""
from pathlib import Path

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

ROOT = Path(__file__).resolve().parents[2]
FONT = ROOT / "frontend/node_modules/@fontsource/roboto/files/roboto-latin-700-normal.woff2"
RES = ROOT / "Android/app/src/main/res"

# Farben wie im Web-Favicon (frontend/public/favicon.svg)
BACKGROUND = "#FFFFFF"
BODY = "#6750A4"
HEADER = "#4F378B"
RINGS = "#21005D"
DIGITS = "#FFFFFF"

# Geometrie im 108×108-Raster des adaptiven Icons (sichtbarer Bereich: Kreis r=33 um 54,54)
CARD = (28, 30, 80, 80)          # links, oben, rechts, unten
CARD_R = 9
HEADER_BOTTOM = 44
RING_W, RING_TOP, RING_BOTTOM = 5, 26, 36
RING_X = (38, 65)
MONO_INNER = (32, 44, 76, 76)    # Innenfläche der Umriss-Variante (themed icons)
MONO_INNER_R = 5
DIGIT_HEIGHT = 20                # Versalhöhe der Ziffern
DIGIT_CENTER_Y = 61.5

VECTOR_HEAD = """<?xml version="1.0" encoding="utf-8"?>
<!-- Generiert von Android/tools/generate_day_icons.py – nicht von Hand bearbeiten. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
"""


def vector(*paths: tuple[str, str], fill_type: str = "evenOdd") -> str:
    # Kalenderformen brauchen evenOdd (Aussparungen), Ziffern nonZero: Roboto-Glyphen wie die 8
    # bestehen aus überlappenden Konturen, die evenOdd als Loch darstellen würde.
    body = "".join(
        f'    <path\n        android:fillColor="{color}"\n'
        f'        android:fillType="{fill_type}"\n'
        f'        android:pathData="{data}" />\n'
        for color, data in paths
    )
    return VECTOR_HEAD + body + "</vector>\n"


def fmt(v: float) -> str:
    return f"{v:.2f}".rstrip("0").rstrip(".")


def rounded_rect(l, t, r, b, rad, top=True, bottom=True) -> str:
    """Rechteck mit optional gerundeten oberen/unteren Ecken (im Uhrzeigersinn)."""
    rt, rb = (rad if top else 0), (rad if bottom else 0)
    p = [f"M{fmt(l + rt)},{fmt(t)}", f"H{fmt(r - rt)}"]
    if rt:
        p.append(f"A{rt},{rt} 0 0 1 {fmt(r)},{fmt(t + rt)}")
    p.append(f"V{fmt(b - rb)}")
    if rb:
        p.append(f"A{rb},{rb} 0 0 1 {fmt(r - rb)},{fmt(b)}")
    p.append(f"H{fmt(l + rb)}")
    if rb:
        p.append(f"A{rb},{rb} 0 0 1 {fmt(l)},{fmt(b - rb)}")
    p.append(f"V{fmt(t + rt)}")
    if rt:
        p.append(f"A{rt},{rt} 0 0 1 {fmt(l + rt)},{fmt(t)}")
    return "".join(p) + "Z"


def rings() -> str:
    return "".join(rounded_rect(x, RING_TOP, x + RING_W, RING_BOTTOM, RING_W / 2) for x in RING_X)


def calendar_colored() -> str:
    l, t, r, b = CARD
    return vector(
        (BODY, rounded_rect(l, t, r, b, CARD_R)),
        (HEADER, rounded_rect(l, t, r, HEADER_BOTTOM, CARD_R, bottom=False)),
        (RINGS, rings()),
    )


def calendar_mono() -> str:
    # Rahmen + Kopfleiste als eine Form: Außenkontur minus Innenfläche (evenOdd)
    return vector(
        ("#FF000000", rounded_rect(*CARD, CARD_R) + rounded_rect(*MONO_INNER, MONO_INNER_R)),
        ("#FF000000", rings()),
    )


def grid() -> str:
    """Neutrales Icon (vor dem ersten Start, App-Info): 3×2 Tagesfelder statt einer Zahl."""
    size, gap = 8, 4
    total_w = 3 * size + 2 * gap
    x0 = (CARD[0] + CARD[2]) / 2 - total_w / 2
    y0 = DIGIT_CENTER_Y - (2 * size + gap) / 2
    data = "".join(
        rounded_rect(x0 + c * (size + gap), y0 + row * (size + gap),
                     x0 + c * (size + gap) + size, y0 + row * (size + gap) + size, 1.5)
        for row in range(2) for c in range(3)
    )
    return vector((DIGITS, data))


def digits(font: TTFont, text: str) -> str:
    glyphs = font.getGlyphSet()
    cmap = font.getBestCmap()
    cap = font["OS/2"].sCapHeight
    scale = DIGIT_HEIGHT / cap
    names = [cmap[ord(ch)] for ch in text]

    # Tinten-Breite für die Zentrierung
    from fontTools.pens.boundsPen import BoundsPen
    x, xmin, xmax = 0.0, None, None
    for n in names:
        bp = BoundsPen(glyphs)
        glyphs[n].draw(bp)
        if bp.bounds:
            gx0, _, gx1, _ = bp.bounds
            xmin = x + gx0 if xmin is None else min(xmin, x + gx0)
            xmax = x + gx1 if xmax is None else max(xmax, x + gx1)
        x += glyphs[n].width
    ink_w = (xmax - xmin) * scale
    center_x = (CARD[0] + CARD[2]) / 2
    left = center_x - ink_w / 2 - xmin * scale
    baseline = DIGIT_CENTER_Y + DIGIT_HEIGHT / 2

    pen = SVGPathPen(glyphs, ntos=fmt)
    x = 0.0
    for n in names:
        # Schrift-Koordinaten (y nach oben) → Vektor-Koordinaten (y nach unten)
        tp = TransformPen(pen, (scale, 0, 0, -scale, left + x * scale, baseline))
        glyphs[n].draw(tp)
        x += glyphs[n].width
    return vector((DIGITS, pen.getCommands()), fill_type="nonZero")


def adaptive(foreground: list[str], monochrome: list[str]) -> str:
    def layers(names):
        return "".join(f'            <item android:drawable="@drawable/{n}" />\n' for n in names)

    return f"""<?xml version="1.0" encoding="utf-8"?>
<!-- Generiert von Android/tools/generate_day_icons.py – nicht von Hand bearbeiten. -->
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/ic_launcher_background" />
    <foreground>
        <layer-list>
{layers(foreground)}        </layer-list>
    </foreground>
    <monochrome>
        <layer-list>
{layers(monochrome)}        </layer-list>
    </monochrome>
</adaptive-icon>
"""


def main() -> None:
    font = TTFont(FONT)
    drawable, mipmap = RES / "drawable", RES / "mipmap-anydpi"
    drawable.mkdir(parents=True, exist_ok=True)
    mipmap.mkdir(parents=True, exist_ok=True)

    (drawable / "ic_launcher_background.xml").write_text(vector((BACKGROUND, "M0,0h108v108h-108z")))
    (drawable / "ic_launcher_calendar.xml").write_text(calendar_colored())
    (drawable / "ic_launcher_calendar_mono.xml").write_text(calendar_mono())
    (drawable / "ic_launcher_grid.xml").write_text(grid())

    base = adaptive(["ic_launcher_calendar", "ic_launcher_grid"],
                    ["ic_launcher_calendar_mono", "ic_launcher_grid"])
    (mipmap / "ic_launcher.xml").write_text(base)
    (mipmap / "ic_launcher_round.xml").write_text(base)

    for day in range(1, 32):
        (drawable / f"ic_launcher_digits_{day}.xml").write_text(digits(font, str(day)))
        (mipmap / f"ic_launcher_day_{day}.xml").write_text(
            adaptive(["ic_launcher_calendar", f"ic_launcher_digits_{day}"],
                     ["ic_launcher_calendar_mono", f"ic_launcher_digits_{day}"]))


if __name__ == "__main__":
    main()
