package Backend.repository;

import Backend.model.VotingSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VotingSessionRepository extends JpaRepository<VotingSession, String> {

    VotingSession findBySessionToken(String sessionToken);

    void deleteByElectionId(Integer electionId);
}
