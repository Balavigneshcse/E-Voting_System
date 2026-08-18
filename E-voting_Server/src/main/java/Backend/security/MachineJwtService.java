package Backend.security;

import Backend.model.MachineToken;
import Backend.repository.MachineTokenRepository;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;

/**
 * Mints and verifies the HS256 tokens a terminal presents on every request.
 *
 * <p>A plain JWT cannot be withdrawn before it expires, which is a poor fit for
 * election hardware that might be stolen mid-poll. Each token therefore carries a
 * {@code jti} recorded in {@code machine_tokens}, and verification requires that
 * row to exist and be neither revoked nor expired. Revoking a terminal invalidates
 * its tokens immediately.
 */
@Service
public class MachineJwtService {

    private static final String ISSUER            = "evoting-server";
    private static final String AUDIENCE          = "evoting-machine";
    private static final String CLAIM_MACHINE_ID  = "mid";

    private static final Logger log = LoggerFactory.getLogger(MachineJwtService.class);

    private final MachineTokenRepository      tokens;
    private final MachineSecurityProperties   properties;
    private final SecretKey                   signingKey;

    public MachineJwtService(MachineTokenRepository tokens,
                             MachineSecurityProperties properties,
                             MasterKeyProvider keys) {
        this.tokens     = tokens;
        this.properties = properties;
        this.signingKey = new SecretKeySpec(keys.jwtKey(), "HmacSHA256");
    }

    @Transactional
    public IssuedToken issue(String machineId) {
        String        jti       = UUID.randomUUID().toString();
        LocalDateTime issuedAt  = LocalDateTime.now();
        LocalDateTime expiresAt = issuedAt.plus(properties.getTokenTtl());

        String jwt = Jwts.builder()
                .id(jti)
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(machineId)
                .claim(CLAIM_MACHINE_ID, machineId)
                .issuedAt(toDate(issuedAt))
                .expiration(toDate(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();

        tokens.save(new MachineToken(jti, machineId, issuedAt, expiresAt));
        log.info("Issued machine token for {} (jti {}), valid until {}.", machineId, jti, expiresAt);
        return new IssuedToken(jwt, expiresAt);
    }

    /**
     * Verifies signature, claims and revocation state.
     *
     * @return the machine ID when the token is fully valid, otherwise empty
     */
    @Transactional(readOnly = true)
    public Optional<String> verify(String jwt) {
        if (jwt == null || jwt.isBlank()) {
            return Optional.empty();
        }
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(jwt)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Rejected machine token: {}", e.getMessage());
            return Optional.empty();
        }

        String machineId = claims.get(CLAIM_MACHINE_ID, String.class);
        if (machineId == null || machineId.isBlank()) {
            return Optional.empty();
        }

        // The signature is valid, but the token may since have been withdrawn.
        return tokens.findById(claims.getId())
                .filter(MachineToken::isUsable)
                .filter(record -> record.getMachineId().equals(machineId))
                .map(MachineToken::getMachineId);
    }

    @Transactional
    public int revokeAllFor(String machineId) {
        return tokens.revokeAllForMachine(machineId, LocalDateTime.now());
    }

    private static Date toDate(LocalDateTime value) {
        return Date.from(value.atZone(ZoneId.systemDefault()).toInstant());
    }

    /** A freshly minted token and the moment it stops working. */
    public record IssuedToken(String token, LocalDateTime expiresAt) {
        public long secondsUntilExpiry() {
            return Math.max(0, java.time.Duration.between(
                    LocalDateTime.now(), expiresAt).toSeconds());
        }

        public Instant expiresAtInstant() {
            return expiresAt.atZone(ZoneId.systemDefault()).toInstant();
        }
    }
}
