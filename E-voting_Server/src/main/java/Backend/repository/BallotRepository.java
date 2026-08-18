package Backend.repository;

import Backend.model.Ballot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * Note the absence of any query by voter: {@link Ballot} has no voter column, so
 * "which candidate did this voter choose" is not an expressible question.
 */
@Repository
public interface BallotRepository extends JpaRepository<Ballot, Long> {

    Optional<Ballot> findByIdempotencyKey(String idempotencyKey);

    Optional<Ballot> findByBallotUuid(String ballotUuid);

    long countByElectionId(Integer electionId);
}
