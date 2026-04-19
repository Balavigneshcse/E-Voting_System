package Backend.repository;

import Backend.model.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

public interface CandidateRepository extends JpaRepository<Candidate, Integer> {
    List<Candidate> findByElectionIdAndConstituencyId(Integer electionId, Integer constituencyId);
    List<Candidate> findByElectionIdAndMunicipalityTier(Integer electionId, Integer municipalityTier);
    List<Candidate> findByElectionId(Integer electionId);
    List<Candidate> findByConstituencyId(Integer constituencyId);
}