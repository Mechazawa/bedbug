package at.ioexception.mcaddon.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** A saved identity key, read in either of the two forms OpenSSL writes and given its public half. */
class ServerIdentityTest {
    /** Written by `openssl ecparam -name secp384r1 -genkey -noout`; its public half by `openssl ec -pubout`. */
    private static final String SEC1 = """
        -----BEGIN EC PRIVATE KEY-----
        MIGkAgEBBDDGGss0g59UoaWpwKLovndRiiWy2Gbx1zJc/XU8xN6RD754HQ57fDW4
        gw4EjBLdUXugBwYFK4EEACKhZANiAARd+PwIJQB2S/NJ+Pncyvjk6pgTyZpy6wbF
        IDzR1CKcg7PwQqYrr6nM0wXUwc5Kfl+FMMmk6zIBaiOJDeSMxTHDRWuwFRYzk2MX
        7rg9YNJCkgXupEFK7a2FvOOn2PYVaAs=
        -----END EC PRIVATE KEY-----
        """;
    private static final String SEC1_PUBLIC =
        "MHYwEAYHKoZIzj0CAQYFK4EEACIDYgAEXfj8CCUAdkvzSfj53Mr45OqYE8macusGxSA80dQinI"
        + "Oz8EKmK6+pzNMF1MHOSn5fhTDJpOsyAWojiQ3kjMUxw0VrsBUWM5NjF+64PWDSQpIF7qRBSu2thbzjp9j2FWgL";

    @TempDir
    Path directory;

    @Test
    void aSec1KeyGetsThePublicHalfOpenSslGaveIt() throws Exception {
        assertEquals(SEC1_PUBLIC, ServerIdentity.read(write(SEC1)).publicText());
    }

    @Test
    void aPkcs8KeyGetsThePublicHalfItWasMadeWith() throws Exception {
        KeyPair keys = generate("secp384r1");
        assertEquals(Base64.getEncoder().encodeToString(keys.getPublic().getEncoded()),
            ServerIdentity.read(write(pkcs8(keys))).publicText());
    }

    @Test
    void aKeyOnAnotherCurveIsRefused() throws Exception {
        Path other = write(pkcs8(generate("secp256r1")));
        assertThrows(GeneralSecurityException.class, () -> ServerIdentity.read(other));
    }

    @Test
    void aFileWithNoKeyIsRefused() throws Exception {
        Path empty = write("not a key\n");
        assertThrows(IllegalArgumentException.class, () -> ServerIdentity.read(empty));
    }

    private Path write(String text) throws Exception {
        Path file = Files.createTempFile(directory, "key", ".pem");
        Files.writeString(file, text);
        return file;
    }

    private static KeyPair generate(String curve) throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec(curve));
        return generator.generateKeyPair();
    }

    private static String pkcs8(KeyPair keys) {
        return "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder().encodeToString(keys.getPrivate().getEncoded())
            + "\n-----END PRIVATE KEY-----\n";
    }
}
