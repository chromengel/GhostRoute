#!/usr/bin/env python3
"""
Merges NAD house-number address points (the filtered CSV from filter-nad.py) into an
existing GhostRoute geocoder.db, REPLACING the sparse OSM address points (NAD is the
authoritative, comprehensive source). Places + POIs + streets from the OSM build are
left untouched.

Each address becomes a row: name="<house> <street>", kind="address", rank=10 (below
streets, so a bare street query still lists the street first), with the city/state
subtitle, and an FTS entry over name+addr+region so it's searchable on-device.

Usage: add-nad-addresses.py <geocoder.db> <nad_filtered.csv>
"""
import csv
import os
import sqlite3
import sys

ADDRESS_RANK = 10
ADDRESS_CELL = 0.0015  # ~150 m: collapse near-duplicate points for the same house+street
BATCH = 50_000

# Must match build-geocoder.py so the FTS "region" content is identical.
STATE_FULL = {
    "TN": "Tennessee", "NC": "North Carolina", "GA": "Georgia",
    "VA": "Virginia", "KY": "Kentucky", "SC": "South Carolina",
}


def make_addr(city, state):
    return f"{city}, {state}" if city else state


def main(dbpath, csvpath):
    db = sqlite3.connect(dbpath)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")

    # NAD supersedes the OSM address points — drop them (and their FTS rows) first.
    n_old = db.execute("SELECT COUNT(*) FROM places WHERE kind='address'").fetchone()[0]
    db.execute("DELETE FROM places_fts WHERE docid IN (SELECT id FROM places WHERE kind='address')")
    db.execute("DELETE FROM places WHERE kind='address'")
    print(f"removed {n_old:,} OSM address rows")

    max_id = db.execute("SELECT COALESCE(MAX(id), 0) FROM places").fetchone()[0]

    seen = set()
    batch = []
    inserted = 0

    def flush():
        nonlocal inserted
        if batch:
            db.executemany(
                "INSERT INTO places(name,kind,addr,state,lat,lon,rank) VALUES(?,?,?,?,?,?,?)",
                batch,
            )
            inserted += len(batch)
            batch.clear()

    with open(csvpath, newline="") as f:
        for house, street, city, state, lat, lon in csv.reader(f):
            try:
                latf, lonf = float(lat), float(lon)
            except ValueError:
                continue
            # NAD names are ALL-CAPS; title-case for display ("ABINGTON Road" -> "Abington Road").
            name = f"{house} {street.title()}"
            key = (name.lower(), round(latf / ADDRESS_CELL), round(lonf / ADDRESS_CELL))
            if key in seen:
                continue
            seen.add(key)
            batch.append((name, "address", make_addr(city.title(), state.upper()), state.upper(), latf, lonf, ADDRESS_RANK))
            if len(batch) >= BATCH:
                flush()
    flush()
    print(f"inserted {inserted:,} NAD address rows (deduped from {len(seen):,} keys)")

    region_case = "CASE state " + " ".join(
        f"WHEN '{ab}' THEN '{full} {ab}'" for ab, full in STATE_FULL.items()
    ) + " ELSE state END"
    db.execute(
        f"INSERT INTO places_fts(docid, name, addr, region) "
        f"SELECT id, name, addr, {region_case} FROM places WHERE id > ? AND kind='address'",
        (max_id,),
    )
    db.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"db size: {os.path.getsize(dbpath) / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
