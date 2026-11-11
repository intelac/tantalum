package tantalum.shell.param;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tantalum.local.fs.PrivateFiles;

class SecretBoxTest {

    private static byte[] key(int fill) {
        byte[] key = new byte[32];
        Arrays.fill(key, (byte) fill);
        return key;
    }

    @TempDir
    Path data;

    @Test
    void sealsAndOpens() {
        SecretBox box = new SecretBox(key(1));
        SecretBox.Sealed sealed = box.seal("pässword-密码", "alice\nplugin\ndemo\ntoken");
        assertEquals(Optional.of("pässword-密码"), box.open(sealed, "alice\nplugin\ndemo\ntoken"));
        // 密文里找不到原文
        assertFalse(new String(sealed.cipher(), StandardCharsets.ISO_8859_1).contains("word"));
    }

    @Test
    void theSamePlainSealsDifferentlyEachTime() {
        SecretBox box = new SecretBox(key(1));
        SecretBox.Sealed first = box.seal("same", "a");
        SecretBox.Sealed second = box.seal("same", "a");
        assertFalse(Arrays.equals(first.nonce(), second.nonce()));
        assertFalse(Arrays.equals(first.cipher(), second.cipher()));
    }

    @Test
    void cannotOpenWithAnotherKeyAnotherRowOrAfterTampering() {
        SecretBox box = new SecretBox(key(1));
        SecretBox.Sealed sealed = box.seal("secret", "alice\nplugin\ndemo\ntoken");
        assertEquals(Optional.empty(), new SecretBox(key(2)).open(sealed, "alice\nplugin\ndemo\ntoken"));
        // 把 alice 的密文挪到 bob 的行
        assertEquals(Optional.empty(), box.open(sealed, "bob\nplugin\ndemo\ntoken"));
        byte[] tampered = sealed.cipher().clone();
        tampered[0] ^= 1;
        assertEquals(Optional.empty(), box.open(new SecretBox.Sealed(sealed.nonce(), tampered), "alice\nplugin\ndemo\ntoken"));
        assertEquals(Optional.empty(), box.open(new SecretBox.Sealed(new byte[0], sealed.cipher()), "alice\nplugin\ndemo\ntoken"));
    }

    @Test
    void theKeyFromTheEnvironmentWins() throws Exception {
        byte[] loaded = SecretKey.load(data, Base64.getEncoder().encodeToString(key(7)));
        assertTrue(Arrays.equals(key(7), loaded));
        assertFalse(Files.exists(data.resolve(SecretKey.FILE)));
    }

    @Test
    void theKeyFileIsGeneratedOnceAndPrivate() throws Exception {
        byte[] first = SecretKey.load(data, null);
        byte[] second = SecretKey.load(data, "");
        assertEquals(32, first.length);
        assertTrue(Arrays.equals(first, second));
        Path file = data.resolve(SecretKey.FILE);
        assumeTrue(PrivateFiles.posix(file));
        assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
    }

    @Test
    void aBadKeyFailsWithoutEchoingIt() {
        IllegalStateException notBase64 = assertThrows(IllegalStateException.class, () -> SecretKey.load(data, "not base64 !!"));
        assertFalse(notBase64.getMessage().contains("not base64"));
        String tooShort = Base64.getEncoder().encodeToString(new byte[16]);
        IllegalStateException wrongSize = assertThrows(IllegalStateException.class, () -> SecretKey.load(data, tooShort));
        assertFalse(wrongSize.getMessage().contains(tooShort));
    }
}
