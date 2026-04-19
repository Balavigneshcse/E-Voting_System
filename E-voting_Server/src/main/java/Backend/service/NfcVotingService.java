package Backend.service;

import Backend.blockchain.Blockchain;
import Backend.model.*;
import Backend.repository.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
public class NfcVotingService {

    @Autowired private VoterRepository          voterRepo;
    @Autowired private VotingSessionRepository  sessionRepo;
    @Autowired private CandidateRepository      candidateRepo;
    @Autowired private VoteRepository           voteRepo;
    @Autowired private ElectionRepository       electionRepo;
    @Autowired private Blockchain               blockchain;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    public Election getActiveElection() {
        return electionRepo.findByIsActiveTrue().orElse(null);
    }

    public List<Election> getAllElections() {
        return electionRepo.findAll();
    }

    // STEP 1 — voter taps NFC or enters ID
    public Map<String, Object> startSession(String voterIdentifier) {
        Election election = getActiveElection();
        if (election == null)
            return fail("No election is currently active. Please contact election officials.");

        Voter voter = voterRepo.findByVoterId(voterIdentifier);
        if (voter == null) voter = voterRepo.findByNfcCardId(voterIdentifier);
        if (voter == null)
            return fail("Voter not found. Check your Voter ID.");

        // Check if voter already voted in THIS specific election only
        if (voteRepo.existsByVoterIdAndElectionId(voter.getVoterId(), election.getId()))
            return fail("You have already voted in the " + election.getName() + ".");

        if (voter.getCardActive() != null && !voter.getCardActive())
            return fail("Your card has been deactivated. Please contact election officials.");

        VotingSession session = new VotingSession();
        session.setVoterId(voter.getVoterId());
        session.setSessionToken(UUID.randomUUID().toString());
        session.setElectionId(election.getId());
        session.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        sessionRepo.save(session);

        Map<String, Object> res = new HashMap<>();
        res.put("success",      true);
        res.put("sessionToken", session.getSessionToken());
        res.put("voterName",    voter.getName());
        res.put("electionName", election.getName());
        res.put("electionType", election.getType());
        return res;
    }

