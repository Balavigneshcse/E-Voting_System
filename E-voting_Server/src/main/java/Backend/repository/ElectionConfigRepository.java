package Backend.repository;
import Backend.model.ElectionConfig;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ElectionConfigRepository extends JpaRepository<ElectionConfig, Integer> {}