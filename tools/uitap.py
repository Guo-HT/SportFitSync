#!/usr/bin/env python3
"""Tap Android UI elements by their accessible text instead of by pixel.

Pixel coordinates in a Compose app drift every time the layout changes (a card
appears, a dialog interrupts), which makes screenshot-driven scripts fragile and
hard to trust. `uiautomator dump` exposes the accessibility tree that Compose
already publishes, so the element can be located by what it says.

Usage:
    tools/uitap.py "选择活动同步"          # tap the first match
    tools/uitap.py --dump                  # print every visible node
    tools/uitap.py --wait "同步 12 条"     # retry for up to 15s before failing
    tools/uitap.py --contains "导出"       # substring match
"""

from __future__ import annotations

import argparse
import re
import subprocess
import sys
import time
import xml.etree.ElementTree as ET

ADB = "adb"
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


def adb(*args: str, check: bool = True) -> str:
    result = subprocess.run(
        [ADB, *args], capture_output=True, text=True, check=False
    )
    if check and result.returncode != 0:
        sys.stderr.write(result.stderr)
        raise SystemExit(f"adb {' '.join(args)} failed")
    return result.stdout


def dump_tree() -> ET.Element:
    # uiautomator occasionally races with an animating window; retry once.
    for attempt in range(3):
        adb("shell", "uiautomator", "dump", "/sdcard/ui.xml", check=False)
        raw = adb("shell", "cat", "/sdcard/ui.xml", check=False)
        start = raw.find("<?xml")
        if start >= 0:
            try:
                return ET.fromstring(raw[start:])
            except ET.ParseError:
                pass
        time.sleep(0.6)
    raise SystemExit("could not read the UI hierarchy")


def iter_nodes(root: ET.Element):
    for node in root.iter("node"):
        yield node


def label_of(node: ET.Element) -> str:
    return (node.get("text") or "").strip() or (node.get("content-desc") or "").strip()


def center(node: ET.Element) -> tuple[int, int]:
    match = BOUNDS_RE.match(node.get("bounds", ""))
    if not match:
        raise SystemExit(f"node has no usable bounds: {node.attrib}")
    x1, y1, x2, y2 = (int(g) for g in match.groups())
    return (x1 + x2) // 2, (y1 + y2) // 2


def find(root: ET.Element, needle: str, contains: bool, nth: int) -> ET.Element | None:
    """Return the nth (1-based) matching node.

    Several controls often share a label — the source and target rows both
    render "演练模式", for instance — so callers need to say which one.
    """
    matches = []
    for node in iter_nodes(root):
        text = label_of(node)
        if not text:
            continue
        if text == needle or (contains and needle in text):
            matches.append(node)
    if nth < 1 or nth > len(matches):
        return None
    return matches[nth - 1]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("text", nargs="?", help="text or content description to match")
    parser.add_argument("--contains", action="store_true", help="substring match")
    parser.add_argument("--dump", action="store_true", help="list visible nodes")
    parser.add_argument("--wait", type=float, default=0.0, help="seconds to keep retrying")
    parser.add_argument("--no-tap", action="store_true", help="locate but do not tap")
    parser.add_argument(
        "--nth",
        type=int,
        default=1,
        help="which match to use when several controls share the same label (1-based)",
    )
    args = parser.parse_args()

    if args.dump:
        for node in iter_nodes(dump_tree()):
            text = label_of(node)
            if text:
                x, y = center(node)
                print(f"{x:>5},{y:>5}  {text}")
        return 0

    if not args.text:
        parser.error("provide text to tap, or --dump")

    deadline = time.time() + args.wait
    while True:
        node = find(dump_tree(), args.text, args.contains, args.nth)
        if node is not None:
            x, y = center(node)
            print(f"{x},{y}  {label_of(node)}")
            if not args.no_tap:
                adb("shell", "input", "tap", str(x), str(y))
            return 0
        if time.time() >= deadline:
            sys.stderr.write(f"not found: {args.text!r}\n")
            return 1
        time.sleep(0.7)


if __name__ == "__main__":
    raise SystemExit(main())
