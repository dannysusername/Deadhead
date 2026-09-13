package deadhead.user;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Encrypts the API keys pilots paste in, so the database never holds one in
 * the clear. AES-256-GCM: authenticated, so a tampered ciphertext fails to
 * decrypt rather than returning garbage.
 *
 * The master key comes from DEADHEAD_SECRET_KEY (base64, 32 bytes). With none
 * set — a laptop, a test run — one is generated into data/secret.key with
 * owner-only permissions. That file is gitignored, and it is NOT a substitute
 * for a real secret in production: it sits next to the database it protects,
 * so anyone who can steal one can steal both. Set the env var on the dyno.
 */
@Service
public class Secrets {

    private static final String ALGORITHM = "AES/GCM/NoPadding";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private static final Path KEY_FILE = Path.of("data/secret.key");

    private final SecretKey master;
    private final SecureRandom random = new SecureRandom();

    public Secrets(@Value("${deadhead.secret.key:}") String configured) {
        String base64 = !configured.isBlank() ? configured : System.getenv("DEADHEAD_SECRET_KEY");
        byte[] raw = (base64 == null || base64.isBlank())
            ? fromKeyFile()
            : Base64.getDecoder().decode(base64.trim());

        if (raw.length != 32) {
            throw new IllegalStateException(
                "DEADHEAD_SECRET_KEY must be 32 bytes, base64-encoded (got " + raw.length + ").");
        }
        this.master = new SecretKeySpec(raw, "AES");
    }

    /** Generate once, reuse forever: losing this file makes every stored key unreadable. */
    private byte[] fromKeyFile() {
        try {
            if (Files.exists(KEY_FILE)) {
                return Base64.getDecoder().decode(Files.readString(KEY_FILE).trim());
            }
            byte[] fresh = new byte[32];
            new SecureRandom().nextBytes(fresh);
            Files.createDirectories(KEY_FILE.getParent());
            Files.writeString(KEY_FILE, Base64.getEncoder().encodeToString(fresh));
            try {
                Files.setPosixFilePermissions(KEY_FILE, PosixFilePermissions.fromString("rw-------"));
            } catch (UnsupportedOperationException notPosix) { /* Windows: ACLs, not modes */ }
            System.out.println("generated a local encryption key at " + KEY_FILE
                             + " — set DEADHEAD_SECRET_KEY in production instead");
            return fresh;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** iv || ciphertext || tag, base64. The iv is fresh per call and travels with the value. */
    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher c = Cipher.getInstance(ALGORITHM);
            c.init(Cipher.ENCRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, iv));
            byte[] body = c.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(body, 0, out, iv.length, body.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("could not encrypt the value", e);
        }
    }

    /**
     * @return the plaintext, or null if this ciphertext can't be read — which
     *         happens when the master key changed. The caller treats that as
     *         "no key stored" so a rotated secret degrades to "please re-enter
     *         it" rather than a 500 on every import.
     */
    public String decrypt(String stored) {
        try {
            byte[] all = Base64.getDecoder().decode(stored);
            if (all.length <= IV_BYTES) return null;
            byte[] iv = java.util.Arrays.copyOfRange(all, 0, IV_BYTES);
            byte[] body = java.util.Arrays.copyOfRange(all, IV_BYTES, all.length);

            Cipher c = Cipher.getInstance(ALGORITHM);
            c.init(Cipher.DECRYPT_MODE, master, new GCMParameterSpec(TAG_BITS, iv));
            return new String(c.doFinal(body), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception unreadable) {
            return null;
        }
    }
}
