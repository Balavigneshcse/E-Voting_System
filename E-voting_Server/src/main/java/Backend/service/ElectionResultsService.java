package Backend.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live tallies, computed straight from {@code ballots} on every request.
 *
 * <p>There is no cache, no materialised view and no scheduled aggregation, so a result
 * is never stale: a vote committed a second ago is in the next response. That is what
 * makes same-day declaration workable, and it is cheap at this scale — one ward is
 * about 3,000 voters, per the hardware quotation.
 *
 * <p>Every query reads {@code ballots}, which holds no voter identity, so producing
 * results cannot expose how anyone voted. Turnout comes from {@code voter_turnout},
 * which holds no candidate. The two are never joined.
 */
@Service
public class ElectionResultsService {

    private final JdbcTemplate jdbc;

    public ElectionResultsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getResults(Integer electionId) {
        Map<String, Object> result = new HashMap<>();

        List<Map<String, Object>> election = jdbc.queryForList(
                "SELECT id, name, name_ta, type, election_cycle, is_active FROM elections WHERE id = ?",
                electionId);
        if (election.isEmpty()) {
            return result;
        }
        result.put("election", election.get(0));

        String type = (String) election.get(0).get("type");

        long eligibleVoters = eligibleVoterCount(type);
        long ballotsCast    = count("SELECT COUNT(*) FROM ballots WHERE election_id = ?", electionId);
        long votersVoted    = count("SELECT COUNT(*) FROM voter_turnout WHERE election_id = ?", electionId);
        long notaVotes      = count("""
                SELECT COUNT(*) FROM ballots b
                JOIN   candidate ca ON ca.id = b.candidate_id
                WHERE  b.election_id = ? AND ca.name = 'NOTA'
                """, electionId);
        long candidatesContesting = count(
                "SELECT COUNT(*) FROM candidate WHERE election_id = ? AND name != 'NOTA'", electionId);
        long constituenciesWithCandidates = count(
                "SELECT COUNT(DISTINCT constituency_id) FROM candidate WHERE election_id = ?", electionId);
        long constituenciesReporting = count(
                "SELECT COUNT(DISTINCT constituency_id) FROM ballots WHERE election_id = ?", electionId);

        result.put("eligibleVoters",  eligibleVoters);
        result.put("totalVotesCast",  ballotsCast);
        result.put("votersVoted",     votersVoted);
        result.put("turnoutPercent",  percentage(votersVoted, eligibleVoters));
        result.put("notaVotes",       notaVotes);
        result.put("notaPercent",     percentage(notaVotes, ballotsCast));
        result.put("candidatesContesting",       candidatesContesting);
        result.put("constituenciesWithCandidates", constituenciesWithCandidates);
        result.put("constituenciesReporting",    constituenciesReporting);
        result.put("breakdown",       "PM".equals(type) ? getPmResults(electionId) : getCmResults(electionId));
        result.put("partyTotals",     getPartyTotals(electionId));
        result.put("constituencyLeaders", getConstituencyLeaders(electionId));
        return result;
    }

