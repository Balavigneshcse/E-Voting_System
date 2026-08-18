package Backend.repository;

import Backend.model.Voter;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Voter lookup, keyed by the two identifiers a terminal can present: the
 * voter ID (typed on a simulated card) and the NFC card UID (read from a real
 * card). Both are backed by the unique indexes added in
 * {@code V5__voter_lookup_indexes.sql}, so a card tap against an electorate
 * of millions is a single index seek rather than a table scan.
 */
@Repository
public interface VoterRepository extends JpaRepository<Voter, Long> {

    Voter findByVoterId(String voterId);

    Voter findByNfcCardId(String nfcCardId);
}