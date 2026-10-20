"""Convert the old Tasker PlayTime db (playtime_s23u.db) into xScreenTime's legacy-plays import.

Usage: python tasker_playtime_to_json.py playtime_s23u.db out.json
Then: adb push out.json /sdcard/Android/data/com.maiconerick.xscreentime/files/import/

Only rows whose GameName maps to a known game package are kept. The Tasker profile sometimes logged
whatever app was in front when it fired (Nova Launcher, ChatGPT, Play Store), and those rows can't be
attributed to a game, so they are dropped together with rows that never got an end time.
"""
import json
import sqlite3
import sys

GAMES = {
    "Mobile Legends: Bang Bang": "com.mobile.legends",
    "TFT": "com.riotgames.league.teamfighttactics",
}


def main(src, dst):
    db = sqlite3.connect(src)
    plays, dropped = [], {}
    for name, start, end in db.execute(
        "SELECT GameName, TimeStarted_UNIX, TimeEnded_UNIX FROM PlayTime ORDER BY TimeStarted_UNIX"
    ):
        pkg = GAMES.get(name)
        if pkg is None or end is None or end < start:
            dropped[name] = dropped.get(name, 0) + 1
            continue
        plays.append({"pkg": pkg, "start": start * 1000, "end": end * 1000})
    with open(dst, "w", encoding="utf-8") as f:
        json.dump({"plays": plays}, f)
    print(f"kept {len(plays)} plays, dropped {dropped}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