    // STEP 2 — biometric verified
    public Map<String, Object> verifyBiometric(String sessionToken) {
        VotingSession session = sessionRepo.findBySessionToken(sessionToken);
        if (session == null)
            return fail("Session not found. Please restart.");
        if (session.getExpiresAt().isBefore(LocalDateTime.now()))
            return fail("Session expired. Please restart.");
        if (Boolean.TRUE.equals(session.getUsed()))
            return fail("Session already used.");

        session.setBiometricVerified(true);
        sessionRepo.save(session);

        Voter voter = voterRepo.findByVoterId(session.getVoterId());
        Election election = electionRepo.findById(session.getElectionId()).orElse(null);

        Integer constituencyId = null;
        if (election != null) {
            if ("PM".equals(election.getType())) {
                constituencyId = voter.getLsConstituencyId();
            } else if ("CM".equals(election.getType())) {
                constituencyId = voter.getVsConstituencyId();
            } else {
                constituencyId = voter.getMunicipalityTier();
            }
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success",        true);
        res.put("constituencyId", constituencyId != null ? constituencyId : 1);
        res.put("wardId",         voter.getMunicipalityWard());
        res.put("electionId",     session.getElectionId());
        res.put("electionType",   election != null ? election.getType() : "CM");
        res.put("electionName",   election != null ? election.getName() : "Election");
        res.put("message",        "Biometric verified.");
        return res;
    }

    // STEP 3 — get candidates based on election type and voter's area
    public List<Candidate> getCandidatesForVoter(String sessionToken) {
        VotingSession session = sessionRepo.findBySessionToken(sessionToken);
        if (session == null) return List.of();

        Voter voter = voterRepo.findByVoterId(session.getVoterId());
        Election elec = electionRepo.findById(session.getElectionId()).orElse(null);
        if (elec == null) return List.of();

        switch (elec.getType()) {
            case "PM":
                return candidateRepo.findByElectionIdAndConstituencyId(
                        elec.getId(), voter.getLsConstituencyId());
            case "CM":
                return candidateRepo.findByElectionIdAndConstituencyId(
                        elec.getId(), voter.getVsConstituencyId());
            case "MUNICIPALITY":
                return candidateRepo.findByElectionIdAndMunicipalityTier(
                        elec.getId(), voter.getMunicipalityTier());
            default:
                return candidateRepo.findByElectionId(elec.getId());
        }
    }

    // STEP 4 — cast vote
    public Map<String, Object> castVote(String sessionToken, Integer candidateId) {
        VotingSession session = sessionRepo.findBySessionToken(sessionToken);
        if (session == null)
            return fail("Invalid session. Please restart.");
        if (Boolean.TRUE.equals(session.getUsed()))
            return fail("Session already used.");
        if (!Boolean.TRUE.equals(session.getBiometricVerified()))
            return fail("Biometric not verified.");
        if (session.getExpiresAt().isBefore(LocalDateTime.now()))
            return fail("Session expired.");

        Voter voter = voterRepo.findByVoterId(session.getVoterId());
        Election election = electionRepo.findById(session.getElectionId()).orElse(null);
        Candidate candidate = candidateRepo.findById(candidateId).orElse(null);

        if (voter == null)     return fail("Voter not found.");
        if (election == null)  return fail("Election not found.");
        if (candidate == null) return fail("Candidate not found.");

        // MUNICIPALITY: voter votes once per tier (4 tiers = 4 votes)
        // PM/CM: voter votes once per election
        Integer tierId = null;
        if ("MUNICIPALITY".equals(election.getType())) {
            tierId = candidate.getMunicipalityTier();
            if (tierId == null) return fail("Invalid candidate tier.");
            // Check if already voted in this tier
            boolean alreadyVotedTier = jdbc.queryForObject(
                    "SELECT COUNT(*)>0 FROM vote WHERE voter_id=? AND election_id=? AND municipality_tier=?",
                    Boolean.class, voter.getVoterId(), election.getId(), tierId);
            if (Boolean.TRUE.equals(alreadyVotedTier))
                return fail("You have already voted in Tier " + tierId + " of this election.");
        } else {
            if (voteRepo.existsByVoterIdAndElectionId(voter.getVoterId(), election.getId()))
                return fail("You have already voted in this election.");
        }

        Integer constituencyId = null;
        if ("PM".equals(election.getType())) {
            constituencyId = voter.getLsConstituencyId();
        } else if ("CM".equals(election.getType())) {
            constituencyId = voter.getVsConstituencyId();
        }

        Vote vote = new Vote();
        vote.setVoterId(voter.getVoterId());
        vote.setCandidateName(candidate.getName());
        vote.setCandidateId(candidateId);
        vote.setElectionId(election.getId());
        vote.setElectionType(election.getType());
        vote.setConstituencyId(constituencyId);
        if ("MUNICIPALITY".equals(election.getType())) {
            vote.setMunicipalityTier(tierId);
        }
        voteRepo.save(vote);

        blockchain.addVote(voter.getVoterId(),
                candidate.getName() + " [" + election.getType() +
                        ("MUNICIPALITY".equals(election.getType()) ? " Tier-"+tierId : "") + "]");

        // For MUNICIPALITY: only mark session used after all 4 tiers voted
        if ("MUNICIPALITY".equals(election.getType())) {
            long tiersVoted = jdbc.queryForObject(
                    "SELECT COUNT(DISTINCT municipality_tier) FROM vote WHERE voter_id=? AND election_id=?",
                    Long.class, voter.getVoterId(), election.getId());
            if (tiersVoted >= 4) { session.setUsed(true); sessionRepo.save(session); }
        } else {
            session.setUsed(true);
            sessionRepo.save(session);
        }

        Map<String, Object> res = new HashMap<>();
        res.put("success",      true);
        res.put("blockNumber",  blockchain.getChain().size() - 1);
        res.put("electionName", election.getName());
        res.put("message",      "Vote recorded on blockchain.");
        return res;
    }

    // Admin — enable a specific election
    public String enableElection(Integer electionId) {
        electionRepo.findAll().forEach(e -> {
            e.setIsActive(false);
            electionRepo.save(e);
        });
        Election election = electionRepo.findById(electionId).orElse(null);
        if (election == null) return "Election not found.";
        election.setIsActive(true);
        electionRepo.save(election);
        return election.getName() + " is now active.";
    }

    // Admin — reset votes for specific election
    public String resetElection(Integer electionId) {
        voteRepo.deleteByElectionId(electionId);
        sessionRepo.deleteByElectionId(electionId);
        Election election = electionRepo.findById(electionId).orElse(null);
        if (election != null) {
            election.setElectionCycle(election.getElectionCycle() + 1);
            electionRepo.save(election);
        }
        return "Election reset. All votes cleared.";
    }

    private Map<String, Object> fail(String msg) {
        Map<String, Object> res = new HashMap<>();
        res.put("success", false);
        res.put("message", msg);
        return res;
    }
}