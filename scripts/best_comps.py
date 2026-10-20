#!/usr/bin/env python3
"""Writes app/src/main/assets/team_comps.json: the one comp that wins most on each map in Radiant ranked games.

Run it from the repository root: python3 scripts/best_comps.py

The numbers come from vstats.gg, which publishes every act's ranked games per map and rank. For each map it
reads the newest act with at least 5,000 Radiant team games there: early in an act few players have reached
Radiant yet, so until the current act has that many, the map's previous act in ranked stands in. Only comps at
least 1 in 100 of those teams played, and at least 100 times, count: below that a win rate is mostly one
five-stack's. Of those, the best is the one whose win rate is highest even at the low end of its 95% confidence
interval (Wilson), so a comp with a high rate over a few games doesn't beat one with a slightly lower rate over many.

When an act starts, add it to ACTS; when a map enters or leaves the pool, change MAPS.
"""
import datetime
import gzip
import json
import math
import os
import urllib.request

# Acts newest first, as valorant-api.com/v1/seasons lists them, with how the app names the games they cover.
ACTS = [
    ("d816f426-48ea-f052-117f-9697a155b319", "Act VI so far, patch 13.08"),
    ("8102cd81-43a0-d0d7-bd59-47b8fe9bed1b", "Act V, Aug–Oct 2026"),
    ("4f0864e2-40af-28a4-de2c-0e9e64e75f23", "Act IV, Jun–Aug 2026"),
    ("ce2783e8-44fc-dd48-3da3-33b5ba6c4a22", "Act III, Apr–Jun 2026"),
    ("9d85c932-4820-c060-09c3-668636d4df1b", "Act II, Mar–Apr 2026"),
    ("3ea2b318-423b-cf86-25da-7cbb0eefbe2d", "Act I, Jan–Mar 2026"),
    ("4c4b8cff-43eb-13d3-8f14-96b783c90cd2", "Act VI of 2025, Oct 2025–Jan 2026"),
    ("5adc33fa-4f30-2899-f131-6fba64c5dd3a", "Act V of 2025, Aug–Oct 2025"),
    ("ac12e9b3-47e6-9599-8fa1-0bb473e5efc7", "Act IV of 2025, Jun–Aug 2025"),
]

# Map, its codename in the game files, and whether ranked plays it now.
MAPS = [
    ("Abyss", "Infinity", True),
    ("Bind", "Duality", True),
    ("Corrode", "Rook", True),
    ("Haven", "Triad", True),
    ("Lotus", "Jam", True),
    ("Summit", "Plummet", True),
    ("Sunset", "Juliett", True),
    ("Ascent", "Ascent", False),
    ("Split", "Bonsai", False),
    ("Breeze", "Foxtrot", False),
    ("Pearl", "Pitt", False),
    ("Fracture", "Canyon", False),
    ("Icebox", "Port", False),
]

# vstats.gg's rank number for Radiant games, as its own Radiant filter picks them, and the name the app shows.
RADIANT = 27
RANK_NAME = "Radiant"
# Team games an act needs on a map to stand for it, and that a comp needs to count, beside its share of them.
MIN_ACT_GAMES = 5000
MIN_COMP_GAMES = 100
MIN_COMP_SHARE = 0.01

OUT = "app/src/main/assets/team_comps.json"


def fetch(url):
    request = urllib.request.Request(url, headers={"User-Agent": "radianite-best-comps"})
    with urllib.request.urlopen(request, timeout=60) as response:
        body = response.read()
    # vstats.gg answers a map an act didn't play with its web page rather than a 404.
    return json.loads(gzip.decompress(body)) if body[:2] == b"\x1f\x8b" else []


def radiant_games(code):
    """The map's Radiant comp rows from the newest act with enough of them, and that act's index in ACTS."""
    for index, (act, _) in enumerate(ACTS):
        rows = [r for r in fetch(f"https://www.vstats.gg/statistics/{act}/ALL/{code}/comp.json.gz") if r["r"] == RADIANT]
        if sum(r["m"] for r in rows) >= MIN_ACT_GAMES:
            return rows, index
    raise SystemExit(f"no act has {MIN_ACT_GAMES} {RANK_NAME} team games on {code}")


def agent_names():
    agents = json.loads(urllib.request.urlopen("https://valorant-api.com/v1/agents?isPlayableCharacter=true", timeout=60).read())["data"]
    return {a["uuid"].lower(): a["displayName"] for a in agents}


def wilson_low(wins, n, z=1.96):
    p = wins / n
    return (p + z * z / (2 * n) - z * math.sqrt(p * (1 - p) / n + z * z / (4 * n * n))) / (1 + z * z / n)


def best(rows, names):
    comps = {}
    for row in rows:
        key = tuple(sorted(names.get(a.lower(), a) for a in row["as"]))
        tally = comps.setdefault(key, [0, 0.0])
        tally[0] += row["m"]
        tally[1] += row["wr"] * row["m"] / 100
    teams = sum(n for n, _ in comps.values())
    floor = max(MIN_COMP_GAMES, teams * MIN_COMP_SHARE)
    eligible = [(wilson_low(w, n), key, n, w) for key, (n, w) in comps.items() if n >= floor]
    if not eligible:
        raise SystemExit(f"no comp reaches {floor:.0f} games")
    _, agents, n, w = max(eligible)
    return {"agents": list(agents), "matches": n, "winPercent": round(w / n * 100, 1)}


def main():
    names = agent_names()
    maps = []
    for name, code, in_pool in MAPS:
        rows, act = radiant_games(code)
        comp = best(rows, names)
        period = ACTS[act][1]
        print(f"{name:9} {comp['winPercent']:5.1f}% of {comp['matches']:5}  {', '.join(comp['agents'])}  ({period})")
        maps.append({"map": name, "inPool": in_pool, "period": period, "thisAct": act == 0, "best": comp})
    about = {
        "updated": datetime.date.today().isoformat(),
        "rankFilter": RANK_NAME,
        "sourceName": "vstats.gg",
        "sourceUrl": "https://www.vstats.gg",
    }
    # One map to a line, so a refresh's diff reads map by map.
    lines = [f"  {json.dumps(key)}: {json.dumps(value)}," for key, value in about.items()]
    lines += ['  "maps": [', ",\n".join("    " + json.dumps(m, ensure_ascii=False) for m in maps), "  ]"]
    with open(OUT, "w") as f:
        f.write("{\n" + "\n".join(lines) + "\n}\n")
    print(f"wrote {os.path.abspath(OUT)}")


if __name__ == "__main__":
    main()
