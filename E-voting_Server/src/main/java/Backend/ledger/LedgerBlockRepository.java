package Backend.ledger;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface LedgerBlockRepository extends JpaRepository<LedgerBlock, Long> {

    Optional<LedgerBlock> findFirstByOrderByBlockIndexDesc();

    List<LedgerBlock> findAllByOrderByBlockIndexAsc();

    List<LedgerBlock> findByOrderByBlockIndexDesc(Limit limit);

    boolean existsByBallotUuid(String ballotUuid);

    Optional<LedgerBlock> findByBallotUuid(String ballotUuid);

    @Query("select coalesce(max(b.blockIndex), -1) from LedgerBlock b")
    long findMaxBlockIndex();
}
