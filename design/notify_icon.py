#!/usr/bin/env python3
"""Writes the status-bar icon: CV's coyote in its goggles, ears on air.

    python3 design/notify_icon.py          # needs shapely (pip install shapely)

Android draws a notification's small icon as a cut-out in one colour: only the
shape's alpha counts. So the mascot is drawn here as geometry on the 24-unit
grid of a 24dp icon: the head, the hair, the goggles with a gap round them,
the inner ears and the nose cut out, and two waves over each ear. The shapes
are joined and cut with shapely and written as one white path to
res/drawable/ic_stat_cv.xml, which the launcher's themed icon reuses too.

The design is Rio's own edit of the round of variants made from the mascot
(2026-10-28): the head at 0.85 of the grid, the same scale both ways, and the
waves in the top corners.
"""
import math
import os

from shapely import affinity
from shapely.geometry import LineString, Point, Polygon, box
from shapely.geometry.polygon import orient
from shapely.ops import unary_union

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable",
                   "ic_stat_cv.xml")

SCALE = 0.85        # the head on the grid, both ways
DROP = 0.7          # and how far down, so the waves have the corners
GAP = 0.85          # the dark line round the goggles
WAVES = dict(centre=(4.68, 4.65), radii=(1.85, 3.3), width=1.1, towards=-124, span=88)

VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<!-- CV's coyote for the status bar and the themed launcher icon. Written by design/notify_icon.py.
     The path is long for a vector (lint's VectorPath): it is drawn small and
     seldom, and the precision is what keeps the cuts round the goggles open. -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    tools:ignore="VectorPath"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:fillType="evenOdd"
        android:pathData="{path}" />
</vector>
"""


def bez(p0, p1, p2, p3, n=16):
    """A cubic Bézier as n segments."""
    pts = []
    for i in range(n + 1):
        t = i / n
        u = 1 - t
        pts.append((u ** 3 * p0[0] + 3 * u * u * t * p1[0] + 3 * u * t * t * p2[0] + t ** 3 * p3[0],
                    u ** 3 * p0[1] + 3 * u * u * t * p1[1] + 3 * u * t * t * p2[1] + t ** 3 * p3[1]))
    return pts


def join(segs):
    pts = []
    for s in segs:
        pts.extend(s if not pts else s[1:])
    return pts


def shape(segs):
    return Polygon(join(segs)).buffer(0)


def mirror(g):
    return affinity.scale(g, xfact=-1, origin=(12, 12))


def rrect(x0, y0, x1, y1, r):
    return box(x0 + r, y0 + r, x1 - r, y1 - r).buffer(r, quad_segs=8)


def ellipse(cx, cy, rx, ry):
    return affinity.scale(Point(cx, cy).buffer(1, quad_segs=16), rx, ry)


def head():
    """The face front-on, from its left half: tall ears tilted out, cheek fur in points, the muzzle."""
    left = join([
        bez((12, 6.2), (10.8, 6.2), (9.6, 6.6), (8.6, 7.3)),            # brow
        bez((8.6, 7.3), (7.6, 5.0), (5.6, 2.4), (3.4, 0.8)),            # inner ear
        bez((3.4, 0.8), (2.4, 3.6), (2.0, 7.4), (2.5, 10.4)),           # outer ear
        [(2.5, 10.4), (0.8, 13.6), (2.7, 13.7), (1.7, 16.6), (4.9, 17.0)],  # cheek fur
        bez((4.9, 17.0), (6.6, 17.6), (8.0, 18.9), (9.0, 20.3)),        # muzzle side
        bez((9.0, 20.3), (9.8, 21.6), (10.8, 22.3), (12, 22.3)),        # chin
    ])
    right = [(24 - x, y) for x, y in reversed(left)]
    return Polygon(left + right[1:-1]).buffer(0)


def hair():
    """The mascot's crest: a big lock swept up and over to the right, a smaller one on the left, a flick."""
    return shape([
        bez((9.2, 7.2), (9.0, 5.6), (9.4, 4.2), (10.2, 3.4)),
        bez((10.2, 3.4), (10.4, 4.2), (10.8, 4.8), (11.3, 5.1)),
        bez((11.3, 5.1), (11.2, 3.2), (12.6, 1.4), (15.2, 1.2)),
        bez((15.2, 1.2), (14.2, 1.8), (13.8, 2.8), (14.2, 3.9)),
        bez((14.2, 3.9), (14.8, 3.6), (15.4, 3.7), (15.8, 4.1)),
        bez((15.8, 4.1), (15.0, 4.8), (14.8, 6.0), (14.8, 7.2)),
        [(14.8, 7.2), (9.2, 7.2)],
    ])


def inner_ears():
    a = shape([bez((4.2, 3.6), (4.0, 5.4), (4.0, 7.4), (4.3, 8.8)), [(4.3, 8.8), (7.0, 7.2)],
               bez((7.0, 7.2), (6.2, 5.8), (5.2, 4.6), (4.2, 3.6))])
    return a.union(mirror(a))


GOGGLES = (2.4, 21.6, 9.4, 14.2)


def goggles(x0, x1, y0, y1, notch=1.1):
    """An arched band with a notch over the nose."""
    mid = (x0 + x1) / 2
    return shape([
        bez((x0, y0 + 1.0), (x0 + 3, y0 - 0.5), (x1 - 3, y0 - 0.5), (x1, y0 + 1.0)),
        bez((x1, y0 + 1.0), (x1 + 1.0, y0 + 2.0), (x1 + 1.0, y1 - 1.5), (x1 - 0.6, y1)),
        bez((x1 - 0.6, y1), (x1 - 3, y1 + 0.4), (mid + 2.2, y1 + 0.2), (mid + 1.2, y1 - notch)),
        bez((mid + 1.2, y1 - notch), (mid + 0.5, y1 - notch - 0.6), (mid - 0.5, y1 - notch - 0.6), (mid - 1.2, y1 - notch)),
        bez((mid - 1.2, y1 - notch), (mid - 2.2, y1 + 0.2), (x0 + 3, y1 + 0.4), (x0 + 0.6, y1)),
        bez((x0 + 0.6, y1), (x0 - 1.0, y1 - 1.5), (x0 - 1.0, y0 + 2.0), (x0, y0 + 1.0)),
    ])


def lenses(x0, x1, y0, y1, gap=2.4, pad=1.2):
    mid = (x0 + x1) / 2
    return rrect(x0 + pad, y0 + pad * 0.9, mid - gap / 2, y1 - pad * 1.25, 1.1).union(
        rrect(mid + gap / 2, y0 + pad * 0.9, x1 - pad, y1 - pad * 1.25, 1.1))


def waves(centre, radii, width, towards, span):
    out = []
    for r in radii:
        a0, a1 = math.radians(towards - span / 2), math.radians(towards + span / 2)
        arc = [(centre[0] + r * math.cos(a0 + (a1 - a0) * i / 24), centre[1] + r * math.sin(a0 + (a1 - a0) * i / 24))
               for i in range(25)]
        out.append(LineString(arc).buffer(width / 2, cap_style="round"))
    return unary_union(out)


def glyph():
    # Placed first and cut after, so the gaps keep their width at the smaller size.
    place = lambda g: affinity.translate(affinity.scale(g, SCALE, SCALE, origin=(12, 22.3)), 0, DROP)
    face, band = place(head().union(hair())), place(goggles(*GOGGLES))
    nose, ears, glass = place(ellipse(12, 18.6, 2.0, 1.3)), place(inner_ears()), place(lenses(*GOGGLES))
    face = face.difference(band.buffer(GAP)).difference(nose).difference(ears).union(band.difference(glass))
    left = waves(**WAVES)
    return face.union(left.union(mirror(left)).difference(face.buffer(0.6)))


def path_data(geom, tolerance=0.03):
    geom = geom.simplify(tolerance)
    polys = [geom] if geom.geom_type == "Polygon" else list(geom.geoms)
    rings = []
    # Slivers left where a cut grazed an edge are dust at 24dp.
    for p in (orient(p) for p in polys if p.area > 0.4):
        for ring in [p.exterior, *p.interiors]:
            pts = list(ring.coords)[:-1]
            rings.append("M" + " ".join("%s,%s" % (num(x), num(y)) for x, y in pts) + "Z")
    return "".join(rings)


def num(v):
    return ("%.2f" % v).rstrip("0").rstrip(".")


if __name__ == "__main__":
    d = path_data(glyph())
    with open(OUT, "w") as f:
        f.write(VECTOR.format(path=d))
    print("wrote", os.path.relpath(OUT), len(d), "characters of path")
