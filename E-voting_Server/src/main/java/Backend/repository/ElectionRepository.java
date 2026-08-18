package Backend.repository;

import Backend.model.Election;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ElectionRepository extends JpaRepository<Election, Integer> {

    Optional<Election> findByIsActiveTrue();

    /** Newest cycle first — {@code .get(0)} is what "next election" counts up from. */
    List<Election> findByTypeOrderByElectionCycleDesc(String type);
}
