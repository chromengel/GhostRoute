#!/usr/bin/env python3
"""
Streams the US DOT National Address Database (NAD) national TXT zip and writes a
compact CSV of just the house-number address points GhostRoute covers:
Tennessee (all) + Western North Carolina (within the routing extent's bbox).

Output columns: house,street,city,state,lat,lon  (one address point per row)

The NAD is public-domain (USDOT, GMS-free). We stream the (huge) single TXT inside
the zip so we never unpack the full ~30 GB uncompressed file to disk.

Usage: filter-nad.py <NAD.zip> <out.csv>
"""
import csv
import io
import sys
import zipfile

# Western NC routing extent (matches build-geocoder.sh WNC_BBOX): minlon,minlat,maxlon,maxlat
WNC = (-84.35, 34.95, -81.0, 36.60)
KEEP_STATES = ("TN", "NC")


def find_col(header_low, *candidates):
    for c in candidates:
        cl = c.lower()
        if cl in header_low:
            return header_low.index(cl)
    return -1


def main(zip_path, out_csv):
    with zipfile.ZipFile(zip_path) as z:
        txt_name = next(
            n for n in z.namelist()
            if n.lower().endswith(".txt") and "nad" in n.lower()
        )
        print(f"reading {txt_name} from {zip_path}")
        with z.open(txt_name) as raw:
            stream = io.TextIOWrapper(raw, encoding="utf-8", errors="replace", newline="")
            reader = csv.reader(stream)  # NAD national TXT is comma-delimited w/ header
            header = next(reader)
            low = [h.strip().lower() for h in header]
            col = {
                "state": find_col(low, "State"),
                "numfull": find_col(low, "AddNo_Full"),
                "num": find_col(low, "Add_Number", "AddNum", "Address_Number", "AddressNumber"),
                "stfull": find_col(low, "StNam_Full"),
                "predir": find_col(low, "St_PreDir", "StN_PreDir", "PreDir"),
                "name": find_col(low, "St_Name", "StreetName", "StreetNam"),
                "postyp": find_col(low, "St_PosTyp", "StN_PosTyp", "PosTyp", "StreetType"),
                "posdir": find_col(low, "St_PosDir", "StN_PosDir", "PosDir"),
                "city": find_col(low, "Post_City", "Inc_Muni", "Census_Plc", "Uninc_Comm", "Post_Comm", "City"),
                "lon": find_col(low, "Longitude", "Long", "X"),
                "lat": find_col(low, "Latitude", "Lat", "Y"),
            }
            missing = [k for k in ("state", "lon", "lat") if col[k] < 0]
            if col["num"] < 0 and col["numfull"] < 0:
                missing.append("house-number")
            if col["name"] < 0 and col["stfull"] < 0:
                missing.append("street-name")
            if missing:
                raise SystemExit(f"NAD header missing required columns {missing}\nheader was: {header}")
            print("column map:", {k: header[v] if v >= 0 else None for k, v in col.items()})

            def cell(row, key):
                i = col[key]
                return row[i].strip() if 0 <= i < len(row) else ""

            with open(out_csv, "w", newline="") as out:
                w = csv.writer(out)
                seen = kept = 0
                for row in reader:
                    seen += 1
                    if seen % 5_000_000 == 0:
                        print(f"  scanned {seen:,} kept {kept:,}", file=sys.stderr)
                    st = cell(row, "state").upper()
                    if st not in KEEP_STATES:
                        continue
                    try:
                        lat = float(cell(row, "lat"))
                        lon = float(cell(row, "lon"))
                    except ValueError:
                        continue
                    if st == "NC" and not (WNC[0] <= lon <= WNC[2] and WNC[1] <= lat <= WNC[3]):
                        continue
                    num = cell(row, "numfull") or cell(row, "num")
                    if not num:
                        continue
                    street = cell(row, "stfull") or " ".join(
                        p for p in (cell(row, "predir"), cell(row, "name"),
                                    cell(row, "postyp"), cell(row, "posdir")) if p
                    )
                    if not street:
                        continue
                    w.writerow([num, street, cell(row, "city"), st, f"{lat:.6f}", f"{lon:.6f}"])
                    kept += 1
    print(f"kept {kept:,} of {seen:,} NAD rows -> {out_csv}")


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit(__doc__)
    main(sys.argv[1], sys.argv[2])
