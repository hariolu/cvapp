#!/usr/bin/env python3
"""Writes the app's icons as Android vector drawables, from Material Design Icons.

    npm pack @mdi/js && tar xzf mdi-js-*.tgz
    python3 design/icons.py package/mdi.js

The CV Panel and Home Assistant draw their icons from the same set, so the app's
screens speak the same visual language. Each icon becomes res/drawable/
ic_mdi_<name>.xml, filled white and tinted where it is used. MDI is by
Pictogrammers, Apache License 2.0 (design/licenses/mdi-LICENSE.txt).
"""
import os
import re
import sys

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "app", "src", "main", "res", "drawable")
ICONS = [
    "access-point-network", "account-lock-outline", "alert-outline", "cancel", "car", "cellphone",
    "check-circle-outline", "chevron-right", "close", "close-circle-outline", "cloud-outline",
    "cog", "cog-outline", "compass-outline", "dialpad", "dock-bottom", "dock-top", "download",
    "email-outline", "github", "home-assistant", "home-outline", "information-outline",
    "key-variant", "lan-disconnect", "link-variant", "lock-open-variant-outline", "lock-outline",
    "palette-outline", "power-plug-outline", "refresh", "send", "shield-key-outline",
    "shield-lock-open-outline", "swap-horizontal", "timer-sand-complete", "update", "vpn", "web",
]
TOKEN = re.compile(r"[A-Za-z]|-?(?:\d+\.?\d*|\.\d+)(?:[eE][-+]?\d+)?")

VECTOR = """<?xml version="1.0" encoding="utf-8"?>
<!-- mdi-{name}, Material Design Icons {version} (Pictogrammers, Apache 2.0). design/icons.py -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="24dp"
    android:height="24dp"
    android:viewportWidth="24"
    android:viewportHeight="24">
    <path
        android:fillColor="#FFFFFFFF"
        android:pathData="{path}" />
</vector>
"""


def camel(name):
    return "mdi" + "".join(part.capitalize() for part in name.split("-"))


def main(source):
    text = open(source, encoding="utf-8").read()
    version = re.search(r"Material Design Icons v([\d.]+)", text).group(1)
    for name in ICONS:
        found = re.search(r'^export var %s = "([^"]+)";' % camel(name), text, re.M)
        if not found:
            sys.exit("no such icon: " + name)
        # One separator between every number: Android's path parser is stricter
        # than a browser's about runs like "0.5.5".
        path = " ".join(TOKEN.findall(found.group(1)))
        out = os.path.join(OUT, "ic_mdi_%s.xml" % name.replace("-", "_"))
        with open(out, "w", encoding="utf-8") as f:
            f.write(VECTOR.format(name=name, version=version, path=path))
    print(len(ICONS), "icons from MDI", version)


if __name__ == "__main__":
    main(sys.argv[1])
