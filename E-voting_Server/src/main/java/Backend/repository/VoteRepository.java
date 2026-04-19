package Backend.repository;

import Backend.model.Vote;
import org.springframework.data.jpa.repository.JpaRepository;

public interface VoteRepository extends JpaRepository<Vote, Long> {
    boolean existsByVoterIdAndElectionId(String voterId, Integer electionId);
    boolean existsByVoterId(String voterId);
    void    deleteByElectionId(Integer electionId);
}