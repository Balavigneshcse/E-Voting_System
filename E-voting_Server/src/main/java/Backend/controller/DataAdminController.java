package Backend.controller;

import Backend.model.Candidate;
import Backend.model.Voter;
import Backend.repository.CandidateRepository;
import Backend.repository.VoterRepository;
import Backend.service.BiometricService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Data entry for the admin dashboard: voters, candidates and the geography lookups the
 * entry forms cascade through.
 *
 * <h2>Bugs this rewrite fixes</h2>
 * Registering a voter or a candidate could not previously succeed, because the raw SQL
 * addressed columns and tables that do not exist:
 * <ul>
 *   <li>{@code add-voter} inserted into {@code photo_base64} and
 *       {@code fingerprint_base64}. The actual columns are {@code photo} and
 *       {@code fingerprint_template}, both {@code bytea}.</li>
 *   <li>{@code add-candidate} inserted into a table called {@code candidates}. The table
 *       is {@code candidate}, singular, and its blob columns are {@code photo_data} and
 *       {@code symbol_data}.</li>
 *   <li>{@code /elections} selected a {@code status} column. The column is
 *       {@code is_active}, so the query always threw and the endpoint returned an empty
 *       list — which is why the election dropdown never populated.</li>
 * </ul>
 * Each failure was swallowed by a broad {@code catch}, so the only symptom was an empty
 * form or a generic error string.
 *
 * <p>Writes now go through JPA rather than hand-built SQL, so the entity mapping is the
 * single source of truth for column names.
 *
 * <h2>Eligibility</h2>
 * Both forms also collect a mobile number and date of birth ({@code V10}) and reject the
 * registration outright rather than saving an ineligible row: a voter must be at least 18
 * on the day of registration (Article 326) and a candidate — Lok Sabha or Vidhan Sabha
 * alike — must be at least 25 (Articles 84(b) / 173(b)). There's no separate "eligible"
 * flag to keep in sync; the row simply cannot exist if the person wasn't eligible when it
 * was created.
 *
 * <h2>Endpoints deliberately removed</h2>
 * The generic {@code insert}, {@code update} and {@code delete} handlers assumed every
 * table had {@code name}, {@code status} and {@code remarks} columns, which none of them
 * do, so they could only ever fail. The delete handler was also a way to remove states,
 * constituencies and elections that recorded votes refer to.
 */
@RestController
@RequestMapping("/admin/data")
public class DataAdminController {

    private static final Logger log = LoggerFactory.getLogger(DataAdminController.class);

    private final JdbcTemplate        jdbc;
    private final VoterRepository     voters;
    private final CandidateRepository candidates;
    private final BiometricService    biometrics;

    public DataAdminController(JdbcTemplate jdbc,
                               VoterRepository voters,
                               CandidateRepository candidates,
                               BiometricService biometrics) {
        this.jdbc       = jdbc;
        this.voters     = voters;
        this.candidates = candidates;
        this.biometrics = biometrics;
    }

    // ── Geography lookups for the cascading entry forms ─────────────────────

    @GetMapping("/states")
    public List<Map<String, Object>> states() {
        // Selected a non-existent state_code column before, which threw on every call and
        // was hidden by a broad catch that returned an empty list — leaving the state
        // dropdown permanently blank on the registration forms.
        return jdbc.queryForList(
                "SELECT id, name, name_ta, type, ls_seats, vs_seats FROM states ORDER BY name");
    }

    @GetMapping("/elections")
    public List<Map<String, Object>> elections() {
        return jdbc.queryForList("""
                SELECT id, name, name_ta, type, is_active, election_cycle
                FROM   elections
                WHERE  type IN ('PM', 'CM')
                ORDER  BY type, name
                """);
    }

