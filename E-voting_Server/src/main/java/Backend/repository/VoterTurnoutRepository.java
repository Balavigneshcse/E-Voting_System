package Backend.repository;

import Backend.model.VoterTurnout;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VoterTurnoutRepository extends JpaRepository<VoterTurnout, Long> {

    boolean existsByVoterIdAndElectionId(String voterId, Integer electionId);

    long countByElectionId(Integer electionId);
}
