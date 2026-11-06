package com.sahaj.platform.infrastructure.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.sahaj.platform.application.TokenService;
import com.sahaj.platform.application.admin.AdminTokenService;
import com.sahaj.platform.infrastructure.security.JwtProperties;
import com.sahaj.platform.infrastructure.security.JwtTokenService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import javax.crypto.SecretKey;
import org.junit.jupiter.api.Test;

class AdminJwtServiceTest {

    private static final String ADMIN_SECRET = "admin-secret-admin-secret-admin-secret-1";
    private static final String CLINIC_SECRET = "clinic-secret-clinic-secret-clinic-secret";
    private static final Instant NOW = Instant.parse("2026-11-05T10:00:00Z");

    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final AdminJwtService admin = new AdminJwtService(ADMIN_SECRET, clock);
    private final JwtTokenService clinic = new JwtTokenService(new JwtProperties(CLINIC_SECRET, 15, 5), clock);
    private final UUID adminId = UUID.randomUUID();
    private final UUID sessionId = UUID.randomUUID();

    private static SecretKey key(String secret) {
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private io.jsonwebtoken.JwtBuilder builder(String secret) {
        return Jwts.builder().issuer("sahaj").audience().add("sahaj-admin").and()
                .subject(adminId.toString()).claim("sid", sessionId.toString()).claim("role", "PLATFORM_ADMIN")
                .issuedAt(Date.from(NOW)).expiration(Date.from(NOW.plus(Duration.ofMinutes(10))))
                .signWith(key(secret), Jwts.SIG.HS256);
    }

    @Test
    void issuesATenMinuteTokenWithTheDocumentedClaimsAndNoTenant() {
        AdminTokenService.AccessToken token = admin.issue(adminId, sessionId, List.of("pwd", "otp"));

        assertThat(token.expiresInSeconds()).isEqualTo(600);
        var jws = Jwts.parser().verifyWith(key(ADMIN_SECRET)).clock(() -> Date.from(NOW)).build()
                .parseSignedClaims(token.token());
        var claims = jws.getPayload();
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(claims.getIssuer()).isEqualTo("sahaj");
        assertThat(claims.getAudience()).containsExactly("sahaj-admin");
        assertThat(claims.getSubject()).isEqualTo(adminId.toString());
        assertThat(claims.get("sid", String.class)).isEqualTo(sessionId.toString());
        assertThat(claims.get("role", String.class)).isEqualTo("PLATFORM_ADMIN");
        assertThat(claims.get("amr", List.class)).containsExactly("pwd", "otp");
        assertThat(claims.getId()).isNotBlank();
        assertThat(claims.containsKey("tid")).isFalse();
        assertThat(claims.getExpiration().toInstant()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void parsesATokenItIssued() {
        AdminTokenService.AccessToken token = admin.issue(adminId, sessionId, List.of("pwd", "rcode"));

        AdminTokenService.Claims claims = admin.parse(token.token()).orElseThrow();

        assertThat(claims.adminId()).isEqualTo(adminId);
        assertThat(claims.sessionId()).isEqualTo(sessionId);
        assertThat(claims.amr()).containsExactly("pwd", "rcode");
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofMinutes(10)));
    }

    @Test
    void refusesAnExpiredToken() {
        String token = admin.issue(adminId, sessionId, List.of("pwd", "otp")).token();
        AdminJwtService later = new AdminJwtService(ADMIN_SECRET,
                Clock.fixed(NOW.plus(Duration.ofMinutes(11)), ZoneOffset.UTC));
        assertThat(later.parse(token)).isEmpty();
    }

    @Test
    void refusesATokenSignedWithAnotherKey() {
        assertThat(admin.parse(builder("another-secret-another-secret-another-1").compact())).isEmpty();
    }

    @Test
    void refusesATokenWithoutTheAdminAudience() {
        String token = Jwts.builder().issuer("sahaj").subject(adminId.toString()).claim("sid", sessionId.toString())
                .claim("role", "PLATFORM_ADMIN").expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(key(ADMIN_SECRET), Jwts.SIG.HS256).compact();
        assertThat(admin.parse(token)).isEmpty();
    }

    @Test
    void refusesATokenForAnotherRoleOrWithoutASession() {
        String clinicRole = builder(ADMIN_SECRET).claim("role", "OWNER").compact();
        assertThat(admin.parse(clinicRole)).isEmpty();
        String noSid = Jwts.builder().issuer("sahaj").audience().add("sahaj-admin").and().subject(adminId.toString())
                .claim("role", "PLATFORM_ADMIN").expiration(Date.from(NOW.plusSeconds(60)))
                .signWith(key(ADMIN_SECRET), Jwts.SIG.HS256).compact();
        assertThat(admin.parse(noSid)).isEmpty();
    }

    @Test
    void refusesAnAdminTokenThatCarriesATenant() {
        assertThat(admin.parse(builder(ADMIN_SECRET).claim("tid", UUID.randomUUID().toString()).compact())).isEmpty();
    }

    @Test
    void refusesAWrongIssuer() {
        assertThat(admin.parse(builder(ADMIN_SECRET).issuer("someone-else").compact())).isEmpty();
    }

    @Test
    void refusesAClinicTokenAndGarbage() {
        TokenService.AccessToken clinicToken = clinic.issueAccessToken(adminId, UUID.randomUUID(), "OWNER");
        assertThat(admin.parse(clinicToken.token())).isEmpty();
        assertThat(admin.parse("not-a-token")).isEmpty();
        assertThat(admin.parse("")).isEmpty();
        assertThat(admin.parse("A".repeat(43))).isEmpty();
    }

    @Test
    void refusesAnUnsignedToken() {
        String unsigned = Jwts.builder().issuer("sahaj").audience().add("sahaj-admin").and()
                .subject(adminId.toString()).claim("sid", sessionId.toString()).claim("role", "PLATFORM_ADMIN").compact();
        assertThat(admin.parse(unsigned)).isEmpty();
    }

    @Test
    void theClinicParserRefusesAnAdminTokenEvenIfItWereSignedWithTheClinicKey() {
        // An admin token signed with the admin key cannot verify on the clinic key at all ...
        assertThat(clinic.parseAccessToken(admin.issue(adminId, sessionId, List.of("pwd", "otp")).token())).isEmpty();
        // ... and one that carries the admin audience is refused even under the clinic key.
        String forged = Jwts.builder().audience().add("sahaj-admin").and().subject(adminId.toString())
                .claim("tid", UUID.randomUUID().toString()).claim("role", "OWNER")
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(key(CLINIC_SECRET), Jwts.SIG.HS256).compact();
        assertThat(clinic.parseAccessToken(forged)).isEmpty();
        assertThat(clinic.parseSelectionToken(Jwts.builder().audience().add("sahaj-admin").and()
                .subject(adminId.toString()).claim("purpose", "select-clinic")
                .expiration(Date.from(NOW.plusSeconds(60))).signWith(key(CLINIC_SECRET), Jwts.SIG.HS256).compact()))
                .isEmpty();
    }

    @Test
    void aNormalClinicTokenStillWorksOnTheClinicParser() {
        UUID tenant = UUID.randomUUID();
        TokenService.AccessToken token = clinic.issueAccessToken(adminId, tenant, "OWNER");
        TokenService.AccessTokenClaims claims = clinic.parseAccessToken(token.token()).orElseThrow();
        assertThat(claims.tenantId()).isEqualTo(tenant);
        assertThat(clinic.parseSelectionToken(clinic.issueSelectionToken(adminId))).contains(adminId);
    }
}
