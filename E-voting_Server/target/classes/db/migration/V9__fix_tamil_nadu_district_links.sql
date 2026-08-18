-- ============================================================================
-- V9 — Fix wrong/missing district links for Tamil Nadu constituencies
-- ============================================================================
-- V4 seeded a parliamentary (LS) seat's district as "whichever district holds
-- the majority of its assembly segments" (see V4's own header comment). Two
-- Tamil Nadu bugs came out of that seeding:
--
--   1. Erode (LS id 350) was linked to TIRUPPUR (481) instead of ERODE (460).
--      Its six real segments (Kumarapalayam, Erode East, Erode West,
--      Modakkurichi, Dharapuram, Kangayam) split 3 Erode / 2 Tiruppur / 1
--      Namakkal — Erode wins the majority, not Tiruppur. Cross-checked
--      against constituency_segments plus the Wikipedia/ECI seat pages for
--      both Erode and Tiruppur Lok Sabha constituencies.
--
--      (Tiruppur, LS id 373, was already correctly linked to ERODE — its
--      six real segments are 4 Erode-district / 2 Tiruppur-district, which
--      does check out despite the name. That row needs no change.)
--
--   2. Ten Tamil Nadu assembly (VS) seats were seeded with a NULL district —
--      one of them, Viluppuram itself, is the seat the user reported as
--      missing/mismatched. district_id was NULL on all ten, so nothing
--      "showed as" a specific wrong district in the UI; they just fell
--      through COALESCE(d.name, c.district_name, '') to a blank district in
--      every dropdown and lookup that groups by district. One of the ten
--      (Modakurichi) is also why bug #1 above wasn't a clean 3-2 majority in
--      the stored data — it was sitting out of the count entirely.
--
-- Every value below was checked two ways: against the seat's real district
-- per Wikipedia/ECI, and against the district of its immediate assembly-
-- number neighbours in this table (TN's AC numbers 1-234 run in contiguous
-- per-district blocks here, which is a strong independent check — e.g.
-- Modakurichi, #66, sits between Kumarapalayam #65 and Attur #67 in the
-- source data only because #65 and #67 land either side of the Erode block;
-- Erode's own #62-63 confirm the block itself).
--
-- Scope note: this migration only covers Tamil Nadu, the state the user
-- flagged. The same NULL-district gap exists in ~300 assembly seats across
-- most other states (largest: Madhya Pradesh, Gujarat, West Bengal,
-- Jharkhand, Karnataka, Uttar Pradesh) — each of those would need the same
-- per-seat verification against an authoritative source before being fixed,
-- which is future work, not something to guess at here.
-- ============================================================================

-- Bug 1: Erode LS seat was linked to Tiruppur district.
UPDATE public.constituencies
SET district_id = 460, district_name = 'ERODE'
WHERE id = 350 AND name = 'Erode' AND type = 'LS';

-- Bug 2: ten Tamil Nadu VS seats had no district at all.
UPDATE public.constituencies SET district_id = 451, district_name = 'CHENNAI'
WHERE id = 3776 AND name = 'Tiruvottiyur' AND type = 'VS';

UPDATE public.constituencies SET district_id = 452, district_name = 'KANCHEEPURAM'
WHERE id = 3779 AND name = 'Sholinganallur' AND type = 'VS';

UPDATE public.constituencies SET district_id = 467, district_name = 'CUDDALORE'
WHERE id = 3800 AND name = 'Vriddhachalam' AND type = 'VS';

UPDATE public.constituencies SET district_id = 455, district_name = 'DHARMAPURI'
WHERE id = 3804 AND name = 'Palacodu' AND type = 'VS';

UPDATE public.constituencies SET district_id = 460, district_name = 'ERODE'
WHERE id = 3818 AND name = 'Modakurichi' AND type = 'VS';

UPDATE public.constituencies SET district_id = 470, district_name = 'THANJAVUR'
WHERE id = 3922 AND name = 'Orattanadu' AND type = 'VS';

UPDATE public.constituencies SET district_id = 474, district_name = 'THENI'
WHERE id = 3928 AND name = 'Bodinayackanur' AND type = 'VS';

UPDATE public.constituencies SET district_id = 471, district_name = 'PUDUKKOTTAI'
WHERE id = 3939 AND name = 'Gandarvakottai' AND type = 'VS';

UPDATE public.constituencies SET district_id = 457, district_name = 'VILLUPURAM'
WHERE id = 3975 AND name = 'Thirukoilur' AND type = 'VS';

UPDATE public.constituencies SET district_id = 457, district_name = 'VILLUPURAM'
WHERE id = 3980 AND name = 'Viluppuram' AND type = 'VS';
