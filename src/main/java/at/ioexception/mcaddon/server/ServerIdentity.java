package at.ioexception.mcaddon.server;

import at.ioexception.mcaddon.McAddon;
import at.ioexception.mcaddon.network.Channels;
import at.ioexception.mcaddon.platform.Platform;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.AlgorithmParameters;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.ECPrivateKeySpec;
import java.security.spec.ECPublicKeySpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The P-384 key a server identifies itself to its clients with, and `serveridentity`, which saves,
 * deletes and reports the copy kept at `keys/server_identity_key.pem` in the server's directory.
 *
 * A server starts with the saved key, or with a key it makes and holds in memory alone, so without a
 * saved key it is a different server to its clients each time it starts. The public half goes to
 * every client running this mod as it joins, which keeps the key it trusts per server address.
 */
public final class ServerIdentity {
    public static final String KIND = "server_identity";

    private static final String CURVE_NAME = "secp384r1";
    /** The DER content of secp384r1's object identifier, 1.3.132.0.34. */
    private static final byte[] CURVE_OID = {0x2B, (byte) 0x81, 0x04, 0x00, 0x22};
    private static final Pattern PEM = Pattern.compile(
        "-----BEGIN (EC PRIVATE KEY|PRIVATE KEY)-----(.*?)-----END \\1-----", Pattern.DOTALL);
    private static final ECParameterSpec CURVE = curve();

    private static ServerIdentity running;

    private final PrivateKey privateKey;
    private final PublicKey publicKey;

    private ServerIdentity(KeyPair keys) {
        this.privateKey = keys.getPrivate();
        this.publicKey = keys.getPublic();
    }

    public static Path savedKey() {
        return FabricLoader.getInstance().getGameDir().resolve("keys").resolve("server_identity_key.pem");
    }

