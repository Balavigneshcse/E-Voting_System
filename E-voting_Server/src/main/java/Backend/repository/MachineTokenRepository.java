package Backend.repository;

import Backend.model.MachineToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface MachineTokenRepository extends JpaRepository<MachineToken, String> {

    @Modifying
    @Query("update MachineToken t set t.revokedAt = :now where t.machineId = :machineId and t.revokedAt is null")
    int revokeAllForMachine(String machineId, LocalDateTime now);

    @Modifying
    @Query("delete from MachineToken t where t.expiresAt < :cutoff")
    int deleteExpiredBefore(LocalDateTime cutoff);
}
