package Backend.repository;
import Backend.model.Voter;
import org.springframework.data.jpa.repository.JpaRepository;
public interface VoterRepository extends JpaRepository<Voter, Long> {
    Voter findByVoterId(String voterId);
    Voter findByNfcCardId(String nfcCardId);
    boolean existsByVoterId(String voterId);  // ← add this line
}