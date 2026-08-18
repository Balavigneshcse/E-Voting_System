# -*- coding: utf-8 -*-
"""
Generate V4__real_electoral_geography.sql.

Sources
-------
Constituencies (the spine): Local Government Directory, as of March 2024, via
https://gist.github.com/planemad/96a5a3644a6fed2a43ddf579f6a9612d
  - 543 parliamentary constituencies
  - 4,120 assembly constituencies
  - the parliamentary <-> assembly segment mapping

Districts: ECI directory via
https://github.com/anandology/election-directory
  - 699 districts with their assembly constituencies

The two use different code systems, so assembly constituencies are matched by
normalised name within a state to attach a district.
"""
import csv
import re
from collections import defaultdict, Counter

OUT = "../E-voting_Server/src/main/resources/db/migration/V4__real_electoral_geography.sql"

# ── Map both source vocabularies onto the existing states table (ids 1..36) ──
ECI_STATE_TO_ID = {
    "Andhra Pradesh": 1, "Arunachal Pradesh": 2, "Assam": 3, "Bihar": 4,
    "Chhattisgarh": 5, "Goa": 6, "Gujarat": 7, "Haryana": 8,
    "Himachal Pradesh": 9, "Jharkhand": 10, "Karnataka": 11, "Kerala": 12,
    "Madhya Pradesh": 13, "Maharashtra": 14, "Manipur": 15, "Meghalaya": 16,
    "Mizoram": 17, "Nagaland": 18, "Odisha": 19, "Punjab": 20,
    "Rajasthan": 21, "Sikkim": 22, "Tamil Nadu": 23, "Telangana": 24,
    "Tripura": 25, "Uttar Pradesh": 26, "Uttarakhand": 27, "West Bengal": 28,
    "Andaman & Nicobar Islands": 29, "Chandigarh": 30,
    "Dadra & Nagar Haveli": 31, "Daman & Diu": 31,
    "NCT OF Delhi": 32, "Jammu & Kashmir": 33, "Lakshadweep": 35,
    "Puducherry": 36,
}

LGD_STATE_TO_ID = {
    "Andhra Pradesh": 1, "Arunachal Pradesh": 2, "Assam": 3, "Bihar": 4,
    "Chhattisgarh": 5, "Goa": 6, "Gujarat": 7, "Haryana": 8,
    "Himachal Pradesh": 9, "Jharkhand": 10, "Karnataka": 11, "Kerala": 12,
    "Madhya Pradesh": 13, "Maharashtra": 14, "Manipur": 15, "Meghalaya": 16,
    "Mizoram": 17, "Nagaland": 18, "Odisha": 19, "Punjab": 20,
    "Rajasthan": 21, "Sikkim": 22, "Tamil Nadu": 23, "Telangana": 24,
    "Tripura": 25, "Uttar Pradesh": 26, "Uttarakhand": 27, "West Bengal": 28,
    "Andaman And Nicobar Islands": 29, "Chandigarh": 30,
    "The Dadra And Nagar Haveli And Daman And Diu": 31,
    "Delhi": 32, "Jammu And Kashmir": 33, "Ladakh": 34,
    "Lakshadweep": 35, "Puducherry": 36,
}

# Ladakh was carved out in 2019 and so is absent from the ECI district list.
EXTRA_DISTRICTS = [(34, "Leh"), (34, "Kargil")]

# Union territories with no legislative assembly, and therefore no CM election and
# no assembly constituencies. The source directory lists administrative segments for
# some of them; including those would let a voter be registered for an assembly
# election that does not exist.
NO_ASSEMBLY_STATE_IDS = {
    29,  # Andaman & Nicobar Islands
    30,  # Chandigarh
    31,  # Dadra & Nagar Haveli and Daman & Diu
    34,  # Ladakh
    35,  # Lakshadweep
}

# Sikkim's 32nd seat is reserved for the Buddhist monastic community and is missing
# from the directory snapshot.
SIKKIM_SANGHA = ("Sangha", "Gangtok", "GEN")

