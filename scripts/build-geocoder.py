#!/usr/bin/env python3
"""
Builds GhostRoute's offline geocoder index from one or more OSM .pbf extracts,
each tagged with the US state it covers.

Extracts named places (cities/towns/...), streets (highway+name), and POIs
(amenity/shop/...) with a representative lat/lon, a prominence rank, a short
ADDRESS/LOCALITY subtitle (so two same-named hits are distinguishable — which
"Kroger", which "Hendersonville"), and the STATE. The full-text index covers the
name, the address, AND the state (full name + abbreviation), so natural queries
like "Hendersonville North Carolina", "Hendersonville NC", or a street + city all
match. The DB ships to the device (like the routing graph) and is queried on-
device as the user types — no network, no Google.

Usage:
  build-geocoder.py <output.db> <input.osm.pbf>:<STATE> [<input2.osm.pbf>:<STATE2> ...]
  e.g. build-geocoder.py geocoder.db tennessee.osm.pbf:TN wnc.osm.pbf:NC
"""
import os
import sqlite3
import sys
from collections import defaultdict

import osmium

PLACE_RANK = {
    "city": 100, "town": 80, "village": 60, "suburb": 48,
    "hamlet": 38, "neighbourhood": 32, "locality": 22,
}
LOCALITY_PLACES = ("city", "town", "village", "hamlet")
POI_KEYS = ("amenity", "shop", "tourism", "leisure", "office", "healthcare", "aeroway")
POI_RANK = 25
STREET_RANK = 12
ADDRESS_RANK = 10  # below streets: a bare street query shows the street first, then its houses
STREET_CELL = 0.05
ADDRESS_CELL = 0.0015  # ~150 m: collapse a house's node + building polygon into one entry
LOCALITY_CELL = 0.1

# State abbreviation -> full name. The FTS "region" content is "<Full> <ABBR>" so
# both "north carolina" and "nc" match. Extend as coverage grows.
STATE_FULL = {
    "TN": "Tennessee", "NC": "North Carolina", "GA": "Georgia",
    "VA": "Virginia", "KY": "Kentucky", "SC": "South Carolina",
}


def street_line(tags) -> str:
    house = tags.get("addr:housenumber")
    street = tags.get("addr:street")
    if street and house:
        return f"{house} {street}"
    return street or ""


def make_addr(line: str, town: str, state: str) -> str:
    """Display subtitle, e.g. '550 NC-9, Black Mountain, NC' or just 'NC' for a city."""
    base = ", ".join(p for p in (line, town) if p)
    return f"{base}, {state}" if base else state


