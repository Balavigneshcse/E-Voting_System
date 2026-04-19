package Backend.repository;
import Backend.model.Constituency;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ConstituencyRepository extends JpaRepository<Constituency, Integer> {}