# Jammu & Kashmir is handled separately. The directory snapshot still carries the
# pre-2019 83-seat list, whereas the 2022 Delimitation Commission redrew the union
# territory into 90 seats, renaming and reorganising many of them. Patching seven
# names onto the old list would produce a list matching neither, so the state's
# assembly constituencies are replaced wholesale with the delimited list.
# Source: Delimitation Commission notification, in force 20 May 2022.
JK_DISTRICTS = [
    "Kupwara", "Baramulla", "Bandipora", "Ganderbal", "Srinagar", "Budgam",
    "Pulwama", "Shopian", "Kulgam", "Anantnag", "Kishtwar", "Doda", "Ramban",
    "Reasi", "Udhampur", "Kathua", "Samba", "Jammu", "Rajouri", "Poonch",
]

# (AC number, name, district, category)
JK_ASSEMBLY = [
    (1, "Karnah", "Kupwara", "GEN"), (2, "Trehgam", "Kupwara", "GEN"),
    (3, "Kupwara", "Kupwara", "GEN"), (4, "Lolab", "Kupwara", "GEN"),
    (5, "Handwara", "Kupwara", "GEN"), (6, "Langate", "Kupwara", "GEN"),
    (7, "Sopore", "Baramulla", "GEN"), (8, "Rafiabad", "Baramulla", "GEN"),
    (9, "Uri", "Baramulla", "GEN"), (10, "Baramulla", "Baramulla", "GEN"),
    (11, "Gulmarg", "Baramulla", "GEN"), (12, "Wagoora-Kreeri", "Baramulla", "GEN"),
    (13, "Pattan", "Baramulla", "GEN"), (14, "Sonawari", "Bandipora", "GEN"),
    (15, "Bandipora", "Bandipora", "GEN"), (16, "Gurez", "Bandipora", "ST"),
    (17, "Kangan", "Ganderbal", "ST"), (18, "Ganderbal", "Ganderbal", "GEN"),
    (19, "Hazratbal", "Srinagar", "GEN"), (20, "Khanyar", "Srinagar", "GEN"),
    (21, "Habba Kadal", "Srinagar", "GEN"), (22, "Lal Chowk", "Srinagar", "GEN"),
    (23, "Chanapora", "Srinagar", "GEN"), (24, "Zadibal", "Srinagar", "GEN"),
    (25, "Eidgah", "Srinagar", "GEN"), (26, "Central Shalteng", "Srinagar", "GEN"),
    (27, "Budgam", "Budgam", "GEN"), (28, "Beerwah", "Budgam", "GEN"),
    (29, "Khan Sahib", "Budgam", "GEN"), (30, "Chrar-i-Sharief", "Budgam", "GEN"),
    (31, "Chadoora", "Budgam", "GEN"), (32, "Pampore", "Pulwama", "GEN"),
    (33, "Tral", "Pulwama", "GEN"), (34, "Pulwama", "Pulwama", "GEN"),
    (35, "Rajpora", "Pulwama", "GEN"), (36, "Zainapora", "Shopian", "GEN"),
    (37, "Shopian", "Shopian", "GEN"), (38, "D. H. Pora", "Kulgam", "GEN"),
    (39, "Kulgam", "Kulgam", "GEN"), (40, "Devsar", "Kulgam", "GEN"),
    (41, "Dooru", "Anantnag", "GEN"), (42, "Kokernag", "Anantnag", "ST"),
    (43, "Anantnag West", "Anantnag", "GEN"), (44, "Anantnag", "Anantnag", "GEN"),
    (45, "Srigufwara-Bijbehara", "Anantnag", "GEN"),
    (46, "Shangus-Anantnag East", "Anantnag", "GEN"),
    (47, "Pahalgam", "Anantnag", "GEN"), (48, "Inderwal", "Kishtwar", "GEN"),
    (49, "Kishtwar", "Kishtwar", "GEN"), (50, "Padder-Nagseni", "Kishtwar", "GEN"),
    (51, "Bhadarwah", "Doda", "GEN"), (52, "Doda", "Doda", "GEN"),
    (53, "Doda West", "Doda", "GEN"), (54, "Ramban", "Ramban", "GEN"),
    (55, "Banihal", "Ramban", "GEN"), (56, "Gulabgarh", "Reasi", "ST"),
    (57, "Reasi", "Reasi", "GEN"), (58, "Shri Mata Vaishno Devi", "Reasi", "GEN"),
    (59, "Udhampur West", "Udhampur", "GEN"), (60, "Udhampur East", "Udhampur", "GEN"),
    (61, "Chenani", "Udhampur", "GEN"), (62, "Ramnagar", "Udhampur", "SC"),
    (63, "Bani", "Kathua", "GEN"), (64, "Billawar", "Kathua", "GEN"),
    (65, "Basohli", "Kathua", "GEN"), (66, "Jasrota", "Kathua", "GEN"),
    (67, "Kathua", "Kathua", "SC"), (68, "Hiranagar", "Kathua", "GEN"),
    (69, "Ramgarh", "Samba", "SC"), (70, "Samba", "Samba", "GEN"),
    (71, "Vijaypur", "Samba", "GEN"), (72, "Bishnah", "Jammu", "SC"),
    (73, "Suchetgarh", "Jammu", "SC"), (74, "R. S. Pura-Jammu South", "Jammu", "GEN"),
    (75, "Bahu", "Jammu", "GEN"), (76, "Jammu East", "Jammu", "GEN"),
    (77, "Nagrota", "Jammu", "GEN"), (78, "Jammu West", "Jammu", "GEN"),
    (79, "Jammu North", "Jammu", "GEN"), (80, "Marh", "Jammu", "SC"),
    (81, "Akhnoor", "Jammu", "SC"), (82, "Chhamb", "Jammu", "GEN"),
    (83, "Kalakote-Sunderbani", "Rajouri", "GEN"), (84, "Nowshera", "Rajouri", "GEN"),
    (85, "Rajouri", "Rajouri", "ST"), (86, "Budhal", "Rajouri", "ST"),
    (87, "Thannamandi", "Rajouri", "ST"), (88, "Surankote", "Poonch", "ST"),
    (89, "Poonch Haveli", "Poonch", "GEN"), (90, "Mendhar", "Poonch", "ST"),
]


