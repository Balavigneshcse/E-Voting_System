-- ============================================================================
-- V11 — Fix missing district links for Madhya Pradesh constituencies
-- ============================================================================
-- The same seeding gap V9 fixed for Tamil Nadu (see that file's header) also
-- affects 32 Madhya Pradesh assembly seats, seeded with a NULL district. This
-- is the largest single-state share of the ~300-row nationwide gap noted when
-- V9 was written.
--
-- Each of the 32 below was checked against an authoritative, unambiguous
-- source per seat — each seat's own Wikipedia infobox, an official
-- district-constituency listing (e.g. a District Election Office page), or
-- both — not against MP's own "list of constituencies" Wikipedia page, whose
-- rowspan-merged district/Lok-Sabha-constituency columns collapse to
-- ambiguous plain text once flattened and produced two wrong first guesses
-- during verification here (Dhauhani was initially misread as Singrauli
-- district; it is Sidhi. Alot was initially misread as Ujjain district,
-- which is actually only its Lok Sabha constituency; the assembly seat
-- itself is in Ratlam district). Both were caught by cross-checking against
-- a second, unambiguous source before writing anything — a reminder that a
-- merged-cell table flattened to plain text can silently swap which column a
-- value belongs to, and is not trustworthy on its own for exactly the kind
-- of fix this migration makes.
--
-- Scope note, unchanged from V9: this covers only Madhya Pradesh. Roughly
-- 270 assembly seats across other states have the same NULL-district gap —
-- Gujarat (31), West Bengal (28), Jharkhand (24), Karnataka (24) and Uttar
-- Pradesh (24) are the next largest. Each still needs the same per-seat
-- verification before being fixed.
-- ============================================================================

UPDATE public.constituencies SET district_id = 262, district_name = 'Betul'
WHERE id = 2531 AND name = 'Ghoradongri' AND type = 'VS';

UPDATE public.constituencies SET district_id = 267, district_name = 'Bhopal'
WHERE id = 2545 AND name = 'Bhopal Dakshin- paschim' AND type = 'VS';

UPDATE public.constituencies SET district_id = 261, district_name = 'Chhindwara'
WHERE id = 2557 AND name = 'Parasia' AND type = 'VS';

UPDATE public.constituencies SET district_id = 261, district_name = 'Chhindwara'
WHERE id = 2558 AND name = 'Saunsar' AND type = 'VS';

UPDATE public.constituencies SET district_id = 237, district_name = 'Gwalior'
WHERE id = 2592 AND name = 'Dabra' AND type = 'VS';

UPDATE public.constituencies SET district_id = 255, district_name = 'Jabalpur'
WHERE id = 2617 AND name = 'Jabalpur Paschim' AND type = 'VS';

UPDATE public.constituencies SET district_id = 255, district_name = 'Jabalpur'
WHERE id = 2618 AND name = 'Jabalpur Purba' AND type = 'VS';

UPDATE public.constituencies SET district_id = 255, district_name = 'Jabalpur'
WHERE id = 2619 AND name = 'Jabalpur Uttar' AND type = 'VS';

UPDATE public.constituencies SET district_id = 246, district_name = 'Panna'
WHERE id = 2625 AND name = 'Gunnaor' AND type = 'VS';

UPDATE public.constituencies SET district_id = 254, district_name = 'Katni'
WHERE id = 2626 AND name = 'Murwara' AND type = 'VS';

UPDATE public.constituencies SET district_id = 274, district_name = 'Khargone'
WHERE id = 2631 AND name = 'Badwah' AND type = 'VS';

UPDATE public.constituencies SET district_id = 271, district_name = 'Dewas'
WHERE id = 2632 AND name = 'Bagali' AND type = 'VS';

UPDATE public.constituencies SET district_id = 275, district_name = 'Barwani'
WHERE id = 2639 AND name = 'Badwani' AND type = 'VS';

UPDATE public.constituencies SET district_id = 275, district_name = 'Barwani'
WHERE id = 2644 AND name = 'Pansemal' AND type = 'VS';

UPDATE public.constituencies SET district_id = 275, district_name = 'Barwani'
WHERE id = 2645 AND name = 'Rajpur' AND type = 'VS';

UPDATE public.constituencies SET district_id = 275, district_name = 'Barwani'
WHERE id = 2646 AND name = 'Sendhawa' AND type = 'VS';

UPDATE public.constituencies SET district_id = 259, district_name = 'Seoni'
WHERE id = 2651 AND name = 'Lakhnadon' AND type = 'VS';

UPDATE public.constituencies SET district_id = 235, district_name = 'Morena'
WHERE id = 2665 AND name = 'Joura' AND type = 'VS';

UPDATE public.constituencies SET district_id = 235, district_name = 'Morena'
WHERE id = 2667 AND name = 'Sabalgarh' AND type = 'VS';

UPDATE public.constituencies SET district_id = 235, district_name = 'Morena'
WHERE id = 2669 AND name = 'Sumawali' AND type = 'VS';

UPDATE public.constituencies SET district_id = 240, district_name = 'Guna'
WHERE id = 2672 AND name = 'Chachoura' AND type = 'VS';

UPDATE public.constituencies SET district_id = 242, district_name = 'Sagar'
WHERE id = 2698 AND name = 'Naryoli' AND type = 'VS';

UPDATE public.constituencies SET district_id = 254, district_name = 'Katni'
WHERE id = 2712 AND name = 'Barwara' AND type = 'VS';

UPDATE public.constituencies SET district_id = 252, district_name = 'Anuppur'
WHERE id = 2715 AND name = 'Kotma' AND type = 'VS';

UPDATE public.constituencies SET district_id = 253, district_name = 'Umaria'
WHERE id = 2716 AND name = 'Manpur' AND type = 'VS';

UPDATE public.constituencies SET district_id = 252, district_name = 'Anuppur'
WHERE id = 2717 AND name = 'Pushprajgarh' AND type = 'VS';

UPDATE public.constituencies SET district_id = 249, district_name = 'Sidhi'
WHERE id = 2722 AND name = 'Dhauhani' AND type = 'VS';

UPDATE public.constituencies SET district_id = 250, district_name = 'Singrauli'
WHERE id = 2725 AND name = 'Singrauli' AND type = 'VS';

UPDATE public.constituencies SET district_id = 281, district_name = 'Ratlam'
WHERE id = 2734 AND name = 'Alot' AND type = 'VS';

UPDATE public.constituencies SET district_id = 280, district_name = 'Ujjain'
WHERE id = 2738 AND name = 'Nagada-khachrod' AND type = 'VS';

UPDATE public.constituencies SET district_id = 280, district_name = 'Ujjain'
WHERE id = 2740 AND name = 'Ujjain Dakshin' AND type = 'VS';

UPDATE public.constituencies SET district_id = 280, district_name = 'Ujjain'
WHERE id = 2741 AND name = 'Ujjain Uttar' AND type = 'VS';
