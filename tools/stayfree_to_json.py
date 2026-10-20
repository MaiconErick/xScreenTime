#!/usr/bin/env python3
"""
Convert a StayFree "Total Usage" .xls export into the JSON that xScreenTime imports.

Usage:
    stayfree_to_json.py <export.xls> <out.json>
Then push it to the phone (the app imports it on its next sync):
    adb push out.json /sdcard/Android/data/com.maiconerick.xscreentime/files/import/

Needs xlrd (pip install xlrd). Website rows (domains) are skipped: they overlap browser time.
"""
import json
import re
import sys
from datetime import datetime

import xlrd

DOMAIN = re.compile(r"^[a-z0-9-]+(\.[a-z0-9-]+)+$")


def secs(v) -> int:
    return sum(int(n) * {"h": 3600, "m": 60, "s": 1}[u] for n, u in re.findall(r"(\d+)\s*([hms])", str(v)))


def day(label: str) -> str:
    return datetime.strptime(label.strip(), "%B %d, %Y").strftime("%Y-%m-%d")


def main(src: str, dst: str):
    book = xlrd.open_workbook(src)
    time_sh, count_sh = book.sheet_by_name("Usage Time"), book.sheet_by_name("Usage Count")
    header = time_sh.row_values(0)
    # Columns: '', Device, <date>..., optionally 'Total Usage' (dropped when the xls hits 256 columns).
    date_cols = [(c, day(h)) for c, h in enumerate(header) if c >= 2 and h and h != "Total Usage"]

    counts = {}
    for r in range(1, count_sh.nrows):
        counts[count_sh.cell_value(r, 0)] = count_sh.row_values(r)

    days = {d: [] for _, d in date_cols}
    for r in range(1, time_sh.nrows):
        label, device = time_sh.cell_value(r, 0), time_sh.cell_value(r, 1)
        if not label or not device or DOMAIN.match(str(label)):
            continue
        crow = counts.get(label)
        for c, d in date_cols:
            s = secs(time_sh.cell_value(r, c))
            if s > 0:
                n = int(float(crow[c] or 0)) if crow and c < len(crow) else 0
                days[d].append([str(label), s, n])

    unlocks = {}
    ush = book.sheet_by_name("Device Unlocks")
    for c, h in enumerate(ush.row_values(0)):
        if c >= 1 and h and h != "Total Usage":
            unlocks[day(h)] = int(float(ush.cell_value(1, c) or 0))

    out = {
        "source": "stayfree",
        "days": [{"date": d, "apps": a} for d, a in sorted(days.items()) if a],
        "unlocks": unlocks,
    }
    with open(dst, "w") as f:
        json.dump(out, f, ensure_ascii=False, separators=(",", ":"))
    total = sum(s for d in out["days"] for _, s, _ in d["apps"])
    print(f"{len(out['days'])} days, {len(unlocks)} unlock days, {total / 3600:.0f}h total -> {dst}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