    @GetMapping("/constituencies")
    public List<Map<String, Object>> constituencies(@RequestParam(required = false) Integer stateId,
                                                    @RequestParam(required = false) Integer districtId,
                                                    @RequestParam(required = false) String type) {
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.name, c.type, c.state_id, c.district_id,
                       COALESCE(d.name, c.district_name, '') AS district_name,
                       COALESCE(c.category, 'GEN') AS category
                FROM   constituencies c
                LEFT JOIN districts d ON d.id = c.district_id
                WHERE  1 = 1
                """);
        List<Object> params = new ArrayList<>();
        if (stateId != null) {
            sql.append(" AND c.state_id = ?");
            params.add(stateId);
        }
        if (districtId != null) {
            sql.append(" AND c.district_id = ?");
            params.add(districtId);
        }
        if (type != null && !type.isBlank()) {
            sql.append(" AND c.type = ?");
            params.add(type.trim().toUpperCase());
        }
        sql.append(" ORDER BY c.name");
        return jdbc.queryForList(sql.toString(), params.toArray());
    }

    @GetMapping("/districts")
    public List<Map<String, Object>> districts(@RequestParam Integer stateId) {
        return jdbc.queryForList(
                "SELECT id, name, state_id FROM districts WHERE state_id = ? ORDER BY name", stateId);
    }

    // ── Voter registration ──────────────────────────────────────────────────

    /**
     * Registers a voter.
     *
     * <p>Returns the voter's simulated fingerprint sample code alongside the NFC card ID.
     * Both stand for something physically carried on the voter's card, and the operator
     * needs the code to complete a simulated fingerprint scan at the terminal, exactly as
     * they need the card ID to complete a simulated tap.
     */
    @PostMapping("/add-voter")
    public ResponseEntity<Map<String, Object>> addVoter(@RequestBody Map<String, Object> body) {
        String name = text(body.get("name"));

        if (name == null) {
            return badRequest("name is required.");
        }

        Integer lsConstituency = number(body.get("lsConstituencyId"));
        Integer vsConstituency = number(body.get("vsConstituencyId"));
        if (lsConstituency == null && vsConstituency == null) {
            return badRequest(
                    "Assign at least one constituency. A voter with neither cannot be issued a "
                            + "ballot for a Lok Sabha or Vidhan Sabha election.");
        }

        String[] error = new String[1];
        String mobile = requireMobile(body.get("mobileNumber"), error);
        if (mobile == null) {
            return badRequest(error[0]);
        }
        LocalDate dateOfBirth = requireDateOfBirth(body.get("dateOfBirth"), error);
        if (dateOfBirth == null) {
            return badRequest(error[0]);
        }
        int age = ageOn(dateOfBirth, LocalDate.now());
        if (age < MIN_VOTER_AGE) {
            return badRequest("Not eligible to register: this voter is " + age + " years old. "
                    + "The minimum voting age in India is " + MIN_VOTER_AGE + ".");
        }

        Voter voter = new Voter();
        voter.setName(name);
        voter.setAadhaarNumber(text(body.get("aadhaarNumber")));
        voter.setMobileNumber(mobile);
        voter.setDateOfBirth(dateOfBirth);
        voter.setStateId(number(body.get("stateId")));
        voter.setLsConstituencyId(lsConstituency);
        voter.setVsConstituencyId(vsConstituency);
        voter.setCouncilId(number(body.get("councilId")));
        voter.setPanchayatId(number(body.get("panchayatId")));
        voter.setWardLocalId(number(body.get("wardId")));
        voter.setCardActive(true);

        byte[] photo = decodeBase64(body.get("photoBase64"));
        boolean photoSaved = photo != null;
        if (photoSaved) {
            voter.setPhoto(photo);
            voter.setPhotoType(mimeType(body.get("photoBase64"), body.get("photoType")));
        }

        String voterId = assignVoterIdAndSave(voter);
        if (voterId == null) {
            return badRequest("Could not assign a voter ID — another registration just took "
                    + "it. Please press Register again.");
        }

        // Enroll the simulated template now, so the terminal's fingerprint check has
        // something real to verify against on this voter's first visit.
        biometrics.ensureEnrolled(voter);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Voter registered.");
        response.put("voterId", voter.getVoterId());
        response.put("nfcCardId", voter.getNfcCardId());
        response.put("photoSaved", photoSaved);
        if (biometrics.simulationEnabled()) {
            response.put("simulatedFingerprintCode", biometrics.simulatedSampleCode(voterId));
            response.put("note", "Record the fingerprint code with the voter's card. "
                    + "It stands in for the scanner until the MFS100 is connected.");
        }
        return ResponseEntity.ok(response);
    }

    /**
     * Assigns the next {@code V<number>} voter ID and saves the voter under it. The unique
     * index on {@code voter_id} ({@code V5__voter_lookup_indexes.sql}) is what actually
     * prevents a collision landing in the database if two registrations somehow read the
     * same next-number at once — a genuine race on this admin-only, one-officer-at-a-time
     * endpoint is rare enough that asking the officer to press Register again is simpler
     * and more predictable than a hidden retry loop, which — done inside this same class —
     * would not actually get its own fresh transaction anyway: {@code @Transactional} is
     * proxy-based, and a self-invocation between two methods on the same bean bypasses the
     * proxy entirely. The NFC card ID is set to the same value as the voter ID: a voter's
     * card and their lookup ID are the same identifier here, so there is nothing separate
     * to assign.
     *
     * @return the assigned voter ID, or null if the id was taken by a concurrent
     *         registration between the read and the save
     */
    @Transactional
    protected String assignVoterIdAndSave(Voter voter) {
        String candidateId = nextVoterId();
        voter.setVoterId(candidateId);
        voter.setNfcCardId(candidateId);
        try {
            voters.save(voter);
            return candidateId;
        } catch (RuntimeException e) {
            log.warn("Voter ID {} was taken by a concurrent registration: {}",
                    candidateId, e.getMessage());
            return null;
        }
    }

    /** One past the highest existing {@code V<number>} id — ids outside that pattern (old
     *  manually-entered ones, test data) are ignored rather than breaking generation. */
    private String nextVoterId() {
        Integer next = jdbc.queryForObject("""
                SELECT COALESCE(MAX(CAST(SUBSTRING(voter_id FROM 2) AS INTEGER)), 0) + 1
                FROM   voters
                WHERE  voter_id ~ '^V[0-9]+$'
                """, Integer.class);
        return String.format("V%03d", next == null ? 1 : next);
    }

    // ── Candidate registration ──────────────────────────────────────────────

    @PostMapping("/add-candidate")
    @Transactional
    public ResponseEntity<Map<String, Object>> addCandidate(@RequestBody Map<String, Object> body) {
        String  name           = text(body.get("name"));
        Integer electionId     = number(body.get("electionId"));
        Integer constituencyId = number(body.get("constituencyId"));

        if (name == null || electionId == null || constituencyId == null) {
            return badRequest("name, electionId and constituencyId are all required.");
        }
        if ("NOTA".equalsIgnoreCase(name.trim())) {
            return badRequest("NOTA is added automatically for every constituency and does not "
                    + "need to be registered by hand.");
        }

        String[] error = new String[1];
        String mobile = requireMobile(body.get("mobileNumber"), error);
        if (mobile == null) {
            return badRequest(error[0]);
        }
        LocalDate dateOfBirth = requireDateOfBirth(body.get("dateOfBirth"), error);
        if (dateOfBirth == null) {
            return badRequest(error[0]);
        }
        int age = ageOn(dateOfBirth, LocalDate.now());
        if (age < MIN_CANDIDATE_AGE) {
            return badRequest("Not eligible to contest: this candidate is " + age + " years old. "
                    + "The minimum candidacy age for both Lok Sabha and Vidhan Sabha elections in "
                    + "India is " + MIN_CANDIDATE_AGE + ".");
        }

        // One button is always reserved for NOTA (added automatically below), so real
        // candidates fill at most MAX_BALLOT_SLOTS - 1 of the terminal's eight buttons.
        long existing = candidates.findByElectionIdAndConstituencyId(electionId, constituencyId).size();
        long realCandidateCapacity = Backend.service.VotingService.MAX_BALLOT_SLOTS - 1;
        long existingReal = existing - (hasNota(electionId, constituencyId) ? 1 : 0);
        if (existingReal >= realCandidateCapacity) {
            return badRequest("This constituency already has " + existingReal + " candidates. "
                    + "With NOTA reserving the last button, the terminal's eight buttons allow "
                    + "at most " + realCandidateCapacity + ".");
        }

        Candidate candidate = new Candidate();
        candidate.setName(name);
        candidate.setNameTa(text(body.get("nameTa")));
        candidate.setParty(text(body.get("party")));
        candidate.setPartyTa(text(body.get("partyTa")));
        candidate.setMobileNumber(mobile);
        candidate.setDateOfBirth(dateOfBirth);
        candidate.setElectionId(electionId);
        candidate.setStateId(number(body.get("stateId")));
        candidate.setConstituencyId(constituencyId);
        candidate.setPhotoUrl(text(body.get("photoUrl")));
        candidate.setSymbolUrl(text(body.get("symbolUrl")));

        byte[] photo = decodeBase64(body.get("photoBase64"));
        if (photo != null) {
            candidate.setPhotoData(photo);
            candidate.setPhotoType(mimeType(body.get("photoBase64"), body.get("photoType")));
        }
        byte[] symbol = decodeBase64(body.get("symbolBase64"));
        if (symbol != null) {
            candidate.setSymbolData(symbol);
            candidate.setSymbolType(mimeType(body.get("symbolBase64"), body.get("symbolType")));
        }

        candidates.save(candidate);
        boolean notaAdded = ensureNota(electionId, constituencyId, candidate.getStateId());

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Candidate added to ballot slot " + (existingReal + 1) + "."
                        + (notaAdded ? " NOTA was added automatically as the final option." : ""),
                "candidateId", "C" + candidate.getId(),
                "slotNumber", existingReal + 1));
    }

    /** Whether a NOTA row already exists for this constituency's ballot. */
    private boolean hasNota(Integer electionId, Integer constituencyId) {
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM candidate WHERE election_id = ? AND constituency_id = ? AND name = 'NOTA'",
                Integer.class, electionId, constituencyId);
        return count != null && count > 0;
    }

    /**
     * Adds NOTA for this constituency's ballot if it is not already there.
     *
     * <p>Every constituency that ever receives a real candidate gets NOTA — not every
     * constituency in the seed geography up front, most of which will never be contested in
     * this deployment. {@link CandidateRepository#findByElectionIdAndConstituencyId} always
     * sorts NOTA last regardless of its row id, so it stays the final button no matter when
     * it was inserted relative to the real candidates.
     *
     * @return true if NOTA was inserted just now, false if it already existed
     */
    private boolean ensureNota(Integer electionId, Integer constituencyId, Integer stateId) {
        if (hasNota(electionId, constituencyId)) {
            return false;
        }
        Candidate nota = new Candidate();
        nota.setName("NOTA");
        nota.setParty("None of the Above");
        nota.setElectionId(electionId);
        nota.setConstituencyId(constituencyId);
        nota.setStateId(stateId);
        try {
            candidates.save(nota);
            return true;
        } catch (RuntimeException e) {
            // Lost a race with a concurrent add-candidate request for the same constituency;
            // whichever one landed first already satisfies the invariant.
            log.debug("NOTA insert skipped for election {} constituency {}: {}",
                    electionId, constituencyId, e.getMessage());
            return false;
        }
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> listCandidates(@RequestParam Integer electionId,
                                                   @RequestParam(required = false) Integer constituencyId) {
        if (constituencyId == null) {
            return jdbc.queryForList("""
                    SELECT ca.id, ca.name, ca.party, ca.party_color, ca.constituency_id,
                           ca.mobile_number, ca.date_of_birth,
                           DATE_PART('year', AGE(ca.date_of_birth)) AS age,
                           c.name AS constituency_name
                    FROM   candidate ca
                    LEFT JOIN constituencies c ON c.id = ca.constituency_id
                    WHERE  ca.election_id = ?
                    ORDER  BY c.name, ca.name
                    """, electionId);
        }
        return jdbc.queryForList("""
                SELECT id, name, name_ta, party, party_ta, party_color, symbol_url,
                       mobile_number, date_of_birth,
                       DATE_PART('year', AGE(date_of_birth)) AS age
                FROM   candidate
                WHERE  election_id = ? AND constituency_id = ?
                ORDER  BY id
                """, electionId, constituencyId);
    }

    @GetMapping("/voters")
    public List<Map<String, Object>> listVoters(@RequestParam(required = false) Integer constituencyId) {
        if (constituencyId == null) {
            return jdbc.queryForList("""
                    SELECT voter_id, name, nfc_card_id, card_active, fingerprint_enrolled,
                           mobile_number, date_of_birth,
                           DATE_PART('year', AGE(date_of_birth)) AS age,
                           ls_constituency_id, vs_constituency_id
                    FROM   voters
                    ORDER  BY voter_id
                    LIMIT  500
                    """);
        }
        return jdbc.queryForList("""
                SELECT voter_id, name, nfc_card_id, card_active, fingerprint_enrolled,
                       mobile_number, date_of_birth,
                       DATE_PART('year', AGE(date_of_birth)) AS age,
                       ls_constituency_id, vs_constituency_id
                FROM   voters
                WHERE  vs_constituency_id = ? OR ls_constituency_id = ?
                ORDER  BY voter_id
                LIMIT  500
                """, constituencyId, constituencyId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static ResponseEntity<Map<String, Object>> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("success", false, "message", message));
    }

    private static String text(Object value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.toString().trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String textOr(Object value, String fallback) {
        String text = text(value);
        return text == null ? fallback : text;
    }

    private static Integer number(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            String trimmed = value.toString().trim();
            return trimmed.isEmpty() ? null : Integer.valueOf(trimmed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Accepts either a bare base64 string or a {@code data:} URL from a file input. Rejects
     *  anything that isn't a JPEG or PNG, or over {@link #MAX_UPLOAD_BYTES} — the admin page
     *  enforces the same rule before upload, but a browser check is not a security boundary. */
    private static byte[] decodeBase64(Object value) {
        String encoded = text(value);
        if (encoded == null) {
            return null;
        }
        String mime = mimeType(value, null);
        if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)) {
            log.warn("Ignoring image upload with unsupported type '{}'; only JPEG and PNG are accepted.", mime);
            return null;
        }
        int comma = encoded.indexOf(',');
        if (encoded.startsWith("data:") && comma > 0) {
            encoded = encoded.substring(comma + 1);
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException e) {
            log.warn("Ignoring malformed base64 image upload.");
            return null;
        }
        if (decoded.length > MAX_UPLOAD_BYTES) {
            log.warn("Ignoring image upload of {} bytes, over the {}-byte limit.",
                    decoded.length, MAX_UPLOAD_BYTES);
            return null;
        }
        return decoded;
    }

    /**
     * The MIME type actually embedded in a {@code data:image/png;base64,...} URL, so a PNG
     * upload doesn't get labelled {@code image/jpeg} just because that was the fallback
     * default. Falls back to an explicit {@code declaredType} field if the value isn't a
     * data URL, then to {@code image/jpeg} only as a last resort.
     */
    private static String mimeType(Object dataUrlValue, Object declaredType) {
        String value = text(dataUrlValue);
        if (value != null && value.startsWith("data:")) {
            int colon = 5;
            int semicolon = value.indexOf(';', colon);
            int comma = value.indexOf(',', colon);
            int end = semicolon > 0 ? semicolon : comma;
            if (end > colon) {
                return value.substring(colon, end);
            }
        }
        return textOr(declaredType, "image/jpeg");
    }

    private static final int MAX_UPLOAD_BYTES = 3 * 1024 * 1024;

    // ── Mobile number / date of birth / eligibility ──────────────────────────

    /** A 10-digit Indian mobile number, starting 6-9 (TRAI's allocated range). No country
     *  code: the form collects a bare 10-digit number, same as a voter ID card would. */
    private static final Pattern MOBILE_PATTERN = Pattern.compile("^[6-9]\\d{9}$");

    private static final int MIN_VOTER_AGE = 18;      // Constitution of India, Article 326
    private static final int MIN_CANDIDATE_AGE = 25;  // Articles 84(b) and 173(b) — Lok Sabha and
                                                        // Vidhan Sabha carry the same minimum

    /** Returns the validated 10-digit mobile number, or null with {@code error} set to why
     *  it was rejected — either missing or not a plausible Indian mobile number. */
    private static String requireMobile(Object value, String[] error) {
        String mobile = text(value);
        if (mobile == null) {
            error[0] = "Mobile number is required.";
            return null;
        }
        if (!MOBILE_PATTERN.matcher(mobile).matches()) {
            error[0] = "\"" + mobile + "\" is not a valid mobile number — enter 10 digits, starting with 6, 7, 8 or 9.";
            return null;
        }
        return mobile;
    }

    /** Returns the parsed, past-dated birth date, or null with {@code error} set to why it
     *  was rejected — missing, unparseable, or in the future. Does not itself check age
     *  against a minimum; call {@link #ageOn} for that once the date parses. */
    private static LocalDate requireDateOfBirth(Object value, String[] error) {
        String raw = text(value);
        if (raw == null) {
            error[0] = "Date of birth is required.";
            return null;
        }
        LocalDate dob;
        try {
            dob = LocalDate.parse(raw);
        } catch (DateTimeParseException e) {
            error[0] = "\"" + raw + "\" is not a valid date of birth.";
            return null;
        }
        if (dob.isAfter(LocalDate.now())) {
            error[0] = "Date of birth cannot be in the future.";
            return null;
        }
        return dob;
    }

    /** Whole years lived as of today — the same definition Indian electoral law uses:
     *  someone turning the minimum age today already qualifies. */
    private static int ageOn(LocalDate dateOfBirth, LocalDate today) {
        return Period.between(dateOfBirth, today).getYears();
    }
}
