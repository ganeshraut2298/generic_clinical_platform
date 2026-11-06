package com.sahaj.platform.devtools.admin;

import com.sahaj.platform.application.admin.AdminProvisioning;
import com.sahaj.platform.application.admin.AdminPasswordHasher;
import com.sahaj.platform.application.admin.AdminAuthService;
import com.sahaj.platform.application.admin.Totp;
import com.sahaj.platform.application.admin.TotpEnrolmentText;
import com.sahaj.platform.application.admin.TotpSecretCipher;
import com.sahaj.platform.application.admin.TotpSecrets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the one sample admin (ADR-008 D5). One transaction; the response is built inside it, so a
 * failure while building rolls the admin back instead of leaving an admin nobody knows the login of.
 * A {@code false} from the database (an admin already exists) is a normal return, so the transaction
 * commits and the audit row of the refusal survives.
 *
 * <p>Never logs the password, the secret, the {@code otpauth://} URI or the email; only the admin id.
 */
public class DevSampleAdminService {

    private static final Logger log = LoggerFactory.getLogger(DevSampleAdminService.class);

    static final String EMAIL = "sample-admin@example.test";
    static final String FULL_NAME = "Sample admin";
    static final String SIGN_IN_PATH = "/admin/sign-in";

    private final DevSampleAdminRepository repository;
    private final AdminProvisioning provisioning;
    private final TotpSecretCipher cipher;
    private final SecureRandom random;
    private final boolean totpDevBypassOn;

    DevSampleAdminService(DevSampleAdminRepository repository, AdminPasswordHasher hasher, TotpSecretCipher cipher,
                          SecureRandom random, boolean totpDevBypassOn) {
        this.repository = repository;
        this.provisioning = new AdminProvisioning(hasher);
        this.cipher = cipher;
        this.random = random;
        this.totpDevBypassOn = totpDevBypassOn;
    }

    /**
     * @return the one-time response, or null when an admin already exists (the caller answers the empty 404)
     * @throws DevSampleAdminUnavailableException when another admin was committed first (23505)
     */
    @Transactional
    public DevSampleAdminResponse create(String remoteAddress, String requestId) {
        UUID adminId = UUID.randomUUID();
        String password = DevPasswordGenerator.generate(random);
        AdminProvisioning.PreparedAdmin prepared = provisioning.prepare(EMAIL, FULL_NAME, password);
        byte[] secret = TotpSecrets.newSecret(random);
        try {
            byte[] sealed = cipher.encrypt(secret, adminId, TotpSecretCipher.CURRENT_KEY_VERSION);
            boolean created = repository.createSampleAdmin(adminId, prepared.passwordHash(), sealed,
                    TotpSecretCipher.CURRENT_KEY_VERSION, remoteAddress, requestId);
            if (!created) {
                return null;
            }
            TotpEnrolmentText text = TotpEnrolmentText.of(EMAIL, secret);
            log.info("Sample admin created adminId={}", adminId);
            return new DevSampleAdminResponse(adminId, EMAIL, FULL_NAME, password, text.secretKey(),
                    text.otpauthUri(), EMAIL, AdminAuthService.ISSUER, Totp.DIGITS, Totp.PERIOD_SECONDS, "SHA1",
                    true, totpDevBypassOn, SIGN_IN_PATH);
        } finally {
            Arrays.fill(secret, (byte) 0);
        }
    }
}
