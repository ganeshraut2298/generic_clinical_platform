package com.sahaj.platform.infrastructure.admin;

import com.sahaj.platform.application.admin.AdminTokenService;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.crypto.SecretKey;

/**
 * Admin access tokens (ADR-003 D3): HS256 with {@code SAHAJ_ADMIN_JWT_SECRET} (never the clinic
 * key), {@code iss sahaj}, {@code aud sahaj-admin}, {@code sub} the admin, {@code sid} the session,
 * {@code role PLATFORM_ADMIN}, {@code amr}, {@code jti}, 10 minutes. There is no {@code tid}, and a
 * token that carries one is refused. A clinic token cannot verify here (different key and no
 * audience), and the clinic parser refuses this audience.
 */
public class AdminJwtService implements AdminTokenService {

    public static final String ISSUER = "sahaj";
    public static final String AUDIENCE = "sahaj-admin";
    public static final Duration LIFE = Duration.ofMinutes(10);

    private static final String ROLE_CLAIM = "role";
    private static final String SESSION_CLAIM = "sid";
    private static final String AMR_CLAIM = "amr";
    private static final String TENANT_CLAIM = "tid";

    private final SecretKey key;
    private final Clock clock;

    public AdminJwtService(String secret, Clock clock) {
        this.key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        this.clock = clock;
    }

    @Override
    public AccessToken issue(UUID adminId, UUID sessionId, List<String> amr) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(LIFE);
        String token = Jwts.builder()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(adminId.toString())
                .claim(SESSION_CLAIM, sessionId.toString())
                .claim(ROLE_CLAIM, AdminAuthentication.ROLE)
                .claim(AMR_CLAIM, amr)
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new AccessToken(token, (int) LIFE.toSeconds());
    }

    @Override
    public Optional<AdminTokenService.Claims> parse(String token) {
        try {
            Jws<io.jsonwebtoken.Claims> jws = Jwts.parser()
                    .clock(() -> Date.from(clock.instant()))
                    .verifyWith(key)
                    .requireIssuer(ISSUER)
                    .requireAudience(AUDIENCE)
                    .build()
                    .parseSignedClaims(token);
            if (!"HS256".equals(jws.getHeader().getAlgorithm())) {
                return Optional.empty();
            }
            io.jsonwebtoken.Claims claims = jws.getPayload();
            if (claims.containsKey(TENANT_CLAIM)
                    || !AdminAuthentication.ROLE.equals(claims.get(ROLE_CLAIM, String.class))
                    || claims.get(SESSION_CLAIM, String.class) == null) {
                return Optional.empty();
            }
            List<String> amr = new ArrayList<>();
            Object raw = claims.get(AMR_CLAIM);
            if (raw instanceof List<?> values) {
                for (Object value : values) {
                    amr.add(String.valueOf(value));
                }
            }
            return Optional.of(new AdminTokenService.Claims(
                    UUID.fromString(claims.getSubject()),
                    UUID.fromString(claims.get(SESSION_CLAIM, String.class)),
                    List.copyOf(amr),
                    claims.getExpiration().toInstant()));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
