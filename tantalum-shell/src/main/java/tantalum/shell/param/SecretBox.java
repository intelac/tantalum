package tantalum.shell.param;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 插件密码的加解密：AES-256-GCM，每次加密用新的随机 nonce。{@code bound} 是这份密文属于谁（用户、插件、键），
 * 一起算进校验：把密文挪到别的行就解不开。
 */
public final class SecretBox {

    private static final int NONCE_BYTES = 12;
    private static final int TAG_BITS = 128;

    /** 一份密文。 */
    public record Sealed(byte[] nonce, byte[] cipher) {}

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public SecretBox(byte[] key) {
        if (key.length != SecretKey.BYTES) {
            throw new IllegalArgumentException("key must be " + SecretKey.BYTES + " bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    public Sealed seal(String plain, String bound) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        try {
            return new Sealed(nonce, cipher(Cipher.ENCRYPT_MODE, nonce, bound).doFinal(plain.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("cannot encrypt a plugin secret", impossible);
        }
    }

    /** 解不开（密钥换了、密文被动过、不是这一行的）返回空。 */
    public Optional<String> open(Sealed sealed, String bound) {
        try {
            return Optional.of(new String(cipher(Cipher.DECRYPT_MODE, sealed.nonce(), bound).doFinal(sealed.cipher()),
                    StandardCharsets.UTF_8));
        } catch (GeneralSecurityException | IllegalArgumentException cannotOpen) {
            return Optional.empty();
        }
    }

    private Cipher cipher(int mode, byte[] nonce, String bound) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(mode, key, new GCMParameterSpec(TAG_BITS, nonce));
        cipher.updateAAD(bound.getBytes(StandardCharsets.UTF_8));
        return cipher;
    }
}
