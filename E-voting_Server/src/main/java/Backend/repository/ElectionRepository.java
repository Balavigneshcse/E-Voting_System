package Backend.repository;

import Backend.model.Election;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface ElectionRepository extends JpaRepository<Election, Integer> {
    Optional<Election> findByIsActiveTrue();
}