    // ── PM (Lok Sabha) ──────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPmResults(Integer electionId) {
        return jdbc.queryForList("""
                SELECT s.name  AS state_name,
                       c.name  AS constituency_name,
                       ca.name AS candidate_name,
                       ca.party,
                       ca.party_color,
                       COUNT(*) AS votes
                FROM   ballots b
                JOIN   constituencies c ON c.id = b.constituency_id
                JOIN   states         s ON s.id = c.state_id
                JOIN   candidate     ca ON ca.id = b.candidate_id
                WHERE  b.election_id = ?
                GROUP  BY s.name, c.name, ca.name, ca.party, ca.party_color
                ORDER  BY s.name, c.name, votes DESC
                """, electionId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPmStateResults(Integer electionId, Integer stateId) {
        return jdbc.queryForList("""
                SELECT c.name AS constituency_name,
                       COALESCE(c.district_name, '') AS district,
                       ca.name AS candidate_name,
                       ca.party,
                       ca.party_color,
                       COUNT(*) AS votes
                FROM   ballots b
                JOIN   constituencies c ON c.id = b.constituency_id
                JOIN   candidate     ca ON ca.id = b.candidate_id
                WHERE  b.election_id = ? AND c.state_id = ?
                GROUP  BY c.name, c.district_name, ca.name, ca.party, ca.party_color
                ORDER  BY c.district_name, c.name, votes DESC
                """, electionId, stateId);
    }

    // ── CM (Vidhan Sabha) ───────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCmResults(Integer electionId) {
        return jdbc.queryForList("""
                SELECT COALESCE(c.district_name, '') AS district_name,
                       c.name  AS constituency_name,
                       ca.name AS candidate_name,
                       ca.party,
                       ca.party_color,
                       COUNT(*) AS votes
                FROM   ballots b
                JOIN   constituencies c ON c.id = b.constituency_id
                JOIN   candidate     ca ON ca.id = b.candidate_id
                WHERE  b.election_id = ?
                GROUP  BY c.district_name, c.name, ca.name, ca.party, ca.party_color
                ORDER  BY c.district_name, c.name, votes DESC
                """, electionId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getCmStateResults(Integer electionId, Integer stateId) {
        return jdbc.queryForList("""
                SELECT COALESCE(c.district_name, 'Unknown') AS district_name,
                       c.name  AS constituency_name,
                       ca.name AS candidate_name,
                       ca.party,
                       ca.party_color,
                       COUNT(*) AS votes
                FROM   ballots b
                JOIN   constituencies c ON c.id = b.constituency_id
                JOIN   candidate     ca ON ca.id = b.candidate_id
                WHERE  b.election_id = ? AND c.state_id = ?
                GROUP  BY c.district_name, c.name, ca.name, ca.party, ca.party_color
                ORDER  BY c.district_name, c.name, votes DESC
                """, electionId, stateId);
    }

    // ── Aggregates for the live dashboard ───────────────────────────────────

    /** Total votes per party across the whole election. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPartyTotals(Integer electionId) {
        return jdbc.queryForList("""
                SELECT COALESCE(ca.party, 'Independent') AS party,
                       MIN(ca.party_color) AS party_color,
                       COUNT(*)            AS votes
                FROM   ballots b
                JOIN   candidate ca ON ca.id = b.candidate_id
                WHERE  b.election_id = ?
                GROUP  BY COALESCE(ca.party, 'Independent')
                ORDER  BY votes DESC
                """, electionId);
    }

    /**
     * The current leader in each constituency, with the winning margin.
     *
     * <p>NOTA is deliberately excluded from the ranking. Under Indian election law
     * (Rule 64 of the Conduct of Election Rules, following the Supreme Court's 2013
     * PUCL judgment that introduced NOTA), a NOTA majority does not void the seat or
     * make NOTA "elected" — the candidate with the most actual votes still wins,
     * NOTA's count simply gets reported alongside as a rejection statistic. A
     * constituency where NOTA outpolled every candidate is still won by whichever
     * candidate came first among the real candidates; it does not show NOTA here.
     *
     * <p>Uses a window function so ties and margins come out of one pass rather than
     * being recomputed per constituency in Java.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getConstituencyLeaders(Integer electionId) {
        return jdbc.queryForList("""
                WITH totals AS (
                    SELECT constituency_id, COUNT(*) AS seat_total_votes
                    FROM   ballots
                    WHERE  election_id = ?
                    GROUP  BY constituency_id
                ),
                tally AS (
                    SELECT b.constituency_id,
                           ca.name  AS candidate_name,
                           ca.party,
                           ca.party_color,
                           COUNT(*) AS votes,
                           ROW_NUMBER() OVER (PARTITION BY b.constituency_id ORDER BY COUNT(*) DESC) AS rank,
                           COUNT(*) - COALESCE(LEAD(COUNT(*)) OVER (
                                PARTITION BY b.constituency_id ORDER BY COUNT(*) DESC), 0) AS margin
                    FROM   ballots b
                    JOIN   candidate ca ON ca.id = b.candidate_id
                    WHERE  b.election_id = ? AND ca.name != 'NOTA'
                    GROUP  BY b.constituency_id, ca.name, ca.party, ca.party_color
                )
                SELECT c.id AS constituency_id,
                       c.name AS constituency_name,
                       COALESCE(c.district_name, '') AS district,
                       s.id AS state_id,
                       s.name AS state_name,
                       t.candidate_name,
                       t.party,
                       t.party_color,
                       t.votes,
                       tot.seat_total_votes,
                       t.margin
                FROM   tally t
                JOIN   totals tot ON tot.constituency_id = t.constituency_id
                JOIN   constituencies c ON c.id = t.constituency_id
                JOIN   states         s ON s.id = c.state_id
                WHERE  t.rank = 1
                ORDER  BY s.name, c.name
                """, electionId, electionId);
    }

    /**
     * How many constituencies each party is currently leading — or has won, once
     * polling for the election closes; the frontend decides which word to use from
     * the election's own {@code is_active} flag, since this system closes a whole
     * election at once rather than seat by seat. Reuses the same NOTA-excluded
     * leader logic as {@link #getConstituencyLeaders}, so the two can never disagree
     * about who is ahead in a given seat.
     *
     * <p>This is the "party performance" table every real ECI results page leads
     * with — deliberately the first thing on this project's results pages too.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getPartySeatTally(Integer electionId) {
        return jdbc.queryForList("""
                WITH tally AS (
                    SELECT b.constituency_id,
                           COALESCE(ca.party, 'Independent') AS party,
                           MIN(ca.party_color) AS party_color,
                           COUNT(*) AS votes,
                           ROW_NUMBER() OVER (PARTITION BY b.constituency_id ORDER BY COUNT(*) DESC) AS rank
                    FROM   ballots b
                    JOIN   candidate ca ON ca.id = b.candidate_id
                    WHERE  b.election_id = ? AND ca.name != 'NOTA'
                    GROUP  BY b.constituency_id, COALESCE(ca.party, 'Independent')
                )
                SELECT party, MIN(party_color) AS party_color, COUNT(*) AS seats
                FROM   tally
                WHERE  rank = 1
                GROUP  BY party
                ORDER  BY seats DESC, party
                """, electionId);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getStatesWithVotes(Integer electionId) {
        return jdbc.queryForList("""
                SELECT DISTINCT s.id, s.name
                FROM   ballots b
                JOIN   constituencies c ON c.id = b.constituency_id
                JOIN   states         s ON s.id = c.state_id
                WHERE  b.election_id = ?
                ORDER  BY s.name
                """, electionId);
    }

    @Transactional(readOnly = true)
    public Map<String, Object> getElectionStats(Integer electionId) {
        String type = jdbc.queryForList("SELECT type FROM elections WHERE id = ?", electionId)
                .stream().findFirst().map(row -> (String) row.get("type")).orElse(null);

        long eligibleVoters = eligibleVoterCount(type);
        long votersVoted    = count("SELECT COUNT(*) FROM voter_turnout WHERE election_id = ?", electionId);
        long ballotsCast    = count("SELECT COUNT(*) FROM ballots WHERE election_id = ?", electionId);
        long candidates     = count("SELECT COUNT(*) FROM candidate WHERE election_id = ?", electionId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("eligibleVoters",  eligibleVoters);
        stats.put("votersVoted",     votersVoted);
        stats.put("totalVotes",      ballotsCast);
        stats.put("totalCandidates", candidates);
        stats.put("turnout",         percentage(votersVoted, eligibleVoters));
        stats.put("machinesActive",  count(
                "SELECT COUNT(*) FROM machines WHERE status = 'ACTIVE' "
                        + "AND last_seen_at > now() - interval '5 minutes'"));
        return stats;
    }

    /** Votes recorded per terminal, so a booth that has gone quiet is visible. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getVotesPerMachine(Integer electionId) {
        return jdbc.queryForList("""
                SELECT m.machine_id,
                       m.label,
                       m.booth_name,
                       m.status,
                       m.last_seen_at,
                       COUNT(b.id) AS votes
                FROM   machines m
                LEFT JOIN ballots b ON b.machine_id = m.machine_id AND b.election_id = ?
                GROUP  BY m.machine_id, m.label, m.booth_name, m.status, m.last_seen_at
                ORDER  BY m.machine_id
                """, electionId);
    }

    /**
     * Turnout broken down by voter age band, for one election.
     *
     * <p>Possible since {@code V10} added {@code date_of_birth} to registration. Age
     * bands follow the ranges the Election Commission itself uses in post-poll turnout
     * releases (18-19 kept separate — first-time voters are usually reported on their
     * own — then ten-year bands, with 80+ folded into "60+" here since a booth-level
     * dashboard has no real use for an 80+ bucket the way a national report might).
     *
     * <p>"Eligible" here uses the same definition as {@link #eligibleVoterCount}: an
     * active card and the constituency assignment this election's type requires. A
     * voter with no date of birth on file (only possible on a row created before
     * {@code V10}) is excluded from every band rather than silently miscounted into one.
     */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTurnoutByAgeBand(Integer electionId) {
        String type = jdbc.queryForList("SELECT type FROM elections WHERE id = ?", electionId)
                .stream().findFirst().map(row -> (String) row.get("type")).orElse(null);
        String constituencyColumn = "PM".equals(type) ? "ls_constituency_id" : "vs_constituency_id";

        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT band,
                       COUNT(*) AS eligible,
                       COUNT(*) FILTER (WHERE voted) AS voted
                FROM (
                    SELECT v.voter_id,
                           (vt.voter_id IS NOT NULL) AS voted,
                           CASE
                               WHEN DATE_PART('year', AGE(v.date_of_birth)) < 20 THEN '18-19'
                               WHEN DATE_PART('year', AGE(v.date_of_birth)) < 30 THEN '20-29'
                               WHEN DATE_PART('year', AGE(v.date_of_birth)) < 40 THEN '30-39'
                               WHEN DATE_PART('year', AGE(v.date_of_birth)) < 50 THEN '40-49'
                               WHEN DATE_PART('year', AGE(v.date_of_birth)) < 60 THEN '50-59'
                               ELSE '60+'
                           END AS band
                    FROM   voters v
                    LEFT JOIN voter_turnout vt
                           ON vt.voter_id = v.voter_id AND vt.election_id = ?
                    WHERE  v.card_active = true
                       AND v.date_of_birth IS NOT NULL
                       AND v.%s IS NOT NULL
                ) banded
                GROUP  BY band
                ORDER  BY MIN(CASE band
                               WHEN '18-19' THEN 1 WHEN '20-29' THEN 2 WHEN '30-39' THEN 3
                               WHEN '40-49' THEN 4 WHEN '50-59' THEN 5 ELSE 6 END)
                """.formatted(constituencyColumn), electionId);

        for (Map<String, Object> row : rows) {
            long eligible = ((Number) row.get("eligible")).longValue();
            long voted = ((Number) row.get("voted")).longValue();
            row.put("turnoutPercent", percentage(voted, eligible));
        }
        return rows;
    }


    // ── Settings and lookups ────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String getLanguage() {
        try {
            return jdbc.queryForObject(
                    "SELECT value FROM election_settings WHERE key = 'language'", String.class);
        } catch (RuntimeException e) {
            return "EN";
        }
    }

    @Transactional
    public void setLanguage(String language) {
        jdbc.update("UPDATE election_settings SET value = ? WHERE key = 'language'", language);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getTranslations(String language) {
        return jdbc.queryForList("SELECT key, value FROM translations WHERE lang = ?", language);
    }

    @Transactional(readOnly = true)
    public List<Map<String, Object>> getAllStates() {
        return jdbc.queryForList(
                "SELECT id, name, name_ta, name_hi, language_code, language_name, type, "
                        + "ls_seats, vs_seats FROM states ORDER BY name");
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /**
     * How many registered voters could actually cast a ballot in an election of this
     * type, right now.
     *
     * <p>This used to be a flat {@code COUNT(*) FROM voters} regardless of election
     * type, which overstated the denominator for turnout: a voter with no
     * {@code ls_constituency_id} can't get a PM ballot ({@code VotingService
     * .constituencyFor}/{@code startSession} refuses with "not assigned to a
     * constituency"), a voter with no {@code vs_constituency_id} can't get a CM one, and
     * a deactivated card ({@code card_active = false}) is turned away at card-read before
     * either check runs. Turnout percentage is only meaningful against the same
     * denominator the voting flow itself uses to decide who can vote.
     *
     * <p>Deliberately does not additionally filter CM by {@code election_open_states}
     * (see {@code V8}): a voter in a not-yet-opened state is still an eligible voter for
     * this election, just not able to vote *yet* — narrowing the denominator to only
     * currently-open states would make turnout look artificially high as more states
     * open, which is the opposite of what the number should communicate.
     */
    private long eligibleVoterCount(String electionType) {
        if ("PM".equals(electionType)) {
            return count("SELECT COUNT(*) FROM voters WHERE card_active = true AND ls_constituency_id IS NOT NULL");
        }
        if ("CM".equals(electionType)) {
            return count("SELECT COUNT(*) FROM voters WHERE card_active = true AND vs_constituency_id IS NOT NULL");
        }
        // Unknown/unsupported election type — fall back to the old, broader count rather
        // than guess at a filter that doesn't apply to it.
        return count("SELECT COUNT(*) FROM voters WHERE card_active = true");
    }

    private long count(String sql, Object... args) {
        Long value = jdbc.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private static double percentage(long part, long whole) {
        return whole > 0 ? Math.round(part * 1000.0 / whole) / 10.0 : 0.0;
    }
}
