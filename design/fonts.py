#!/usr/bin/env python3
"""Builds the app's fonts from the ones Coyotes Vision's engine ships.

    python3 design/fonts.py <Inter-Variable.woff2> <JetBrainsMono-Variable.woff2>

Both variable fonts come from cv-core (component/legacy/www/), so the app's own
screens use the very typeface Home Assistant's pages get from the CV theme.
Android cannot read WOFF2 and does not pick a weight out of a variable font from
XML before API 31, so each weight the app uses becomes a static TTF, cut down to
the characters the app can show: Latin, punctuation, arrows. Every name record
is kept, which is where the fonts carry their copyright and the SIL Open Font
License (design/licenses/fonts-OFL.txt). Needs fontTools and brotli.
"""
import os
import sys

from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont
from fontTools.varLib.instancer import instantiateVariableFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "font")
CHARS = [*range(0x20, 0x7F), *range(0xA0, 0x180), *range(0x2010, 0x203B), *range(0x2190, 0x219A),
         0x2026, 0x20AC, 0x2122, 0x2212, 0x2215, 0x2219, 0x22C5]


def build(src, out, axes):
    font = instantiateVariableFont(TTFont(src), axes)
    options = Options()
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.layout_features += ["tnum", "case"]
    options.notdef_outline = True
    subsetter = Subsetter(options)
    subsetter.populate(unicodes=CHARS)
    subsetter.subset(font)
    font.flavor = None
    font.save(os.path.join(OUT, out))
    print(out, os.path.getsize(os.path.join(OUT, out)), "bytes")


def main(inter, mono):
    for name, weight in (("regular", 400), ("medium", 500), ("semibold", 600)):
        build(inter, "inter_%s.ttf" % name, {"wght": weight, "opsz": 14})
    # Titles: the optical size a browser picks for Inter at that size.
    build(inter, "inter_display.ttf", {"wght": 600, "opsz": 28})
    build(mono, "jetbrains_mono.ttf", {"wght": 400})


if __name__ == "__main__":
    main(*sys.argv[1:3])
