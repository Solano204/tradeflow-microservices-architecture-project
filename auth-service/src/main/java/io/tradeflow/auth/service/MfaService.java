
package io.tradeflow.auth.service;

import dev.samstevens.totp.code.*;
import dev.samstevens.totp.exceptions.QrGenerationException;
import dev.samstevens.totp.qr.QrData;
import dev.samstevens.totp.qr.ZxingPngQrGenerator;
import dev.samstevens.totp.secret.DefaultSecretGenerator;
import dev.samstevens.totp.secret.SecretGenerator;
import dev.samstevens.totp.time.SystemTimeProvider;
import dev.samstevens.totp.time.TimeProvider;
import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

import java.util.Base64;

/**
 * MfaService — TOTP (Time-based One-Time Password) MFA support.
 *
 * RFC 6238 TOTP implementation using the java-totp library.
 *
 * MFA Flow:
 * 1. User enables MFA via their profile
 * 2. Service generates a 32-char secret and returns QR code data URL
 * 3. User scans with authenticator app (Google Authenticator, Authy, etc.)
 * 4. On subsequent logins: user provides 6-digit TOTP code
 * 5. Service validates TOTP code against stored secret
 *
 * The MFA secret is stored in auth_users.mfa_secret (should be encrypted at rest
 * in production — use column-level encryption or Vault Transit).
 */
@ApplicationScoped
public class MfaService {

    private static final Logger LOG = Logger.getLogger(MfaService.class);

    private final SecretGenerator secretGenerator = new DefaultSecretGenerator(32);
    private final TimeProvider timeProvider = new SystemTimeProvider();

    /**
     * Generate a new TOTP secret for MFA enrollment.
     *
     * @return base32-encoded secret (to be stored in auth_users.mfa_secret)
     */
    public String generateSecret() {
        return secretGenerator.generate();
    }

    /**
     * Generate a QR code data URL for the authenticator app setup screen.
     *
     * @param secret  the TOTP secret (from generateSecret())
     * @param email   the user's email (shown as account label in authenticator)
     * @return Base64-encoded PNG image as data: URI
     */
    public String generateQrCodeDataUrl(String secret, String email) {
        QrData data = new QrData.Builder()
                .label(email)
                .secret(secret)
                .issuer("TradeFlow")
                .algorithm(HashingAlgorithm.SHA1)
                .digits(6)
                .period(30)
                .build();

        try {
            ZxingPngQrGenerator generator = new ZxingPngQrGenerator();
            byte[] qrBytes = generator.generate(data);
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(qrBytes);
        } catch (QrGenerationException e) {
            LOG.error("Failed to generate QR code", e);
            throw new RuntimeException("QR code generation failed", e);
        }
    }

    /**
     * Validate a 6-digit TOTP code against a stored secret.
     *
     * Allows ±1 time step (30s window) to account for clock drift.
     *
     * @param secret  the stored TOTP secret (auth_users.mfa_secret)
     * @param code    the 6-digit code submitted by the user
     * @return true if the code is valid within the time window
     */
    public boolean verifyCode(String secret, String code) {
        if (secret == null || code == null || code.isBlank()) {
            return false;
        }
        try {
            CodeGenerator codeGenerator = new DefaultCodeGenerator(HashingAlgorithm.SHA1, 6);
            CodeVerifier verifier = new DefaultCodeVerifier(codeGenerator, timeProvider);
            // Allow 1 period (30s) of clock skew in either direction
            ((DefaultCodeVerifier) verifier).setTimePeriod(30);
            ((DefaultCodeVerifier) verifier).setAllowedTimePeriodDiscrepancy(1);
            return verifier.isValidCode(secret, code);
        } catch (Exception e) {
            LOG.warnf("MFA code verification failed: %s", e.getMessage());
            return false;
        }
    }
}
