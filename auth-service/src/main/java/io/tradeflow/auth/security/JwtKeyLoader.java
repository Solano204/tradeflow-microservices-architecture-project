
package io.tradeflow.auth.security;

import io.tradeflow.auth.dto.AuthDtos.JwksResponse;
import io.tradeflow.auth.dto.AuthDtos.JwksResponse.JwkKey;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.List;

/**
 * JwtKeyLoader — loads RS256 key pair for JWT signing and verification.
 *
 * In production: keys are mounted as Kubernetes Secrets.
 * In development: falls back to generating an ephemeral key pair on startup
 *                 (different key on every restart — not suitable for multi-pod).
 *
 * The public key is exposed via /.well-known/jwks.json (JWKS endpoint).
 * The private key NEVER leaves this service — only used to sign tokens.
 *
 * JWKS caching: the JWKS response is built once at startup and cached in-memory.
 * All other services cache their copy for ~1 hour. This is what makes the
 * auth architecture scalable — no Auth Service call per request.
 */
@ApplicationScoped
public class JwtKeyLoader {

    private static final Logger LOG = Logger.getLogger(JwtKeyLoader.class);

    @ConfigProperty(name = "smallrye.jwt.sign.key.location", defaultValue = "keys/jwt-private-key.pem")
    String privateKeyPath;

    @ConfigProperty(name = "mp.jwt.verify.publickey.location", defaultValue = "keys/jwt-public-key.pem")
    String publicKeyPath;

    @ConfigProperty(name = "tradeflow.jwt.kid", defaultValue = "auth-key-2024-v1")
    String keyId;

    private RSAPrivateKey privateKey;
    private RSAPublicKey publicKey;
    private JwksResponse cachedJwks;

    @PostConstruct
    public void init() {
        try {
            loadKeysFromClasspath();
            buildJwksCache();
            LOG.info("JWT RS256 key pair loaded successfully. KID: " + keyId);
        } catch (Exception e) {
            LOG.warn("Could not load JWT keys from files, generating ephemeral key pair for development: "
                    + e.getMessage());
            generateEphemeralKeyPair();
            buildJwksCache();
            LOG.warn("⚠️  EPHEMERAL KEY PAIR IN USE — tokens will not survive service restarts!");
        }
    }

    /**
     * Attempt to load PEM-encoded keys from the classpath (or filesystem path).
     */
    private void loadKeysFromClasspath() throws Exception {
        // Try classpath first, then absolute path
        byte[] privateKeyBytes = loadPemBytes(privateKeyPath, "PRIVATE KEY");
        byte[] publicKeyBytes = loadPemBytes(publicKeyPath, "PUBLIC KEY");

        KeyFactory kf = KeyFactory.getInstance("RSA");
        this.privateKey = (RSAPrivateKey) kf.generatePrivate(new PKCS8EncodedKeySpec(privateKeyBytes));
        this.publicKey  = (RSAPublicKey)  kf.generatePublic(new X509EncodedKeySpec(publicKeyBytes));
    }

    private byte[] loadPemBytes(String path, String type) throws Exception {
        String pem;
        // Try classpath
        InputStream is = JwtKeyLoader.class.getClassLoader().getResourceAsStream(path);
        if (is != null) {
            pem = new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } else {
            // Try filesystem (Kubernetes Secret mount)
            java.nio.file.Path fsPath = java.nio.file.Path.of(path);
            pem = java.nio.file.Files.readString(fsPath);
        }
        // Strip PEM headers and decode
        String cleaned = pem
                .replace("-----BEGIN " + type + "-----", "")
                .replace("-----END " + type + "-----", "")
                .replaceAll("\\s", "");
        return Base64.getDecoder().decode(cleaned);
    }

    private void generateEphemeralKeyPair() {
        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            KeyPair kp = gen.generateKeyPair();
            this.privateKey = (RSAPrivateKey) kp.getPrivate();
            this.publicKey  = (RSAPublicKey)  kp.getPublic();
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate ephemeral RSA key pair", e);
        }
    }

    /**
     * Build the JWKS response once at startup.
     * Cached in-memory — immutable for the lifetime of the Pod.
     *
     * RFC 7517 format. The 'n' and 'e' are base64url-encoded RSA components.
     */
    private void buildJwksCache() {
        BigInteger modulus  = publicKey.getModulus();
        BigInteger exponent = publicKey.getPublicExponent();

        String n = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedByteArray(modulus));
        String e = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(toUnsignedByteArray(exponent));

        JwkKey jwk = new JwkKey("RSA", "sig", "RS256", keyId, n, e);
        this.cachedJwks = new JwksResponse(List.of(jwk));
    }

    /**
     * Convert BigInteger to unsigned byte array (strips leading sign byte if present).
     */
    private byte[] toUnsignedByteArray(BigInteger value) {
        byte[] bytes = value.toByteArray();
        if (bytes[0] == 0 && bytes.length > 1) {
            byte[] tmp = new byte[bytes.length - 1];
            System.arraycopy(bytes, 1, tmp, 0, tmp.length);
            return tmp;
        }
        return bytes;
    }

    // ----------------------------------------------------------------
    // Public accessors
    // ----------------------------------------------------------------

    public RSAPrivateKey getPrivateKey() {
        return privateKey;
    }

    public RSAPublicKey getPublicKey() {
        return publicKey;
    }

    /**
     * Returns the cached JWKS response.
     * Called by the JWKS endpoint — no computation on hot path.
     */
    public JwksResponse getJwks() {
        return cachedJwks;
    }

    public String getKeyId() {
        return keyId;
    }
}