def process(pbf: str, state: str):
    """Parse one extract; return rows (name, kind, addr, state, lat, lon, rank)."""
    place_rows = []
    pois = []
    streets = []
    addresses = []  # (house, street, city_or_None, lat, lon)
    localities = []
    seen_street = set()
    seen_addr = set()
    seen_poi = set()

    def add_address(house, street, city, lat, lon):
        # Dedup a house's address node + its building polygon (same number+street nearby).
        key = (house.lower(), street.lower(), round(lat / ADDRESS_CELL), round(lon / ADDRESS_CELL))
        if key in seen_addr:
            return
        seen_addr.add(key)
        addresses.append((house, street, city, lat, lon))

    def add_poi(name, cat, line, city, lat, lon):
        # Dedup a store mapped as BOTH a node and a building polygon (collapse to one).
        key = (name.lower(), cat, round(lat / ADDRESS_CELL), round(lon / ADDRESS_CELL))
        if key in seen_poi:
            return
        seen_poi.add(key)
        pois.append((name, cat, line, city, lat, lon))

    def way_centroid(nodes):
        slat = slon = 0.0
        cnt = 0
        for n in nodes:
            loc = n.location
            if loc.valid():
                slat += loc.lat
                slon += loc.lon
                cnt += 1
        return (slat / cnt, slon / cnt) if cnt else None

    for obj in osmium.FileProcessor(pbf).with_locations():
        tags = obj.tags
        kind = type(obj).__name__
        name = tags.get("name")
        house = tags.get("addr:housenumber")
        street = tags.get("addr:street")

        if kind == "Node":
            loc = obj.location
            if not loc.valid():
                continue
            place = tags.get("place")
            if name and place in PLACE_RANK:
                place_rows.append((name, place, make_addr("", "", state), state, loc.lat, loc.lon, PLACE_RANK[place]))
                if place in LOCALITY_PLACES:
                    localities.append((loc.lat, loc.lon, name))
                continue
            if name:
                cat = next((tags.get(k) for k in POI_KEYS if k in tags), None)
                if cat:
                    add_poi(name, cat, street_line(tags), tags.get("addr:city"), loc.lat, loc.lon)
                    continue
            if house and street:  # a bare address point (a house) — no name, no category
                add_address(house, street, tags.get("addr:city"), loc.lat, loc.lon)

        elif kind == "Way":
            nodes = obj.nodes
            if len(nodes) == 0:
                continue
            # Streets are lines → use the middle node; everything else (building polygons)
            # uses an averaged centroid so a POI/address lands inside the footprint.
            if "highway" in tags and name:
                loc = nodes[len(nodes) // 2].location
                if not loc.valid():
                    continue
                key = (name.lower(), round(loc.lat / STREET_CELL), round(loc.lon / STREET_CELL))
                if key not in seen_street:
                    seen_street.add(key)
                    streets.append((name, loc.lat, loc.lon))
                continue
            c = way_centroid(nodes)
            if c is None:
                continue
            clat, clon = c
            cat = next((tags.get(k) for k in POI_KEYS if k in tags), None) if name else None
            if name and cat:  # a building POI (e.g. a Kroger mapped as its polygon)
                add_poi(name, cat, street_line(tags), tags.get("addr:city"), clat, clon)
            elif house and street:  # a building polygon carrying a street address
                add_address(house, street, tags.get("addr:city"), clat, clon)

    # Nearest-town index for address-less results.
    grid = defaultdict(list)
    for lat, lon, lname in localities:
        grid[(round(lat / LOCALITY_CELL), round(lon / LOCALITY_CELL))].append((lat, lon, lname))

    def nearest_town(lat, lon):
        ci, cj = round(lat / LOCALITY_CELL), round(lon / LOCALITY_CELL)
        best, best_d = "", 1e18
        for ring in range(0, 7):
            for i in range(ci - ring, ci + ring + 1):
                for j in range(cj - ring, cj + ring + 1):
                    for plat, plon, lname in grid.get((i, j), ()):
                        d = (plat - lat) ** 2 + (plon - lon) ** 2
                        if d < best_d:
                            best_d, best = d, lname
            if best and ring >= 1:
                break
        return best

    rows = list(place_rows)
    for name, cat, line, city, lat, lon in pois:
        town = city or nearest_town(lat, lon)
        rows.append((name, cat, make_addr(line, town, state), state, lat, lon, POI_RANK))
    for name, lat, lon in streets:
        rows.append((name, "street", make_addr("", nearest_town(lat, lon), state), state, lat, lon, STREET_RANK))
    for house, street, city, lat, lon in addresses:
        town = city or nearest_town(lat, lon)
        rows.append((f"{house} {street}", "address", make_addr("", town, state), state, lat, lon, ADDRESS_RANK))
    print(f"  {pbf} [{state}]: {len(place_rows)} places, {len(pois)} POIs, "
          f"{len(streets)} streets, {len(addresses)} addresses")
    return rows


def main(out: str, inputs) -> None:
    if os.path.exists(out):
        os.remove(out)
    db = sqlite3.connect(out)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute(
        "CREATE TABLE places("
        "id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT, kind TEXT, "
        "addr TEXT, state TEXT, lat REAL, lon REAL, rank INTEGER)"
    )

    total = 0
    for spec in inputs:
        pbf, _, state = spec.rpartition(":")
        if not pbf or not state:
            raise SystemExit(f"bad input '{spec}' — expected <pbf>:<STATE>")
        rows = process(pbf, state.upper())
        db.executemany(
            "INSERT INTO places(name,kind,addr,state,lat,lon,rank) VALUES(?,?,?,?,?,?,?)", rows
        )
        total += len(rows)

    # FTS over name + address + region (full state name + abbreviation), so
    # "Main Street Hendersonville" and "Hendersonville NC" both match. FTS4 (not
    # FTS5) — guaranteed on every Android SQLite build. docid maps to places.id.
    region_case = "CASE state " + " ".join(
        f"WHEN '{ab}' THEN '{full} {ab}'" for ab, full in STATE_FULL.items()
    ) + " ELSE state END"
    db.execute("CREATE VIRTUAL TABLE places_fts USING fts4(name, addr, region, tokenize=unicode61)")
    db.execute(
        f"INSERT INTO places_fts(docid, name, addr, region) "
        f"SELECT id, name, addr, {region_case} FROM places"
    )
    db.execute("INSERT INTO places_fts(places_fts) VALUES('optimize')")
    db.execute("CREATE INDEX idx_rank ON places(rank)")
    db.commit()
    db.execute("VACUUM")
    db.close()
    print(f"indexed {total} features")
    print(f"db size: {os.path.getsize(out) / 1e6:.1f} MB")


if __name__ == "__main__":
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2:])