def sql(value):
    """Render a Python value as a SQL literal."""
    if value is None or value == "":
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("'", "''") + "'"


def normalise(name):
    name = re.sub(r"^\d+\s*-\s*", "", name)
    name = re.sub(r"\(\s*(SC|ST)\s*\)", "", name, flags=re.I)
    return re.sub(r"[^a-z]", "", name.lower())


def category_of(name):
    match = re.search(r"\(\s*(SC|ST)\s*\)", name, flags=re.I)
    return match.group(1).upper() if match else "GEN"


def clean_name(name):
    name = re.sub(r"^\d+\s*-\s*", "", name)
    name = re.sub(r"\s*\(\s*(SC|ST)\s*\)\s*$", "", name, flags=re.I)
    return name.strip()


# ── Load districts and the ECI assembly list ────────────────────────────────
eci_districts = {}          # (state_code, district_code) -> raw name
with open("districts.tsv", encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 3:
            eci_districts[(parts[0], parts[1])] = parts[2]

eci_state_names = {}
with open("states.tsv", encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 2:
            eci_state_names[parts[0]] = parts[1]

# Normalised AC name within a state -> the district(s) bearing that name.
#
# A handful of names genuinely repeat inside one state: Tamil Nadu has two
# Tiruppattur seats, Gujarat two Mandvi, Bihar two Kalyanpur. Matching by name alone
# cannot tell them apart, so where a name is ambiguous the district is left unset
# rather than guessed. Guessing would silently file a seat under the wrong district.
_ac_district_candidates = defaultdict(set)
with open("assembly-constituencies.tsv", encoding="utf-8") as fh:
    for line in fh:
        parts = line.rstrip("\n").split("\t")
        if len(parts) >= 4:
            state_code, district_code, _code, ac_name = parts[:4]
            state_id = ECI_STATE_TO_ID.get(eci_state_names.get(state_code, ""))
            if state_id:
                _ac_district_candidates[(state_id, normalise(ac_name))].add(
                    (state_code, district_code))

ac_to_district = {key: next(iter(values))
                  for key, values in _ac_district_candidates.items()
                  if len(values) == 1}
AMBIGUOUS_AC_NAMES = {key for key, values in _ac_district_candidates.items()
                      if len(values) > 1}

# ── Assign district ids ─────────────────────────────────────────────────────
district_id = {}            # (state_code, district_code) -> new id
district_rows = []
next_id = 1
for key in sorted(eci_districts, key=lambda k: (k[0], int(k[1]) if k[1].isdigit() else 0)):
    state_id = ECI_STATE_TO_ID.get(eci_state_names.get(key[0], ""))
    # J&K districts come from the 2022 delimitation instead; the snapshot's list
    # still includes Leh and Kargil, which moved to Ladakh in 2019.
    if not state_id or state_id == 33:
        continue
    name = clean_name(eci_districts[key])
    district_id[key] = next_id
    district_rows.append((next_id, name, state_id))
    next_id += 1

named_district_ids = {}     # (state_id, district name) -> new id
for state_id, name in EXTRA_DISTRICTS:
    district_rows.append((next_id, name, state_id))
    named_district_ids[(state_id, name)] = next_id
    next_id += 1

for name in JK_DISTRICTS:
    district_rows.append((next_id, name, 33))
    named_district_ids[(33, name)] = next_id
    next_id += 1

district_name_by_id = {row[0]: row[1] for row in district_rows}
district_lookup = {(state_id, normalise(name)): did
                   for did, name, state_id in district_rows}

# ── Load the LGD spine ──────────────────────────────────────────────────────
pc_source = []
with open("parliament_constituencies.csv", encoding="utf-8-sig") as fh:
    for row in csv.DictReader(fh):
        state_id = LGD_STATE_TO_ID.get(row["State Name"])
        if state_id:
            pc_source.append((state_id, int(row["Parliament Constituency Code"]),
                              row["Parliament Constituency Name"]))

ac_source = []
segments = []               # (pc_code, ac_code) pairs, by LGD code
with open("assembly_constituencies.csv", encoding="utf-8-sig") as fh:
    for row in csv.DictReader(fh):
        if not row["Assembly Constituency Name"]:
            continue
        state_id = LGD_STATE_TO_ID.get(row["State Name"])
        if not state_id or state_id in NO_ASSEMBLY_STATE_IDS:
            continue
        # J&K's assembly constituencies are replaced by the delimited list, so the
        # superseded rows and their segment links are skipped here.
        if state_id == 33:
            continue
        ac_code = int(row["Assembly Constituency Code"])
        pc_code = int(row["Parliament Constituency Code"])
        ac_source.append((state_id, ac_code, row["Assembly Constituency Name"], pc_code))
        segments.append((pc_code, ac_code))

# ── Allocate constituency ids: LS 1..543, VS from 1001 ──────────────────────
ls_id_by_code = {}
ls_rows = []
by_state_counter = Counter()
for state_id, pc_code, name in sorted(pc_source, key=lambda r: (r[0], r[1])):
    by_state_counter[state_id] += 1
    new_id = len(ls_rows) + 1
    ls_id_by_code[pc_code] = new_id
    ls_rows.append((new_id, clean_name(name), "LS", by_state_counter[state_id],
                    state_id, category_of(name)))

vs_id_by_code = {}
vs_rows = []
by_state_counter = Counter()
matched_district = 0
for state_id, ac_code, name, _pc_code in sorted(ac_source, key=lambda r: (r[0], r[1])):
    by_state_counter[state_id] += 1
    new_id = 1000 + len(vs_rows) + 1
    vs_id_by_code[ac_code] = new_id

    key = ac_to_district.get((state_id, normalise(name)))
    d_id = district_id.get(key) if key else None
    if d_id:
        matched_district += 1

    vs_rows.append((new_id, clean_name(name), "VS", by_state_counter[state_id],
                    state_id, category_of(name), d_id,
                    district_name_by_id.get(d_id)))

# Sikkim's Sangha seat, absent from the snapshot.
sangha_name, sangha_district, sangha_category = SIKKIM_SANGHA
sangha_district_id = district_lookup.get((22, normalise(sangha_district)))
by_state_counter[22] += 1
vs_rows.append((1000 + len(vs_rows) + 1, sangha_name, "VS", by_state_counter[22],
                22, sangha_category, sangha_district_id,
                district_name_by_id.get(sangha_district_id)))
if sangha_district_id:
    matched_district += 1

# Jammu & Kashmir, from the 2022 delimitation.
for number, name, district_name, category in JK_ASSEMBLY:
    d_id = district_lookup.get((33, normalise(district_name)))
    vs_rows.append((1000 + len(vs_rows) + 1, name, "VS", number, 33, category,
                    d_id, district_name_by_id.get(d_id)))
    if d_id:
        matched_district += 1

# A parliamentary constituency spans several assembly segments, so its district is
# recorded as the district holding the most of those segments.
pc_district_votes = defaultdict(Counter)
vs_district_by_id = {row[0]: row[6] for row in vs_rows}
for pc_code, ac_code in segments:
    vs_id = vs_id_by_code.get(ac_code)
    d_id = vs_district_by_id.get(vs_id) if vs_id else None
    if d_id:
        pc_district_votes[pc_code][d_id] += 1

ls_rows_final = []
ls_with_district = 0
for new_id, name, kind, number, state_id, category in ls_rows:
    pc_code = next(code for code, mapped in ls_id_by_code.items() if mapped == new_id)
    votes = pc_district_votes.get(pc_code)
    d_id = votes.most_common(1)[0][0] if votes else None
    if d_id:
        ls_with_district += 1
    ls_rows_final.append((new_id, name, kind, number, state_id, category, d_id,
                          district_name_by_id.get(d_id)))

# ── Emit ────────────────────────────────────────────────────────────────────
def batched_insert(out, table, columns, rows, batch=200):
    for start in range(0, len(rows), batch):
        chunk = rows[start:start + batch]
        out.append(f"INSERT INTO public.{table} ({', '.join(columns)}) VALUES")
        out.append(",\n".join("  (" + ", ".join(sql(v) for v in row) + ")" for row in chunk) + ";")
        out.append("")


out = []
out.append(f"""-- ============================================================================
-- V4 — Real electoral geography: states, districts, and all PM/CM constituencies
-- ============================================================================
-- Replaces the synthetic constituency data seeded in V1, which was unusable:
--
--   * 3,607 assembly constituencies against 4,120 that actually exist, with four
--     states/UTs (West Bengal, Jammu & Kashmir, Delhi, Puducherry) having none at
--     all — so a voter there could not be registered for a CM election.
--   * 157 groups of duplicated constituency names, used as padding. Maharashtra
--     listed "Nawapur", "Tirora", "Arvi" and others twice, which is why several
--     states appeared to have MORE seats than exist (Maharashtra 337 vs 288).
--   * One Lok Sabha seat misfiled: Uttar Pradesh had 79 of its 80, Sikkim had 2
--     of its 1.
--   * No district linkage at all: district_id was NULL on every row.
--
-- Sources
-- -------
-- Constituencies: Local Government Directory (lgdirectory.gov.in), as of March
-- 2024 — {len(ls_rows_final)} parliamentary and {len(vs_rows)} assembly constituencies, plus the
-- real parliamentary-to-assembly segment mapping.
--
-- Districts: Election Commission directory — {len(district_rows)} districts with their
-- assembly constituencies. Leh and Kargil are added by hand because Ladakh was
-- created in 2019, after that list was compiled.
--
-- A note on "district-wise" parliamentary constituencies
-- -----------------------------------------------------
-- An assembly constituency sits inside exactly one district, so that hierarchy is
-- clean. A parliamentary constituency does not: it is built from roughly seven
-- assembly segments which frequently cross district boundaries. Its district_id
-- here is therefore the district holding the majority of its segments, which is
-- useful for narrowing a dropdown but is not a statement that the seat lies wholly
-- within that district. The authoritative relationship is preserved in the new
-- constituency_segments table, from which a seat's true district span can be
-- derived.
--
-- Scope: PM (Lok Sabha) and CM (Vidhan Sabha) only, matching V3.
-- ============================================================================


-- ---------------------------------------------------------------------------
-- 1. Release foreign keys held by the archived pre-V3 vote table
-- ---------------------------------------------------------------------------
-- An archive must not prevent the live geography from being corrected.
ALTER TABLE public.vote_archive_v2 DROP CONSTRAINT IF EXISTS vote_constituency_id_fkey;
ALTER TABLE public.vote_archive_v2 DROP CONSTRAINT IF EXISTS vote_candidate_id_fkey;


-- ---------------------------------------------------------------------------
-- 2. Detach every reference to the old geography
-- ---------------------------------------------------------------------------
UPDATE public.voters SET
    ls_constituency_id = NULL,
    vs_constituency_id = NULL,
    ward_id            = NULL,
    ward_local_id      = NULL,
    council_id         = NULL,
    panchayat_id       = NULL,
    municipality_tier  = NULL,
    municipality_ward  = NULL;

-- The seeded candidates were two synthetic entries per synthetic constituency.
-- Real candidates are entered through the admin dashboard.
DELETE FROM public.candidate;

-- Local-body tables belong to the municipality feature removed in V3.
DELETE FROM public.wards_local;
DELETE FROM public.wards;
DELETE FROM public.panchayats;
DELETE FROM public.councils;

DELETE FROM public.constituencies;
DELETE FROM public.districts;


-- ---------------------------------------------------------------------------
-- 3. Districts ({len(district_rows)})
-- ---------------------------------------------------------------------------""")

batched_insert(out, "districts", ["id", "name", "state_id"], district_rows)

out.append(f"""-- ---------------------------------------------------------------------------
-- 4. PM constituencies — Lok Sabha ({len(ls_rows_final)})
-- ---------------------------------------------------------------------------
-- number is the seat's ordinal within its state.""")
batched_insert(out, "constituencies",
               ["id", "name", "type", "number", "state_id", "category",
                "district_id", "district_name"],
               ls_rows_final)

out.append(f"""-- ---------------------------------------------------------------------------
-- 5. CM constituencies — Vidhan Sabha ({len(vs_rows)})
-- ---------------------------------------------------------------------------""")
batched_insert(out, "constituencies",
               ["id", "name", "type", "number", "state_id", "category",
                "district_id", "district_name"],
               vs_rows)

# ── Segment mapping ─────────────────────────────────────────────────────────
segment_rows = []
for pc_code, ac_code in segments:
    ls = ls_id_by_code.get(pc_code)
    vs = vs_id_by_code.get(ac_code)
    if ls and vs:
        segment_rows.append((ls, vs))
segment_rows = sorted(set(segment_rows))

out.append(f"""-- ---------------------------------------------------------------------------
-- 6. Which assembly segments make up each parliamentary seat ({len(segment_rows)})
-- ---------------------------------------------------------------------------
-- The authoritative relationship between the two constituency types. It records
-- what a single district_id on a Lok Sabha seat cannot: that a seat spans several
-- assembly constituencies, and therefore often several districts.
CREATE TABLE public.constituency_segments (
    ls_constituency_id integer NOT NULL,
    vs_constituency_id integer NOT NULL,
    CONSTRAINT constituency_segments_pkey PRIMARY KEY (ls_constituency_id, vs_constituency_id),
    CONSTRAINT constituency_segments_ls_fkey FOREIGN KEY (ls_constituency_id)
        REFERENCES public.constituencies (id) ON DELETE CASCADE,
    CONSTRAINT constituency_segments_vs_fkey FOREIGN KEY (vs_constituency_id)
        REFERENCES public.constituencies (id) ON DELETE CASCADE
);

CREATE INDEX idx_constituency_segments_vs ON public.constituency_segments (vs_constituency_id);

COMMENT ON TABLE public.constituency_segments IS
    'Assembly segments of each Lok Sabha seat, from the Local Government Directory. '
    'Lets a voter''s Lok Sabha constituency be derived from their assembly constituency '
    'instead of being captured separately, which removes the chance of the two '
    'disagreeing.';
""")
batched_insert(out, "constituency_segments",
               ["ls_constituency_id", "vs_constituency_id"], segment_rows, batch=400)

out.append("""-- ---------------------------------------------------------------------------
-- 7. Point the demonstration voters at real constituencies
-- ---------------------------------------------------------------------------
-- V001..V010 were registered against synthetic Tamil Nadu constituencies that no
-- longer exist.
--
-- The assembly seat is chosen by name, and the Lok Sabha seat is then derived from
-- constituency_segments rather than matched by name separately. That guarantees the
-- two are consistent: the voter's assembly seat really is a segment of their
-- parliamentary seat. Matching both by name independently is what would allow them
-- to disagree, and a name that fails to match would silently fall back to an
-- unrelated seat.
UPDATE public.voters SET
    state_id = 23,
    vs_constituency_id = COALESCE(
        (SELECT id FROM public.constituencies
          WHERE type = 'VS' AND state_id = 23 AND name ILIKE '%radhakrishnan%'
          ORDER BY id LIMIT 1),
        (SELECT id FROM public.constituencies
          WHERE type = 'VS' AND state_id = 23 ORDER BY number LIMIT 1))
WHERE voter_id LIKE 'V0%';

UPDATE public.voters v SET
    ls_constituency_id = COALESCE(
        (SELECT seg.ls_constituency_id
           FROM public.constituency_segments seg
          WHERE seg.vs_constituency_id = v.vs_constituency_id
          ORDER BY seg.ls_constituency_id LIMIT 1),
        (SELECT id FROM public.constituencies
          WHERE type = 'LS' AND state_id = 23 ORDER BY number LIMIT 1))
WHERE v.voter_id LIKE 'V0%';

-- Two candidates on each ballot the demonstration voters will actually be shown, so
-- the end-to-end voter journey works out of the box for both election types. Derived
-- from the voters table rather than from constituency names, so this cannot silently
-- populate the wrong ballot. Every other constituency starts empty, to be filled from
-- the admin dashboard.
INSERT INTO public.candidate (name, party, election_id, state_id, constituency_id, party_color)
SELECT c.name || ' - ' || x.label, x.party, e.id, c.state_id, c.id, x.colour
FROM   public.elections e
JOIN   public.constituencies c
       ON (e.type = 'PM' AND c.type = 'LS'
           AND c.id IN (SELECT DISTINCT ls_constituency_id FROM public.voters
                         WHERE ls_constituency_id IS NOT NULL))
       OR (e.type = 'CM' AND c.type = 'VS'
           AND c.id IN (SELECT DISTINCT vs_constituency_id FROM public.voters
                         WHERE vs_constituency_id IS NOT NULL))
CROSS  JOIN (VALUES ('Candidate A', 'National Party',   '#FF9933'),
                    ('Candidate B', 'Opposition Party', '#138808'))
            AS x(label, party, colour)
WHERE  e.type IN ('PM', 'CM');


-- ---------------------------------------------------------------------------
-- 8. Realign sequences with the explicit ids inserted above
-- ---------------------------------------------------------------------------
SELECT setval('public.districts_id_seq',      (SELECT COALESCE(MAX(id), 1) FROM public.districts));
SELECT setval('public.constituencies_id_seq', (SELECT COALESCE(MAX(id), 1) FROM public.constituencies));
SELECT setval('public.candidate_id_seq',      (SELECT COALESCE(MAX(id), 1) FROM public.candidate));
SELECT setval('public.councils_id_seq',       1);
SELECT setval('public.panchayats_id_seq',     1);
SELECT setval('public.wards_id_seq',          1);
SELECT setval('public.wards_local_id_seq',    1);
""")

with open(OUT, "w", encoding="utf-8", newline="\n") as fh:
    fh.write("\n".join(out))

# ── Report ─────────────────────────────────────────────────────────────────
print(f"districts            : {len(district_rows)}")
print(f"LS constituencies    : {len(ls_rows_final)}  (district attributed: {ls_with_district})")
print(f"VS constituencies    : {len(vs_rows)}  (district attributed: {matched_district}"
      f" = {matched_district * 100.0 / len(vs_rows):.1f}%)")
print(f"PC<->AC segments     : {len(segment_rows)}")
print(f"ambiguous AC names left without a district: {len(AMBIGUOUS_AC_NAMES)}")
print(f"written              : {OUT}")

official = {
    1: (25, 175), 2: (2, 60), 3: (14, 126), 4: (40, 243), 5: (11, 90), 6: (2, 40),
    7: (26, 182), 8: (10, 90), 9: (4, 68), 10: (14, 81), 11: (28, 224), 12: (20, 140),
    13: (29, 230), 14: (48, 288), 15: (2, 60), 16: (2, 60), 17: (1, 40), 18: (1, 60),
    19: (21, 147), 20: (13, 117), 21: (25, 200), 22: (1, 32), 23: (39, 234),
    24: (17, 119), 25: (2, 60), 26: (80, 403), 27: (5, 70), 28: (42, 294),
    29: (1, 0), 30: (1, 0), 31: (2, 0), 32: (7, 70), 33: (5, 90), 34: (1, 0),
    35: (1, 0), 36: (1, 30),
}
ls_count = Counter(r[4] for r in ls_rows_final)
vs_count = Counter(r[4] for r in vs_rows)
print("\nper-state check against official seat counts (differences only):")
issues = 0
for state_id in sorted(official):
    want_ls, want_vs = official[state_id]
    got_ls, got_vs = ls_count.get(state_id, 0), vs_count.get(state_id, 0)
    if (got_ls, got_vs) != (want_ls, want_vs):
        issues += 1
        print(f"  state {state_id:>2}: LS {got_ls}/{want_ls}   VS {got_vs}/{want_vs}")
print("  all states match" if not issues else f"  {issues} state(s) differ")
