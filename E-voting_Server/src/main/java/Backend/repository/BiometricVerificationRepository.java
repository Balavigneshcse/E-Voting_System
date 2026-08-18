package Backend.repository;

import Backend.model.BiometricVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;

@Repository
public interface BiometricVerificationRepository extends JpaRepository<BiometricVerification, String> {

    @Modifying
    @Query("delete from BiometricVerification b where b.expiresAt < :cutoff")
    int deleteExpiredBefore(LocalDateTime cutoff);
}
