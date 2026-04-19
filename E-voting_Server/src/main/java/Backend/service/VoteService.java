package Backend.service;

import Backend.blockchain.Blockchain;
import Backend.model.Vote;
import Backend.model.Voter;
import Backend.repository.VoteRepository;
import Backend.repository.VoterRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VoteService {

    @Autowired
    private VoterRepository voterRepository;

    @Autowired
    private VoteRepository voteRepository;

    @Autowired
    private Blockchain blockchain;   // ← injected because it's now @Component

    public String castVote(String voterId, String candidateName) {
        Voter voter = voterRepository.findByVoterId(voterId);

        if (voter == null) {
            return "Voter not found!";
        }
        if (voter.getHasVoted()) {
            return "You have already voted!";
        }

        // 1. Save to PostgreSQL
        Vote vote = new Vote();
        vote.setVoterId(voterId);
        vote.setCandidate(candidateName);
        voteRepository.save(vote);

        // 2. Record on the blockchain
        blockchain.addVote(voterId, candidateName);

        // 3. Mark voter as done
        voter.setHasVoted(true);
        voterRepository.save(voter);

        return "Vote cast successfully! Block #" + (blockchain.getChain().size() - 1) + " added to chain.";
    }
}