    /** Takes the saved key as this session's identity, or a generated one when there is none or it is unusable. */
    public static void start() {
        Path saved = savedKey();
        if (Files.exists(saved)) {
            try {
                running = read(saved);
                return;
            } catch (IOException | GeneralSecurityException | IllegalArgumentException unusable) {
                McAddon.LOGGER.error("The server identity key at {} is not an unencrypted P-384 private key ({}),"
                    + " so this session uses a generated key.", saved, unusable.getMessage());
            }
        }
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(CURVE_NAME));
            running = new ServerIdentity(generator.generateKeyPair());
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException("This Java runtime cannot make a P-384 key.", missing);
        }
    }

    public static ServerIdentity running() {
        return running;
    }

    /** Reads an unencrypted P-384 private key, SEC1 (`EC PRIVATE KEY`) or PKCS#8 (`PRIVATE KEY`). */
    public static ServerIdentity read(Path file) throws IOException, GeneralSecurityException {
        Matcher pem = PEM.matcher(Files.readString(file, StandardCharsets.US_ASCII));
        if (!pem.find()) throw new IllegalArgumentException("no EC PRIVATE KEY or PRIVATE KEY block");
        byte[] der = Base64.getMimeDecoder().decode(pem.group(2).trim());
        BigInteger secret;
        if (pem.group(1).equals("PRIVATE KEY")) {
            PrivateKey key = KeyFactory.getInstance("EC").generatePrivate(new PKCS8EncodedKeySpec(der));
            if (!(key instanceof ECPrivateKey elliptic) || !elliptic.getParams().getCurve().equals(CURVE.getCurve())) {
                throw new InvalidKeyException("the key is not on " + CURVE_NAME);
            }
            secret = elliptic.getS();
        } else {
            secret = Der.sec1Secret(der);
        }
        if (secret.signum() <= 0 || secret.compareTo(CURVE.getOrder()) >= 0) {
            throw new InvalidKeyException("the private value is outside the curve's order");
        }
        KeyFactory factory = KeyFactory.getInstance("EC");
        return new ServerIdentity(new KeyPair(
            factory.generatePublic(new ECPublicKeySpec(times(CURVE.getGenerator(), secret), CURVE)),
            factory.generatePrivate(new ECPrivateKeySpec(secret, CURVE))));
    }

    /** The public half as its X.509 encoding in base64, which is what a client compares. */
    public String publicText() {
        return Base64.getEncoder().encodeToString(publicKey.getEncoded());
    }

    public static void joined(ServerPlayer player) {
        if (running != null) Channels.sendInstruction(player, KIND, running.publicText());
    }

    public static void registerCommand(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("serveridentity")
            .requires(source -> Platform.allowedAt(source, 4))
            .then(Commands.literal("save").executes(ServerIdentity::save))
            .then(Commands.literal("delete").executes(ServerIdentity::delete))
            .then(Commands.literal("status").executes(ServerIdentity::status)));
    }

    private static int save(CommandContext<CommandSourceStack> command) {
        Path saved = savedKey();
        try {
            Files.createDirectories(saved.getParent());
            String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(running.privateKey.getEncoded());
            Files.writeString(saved, "-----BEGIN PRIVATE KEY-----\n" + body + "\n-----END PRIVATE KEY-----\n",
                StandardCharsets.US_ASCII);
            if (saved.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(saved, PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException unwritable) {
            command.getSource().sendFailure(Component.literal("Could not save the server identity key to "
                + saved + ": " + unwritable.getMessage()));
            return 0;
        }
        command.getSource().sendSuccess(() -> Component.literal("Saved the server identity key to " + saved + "."),
            false);
        return 1;
    }

    private static int delete(CommandContext<CommandSourceStack> command) {
        Path saved = savedKey();
        try {
            if (!Files.deleteIfExists(saved)) {
                command.getSource().sendFailure(Component.literal("There is no saved server identity key."));
                return 0;
            }
        } catch (IOException undeletable) {
            command.getSource().sendFailure(Component.literal("Could not delete the saved server identity key: "
                + undeletable.getMessage()));
            return 0;
        }
        command.getSource().sendSuccess(() -> Component.literal("Deleted the saved server identity key. The server"
            + " keeps its identity until it restarts with a newly generated key."), false);
        return 1;
    }

    private static int status(CommandContext<CommandSourceStack> command) {
        boolean available = Files.isRegularFile(savedKey());
        command.getSource().sendSuccess(() -> Component.literal(available
            ? "A saved server identity key is available at " + savedKey() + "."
            : "No saved server identity key is available. The server uses a generated key until one is saved."),
            false);
        return available ? 1 : 0;
    }

    /** The scalar multiple of a point on secp384r1, by doubling and adding in affine coordinates. */
    private static ECPoint times(ECPoint point, BigInteger scalar) {
        ECPoint sum = ECPoint.POINT_INFINITY;
        for (int bit = scalar.bitLength() - 1; bit >= 0; bit--) {
            sum = plus(sum, sum);
            if (scalar.testBit(bit)) sum = plus(sum, point);
        }
        return sum;
    }

    private static ECPoint plus(ECPoint first, ECPoint second) {
        if (first.equals(ECPoint.POINT_INFINITY)) return second;
        if (second.equals(ECPoint.POINT_INFINITY)) return first;
        BigInteger prime = ((ECFieldFp) CURVE.getCurve().getField()).getP();
        BigInteger x1 = first.getAffineX();
        BigInteger y1 = first.getAffineY();
        BigInteger x2 = second.getAffineX();
        BigInteger y2 = second.getAffineY();
        BigInteger slope;
        if (x1.equals(x2)) {
            if (!y1.equals(y2) || y1.signum() == 0) return ECPoint.POINT_INFINITY;
            slope = x1.pow(2).multiply(BigInteger.valueOf(3)).add(CURVE.getCurve().getA())
                .multiply(y1.shiftLeft(1).modInverse(prime));
        } else {
            slope = y2.subtract(y1).multiply(x2.subtract(x1).modInverse(prime));
        }
        slope = slope.mod(prime);
        BigInteger x3 = slope.pow(2).subtract(x1).subtract(x2).mod(prime);
        return new ECPoint(x3, slope.multiply(x1.subtract(x3)).subtract(y1).mod(prime));
    }

    private static ECParameterSpec curve() {
        try {
            AlgorithmParameters parameters = AlgorithmParameters.getInstance("EC");
            parameters.init(new ECGenParameterSpec(CURVE_NAME));
            return parameters.getParameterSpec(ECParameterSpec.class);
        } catch (GeneralSecurityException missing) {
            throw new IllegalStateException("This Java runtime does not know " + CURVE_NAME + ".", missing);
        }
    }

    /** One DER element: its tag and where its content lies in the encoding. */
    private record Der(int tag, byte[] encoding, int start, int end) {
        static Der at(byte[] encoding, int offset) {
            if (offset + 2 > encoding.length) throw new IllegalArgumentException("the key's DER ends early");
            int length = encoding[offset + 1] & 0xFF;
            int start = offset + 2;
            if (length > 0x7F) {
                int bytes = length & 0x7F;
                length = 0;
                for (int i = 0; i < bytes; i++) length = (length << 8) | (encoding[start++] & 0xFF);
            }
            if (length < 0 || start + length > encoding.length) {
                throw new IllegalArgumentException("the key's DER ends early");
            }
            return new Der(encoding[offset] & 0xFF, encoding, start, start + length);
        }

        Der expect(int wanted) {
            if (tag != wanted) throw new IllegalArgumentException("the key's DER is not a SEC1 private key");
            return this;
        }

        Der first() {
            return at(encoding, start);
        }

        Der next() {
            return at(encoding, end);
        }

        byte[] content() {
            return Arrays.copyOfRange(encoding, start, end);
        }

        /**
         * The private value of a SEC1 `ECPrivateKey`: a sequence of version 1, the value, and the curve
         * when the key states one, which has to be secp384r1.
         */
        static BigInteger sec1Secret(byte[] der) {
            Der key = at(der, 0).expect(0x30);
            Der version = key.first().expect(0x02);
            if (!Arrays.equals(version.content(), new byte[] {1})) {
                throw new IllegalArgumentException("the SEC1 key is not version 1");
            }
            Der secret = version.next().expect(0x04);
            if (secret.end < key.end) {
                Der parameters = secret.next();
                if (parameters.tag == 0xA0 && !Arrays.equals(parameters.first().expect(0x06).content(), CURVE_OID)) {
                    throw new IllegalArgumentException("the key is not on " + CURVE_NAME);
                }
            }
            return new BigInteger(1, secret.content());
        }
    }
}
