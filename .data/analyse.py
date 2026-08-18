# -*- coding: utf-8 -*-
"""Assess the two source datasets before generating any migration SQL."""
import csv
import re
from collections import defaultdict, Counter

DATA = "."

# ── ECI-coded hierarchy: state -> district -> assembly constituency ─────────
states = {}
with open(f"{DATA}/states.tsv", encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2:
            states[parts[0]] = parts[1]

districts = {}
with open(f"{DATA}/districts.tsv", encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3:
            districts[(parts[0], parts[1])] = parts[2]

acs = []
with open(f"{DATA}/assembly-constituencies.tsv", encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 4:
            acs.append((parts[0], parts[1], parts[2], parts[3]))

print(f"states={len(states)}  districts={len(districts)}  assembly_constituencies={len(acs)}")

# Do all ACs point at a district that exists?
orphans = [a for a in acs if (a[0], a[1]) not in districts]
print(f"ACs with no matching district row: {len(orphans)}")

# Per-state AC counts, against official figures.
official_vs = {
    "Andhra Pradesh": 175, "Arunachal Pradesh": 60, "Assam": 126, "Bihar": 243,
    "Chhattisgarh": 90, "Goa": 40, "Gujarat": 182, "Haryana": 90,
    "Himachal Pradesh": 68, "Jharkhand": 81, "Karnataka": 224, "Kerala": 140,
    "Madhya Pradesh": 230, "Maharashtra": 288, "Manipur": 60, "Meghalaya": 60,
    "Mizoram": 40, "Nagaland": 60, "Odisha": 147, "Punjab": 117,
    "Rajasthan": 200, "Sikkim": 32, "Tamil Nadu": 234, "Telangana": 119,
    "Tripura": 60, "Uttar Pradesh": 403, "Uttarakhand": 70, "West Bengal": 294,
    "NCT OF Delhi": 70, "Delhi": 70, "Jammu and Kashmir": 90,
    "Jammu & Kashmir": 90, "Puducherry": 30,
}

by_state = Counter(states.get(a[0], a[0]) for a in acs)
print("\n-- assembly constituencies per state (source vs official) --")
mismatch = 0
for name in sorted(by_state):
    have = by_state[name]
    want = official_vs.get(name)
    flag = ""
    if want is None:
        flag = "  (no assembly / not compared)"
    elif have != want:
        flag = f"  <-- differs by {have - want}"
        mismatch += 1
    print(f"  {name:<28} {have:>4}" + (f" / {want}" if want else "") + flag)
print(f"states differing from official: {mismatch}")

# ── LGD-coded parliament <-> assembly segment mapping ──────────────────────
pc_rows = []
with open(f"{DATA}/assembly_constituencies.csv", encoding="utf-8-sig") as fh:
    for row in csv.DictReader(fh):
        if row["Assembly Constituency Name"]:
            pc_rows.append(row)

pcs = {}
with open(f"{DATA}/parliament_constituencies.csv", encoding="utf-8-sig") as fh:
    for row in csv.DictReader(fh):
        pcs[(row["State Name"], row["Parliament Constituency Code"])] = \
            row["Parliament Constituency Name"]

print(f"\nparliament constituencies (LGD) = {len(pcs)}")
print(f"AC rows carrying a PC link      = {len(pc_rows)}")


def normalise(name):
    """Strip ECI's numeric prefix, punctuation and case for cross-source matching."""
    name = re.sub(r"^\d+\s*-\s*", "", name)
    name = re.sub(r"\(\s*(SC|ST)\s*\)", "", name, flags=re.I)
    name = re.sub(r"[^a-z]", "", name.lower())
    return name


# Can a PC be attributed to a district by matching its AC segments by name?
eci_ac_district = defaultdict(set)
for state_code, district_code, _ac_code, ac_name in acs:
    eci_ac_district[(states.get(state_code, ""), normalise(ac_name))].add(
        (state_code, district_code))

matched = 0
unmatched_examples = []
pc_districts = defaultdict(Counter)
for row in pc_rows:
    key = (row["State Name"], normalise(row["Assembly Constituency Name"]))
    hit = None
    for (st, ac), districts_for_ac in eci_ac_district.items():
        if ac == key[1] and st.lower().replace(" ", "") in key[0].lower().replace(" ", ""):
            hit = districts_for_ac
            break
    if hit:
        matched += 1
        for d in hit:
            pc_districts[(row["State Name"], row["Parliament Constituency Code"])][d] += 1
    elif len(unmatched_examples) < 12:
        unmatched_examples.append(f'{row["State Name"]} / {row["Assembly Constituency Name"]}')

rate = matched * 100.0 / len(pc_rows) if pc_rows else 0
print(f"\nAC name match across the two sources: {matched}/{len(pc_rows)} = {rate:.1f}%")
print(f"PCs that got at least one district:   {len(pc_districts)}/{len(pcs)}")
print("unmatched examples:")
for example in unmatched_examples:
    print("   ", example)
