package Backend.repository;

import Backend.model.Candidate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface CandidateRepository extends JpaRepository<Candidate, Integer> {

    /**
     * Ballot order for one constituency: real candidates first, by registration order
     * (button position should not shift just because someone else was added later), then
     * NOTA last regardless of when it was inserted. Without the explicit ordering here, the
     * physical button a candidate maps to on the terminal would depend on unspecified query
     * plan behaviour rather than a guarantee — a ballot's layout has to be stable.
     */
    @Query("SELECT c FROM Candidate c "
         + "WHERE c.electionId = :electionId AND c.constituencyId = :constituencyId "
         + "ORDER BY CASE WHEN c.name = 'NOTA' THEN 1 ELSE 0 END, c.id")
    List<Candidate> findByElectionIdAndConstituencyId(@Param("electionId") Integer electionId,
                                                      @Param("constituencyId") Integer constituencyId);

    List<Candidate> findByElectionIdAndMunicipalityTier(Integer electionId, Integer municipalityTier);

    List<Candidate> findByElectionId(Integer electionId);

    List<Candidate> findByConstituencyId(Integer constituencyId);
}
