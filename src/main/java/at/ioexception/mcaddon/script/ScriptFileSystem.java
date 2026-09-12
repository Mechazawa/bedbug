package at.ioexception.mcaddon.script;

import org.graalvm.polyglot.io.FileSystem;

import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessMode;
import java.nio.file.DirectoryStream;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.FileAttribute;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The only files a pack's scripts can see: the pack's own scripts and the `@minecraft/*` modules.
 *
 * Nothing reaches the real disk, so a pack cannot read the player's files whatever it asks for.
 */
final class ScriptFileSystem implements FileSystem {
    private final Map<String, byte[]> files = new LinkedHashMap<>();
    private Path workingDirectory = Paths.get("/");

    void put(String path, String content) {
        files.put(path, content.getBytes(StandardCharsets.UTF_8));
    }

    void put(String path, byte[] content) {
        files.put(path, content);
    }

    /**
     * Maps whatever absolute path the engine invents for a bare specifier back onto the module.
     *
     * GraalJS resolves `@minecraft/server` against a working directory an embedder cannot set, so the
     * request arrives as some absolute path ending in the specifier. Matching on the tail is what
     * makes the import resolve from any directory a pack's modules happen to live in.
     */
    private String key(Path path) {
        String absolute = path.isAbsolute() ? path.normalize().toString()
            : workingDirectory.resolve(path).normalize().toString();
        for (String name : files.keySet()) {
            if (name.startsWith("@") && (absolute.equals(name) || absolute.endsWith("/" + name))) return name;
        }
        return absolute;
    }

    @Override
    public Path parsePath(URI uri) {
        return Paths.get(uri);
    }

    @Override
    public Path parsePath(String path) {
        return Paths.get(path);
    }

    @Override
    public void checkAccess(Path path, Set<? extends AccessMode> modes, LinkOption... options)
        throws IOException {
        if (!files.containsKey(key(path))) throw new NoSuchFileException(path.toString());
    }

    @Override
    public void createDirectory(Path directory, FileAttribute<?>... attributes) throws IOException {
        throw new IOException("read only");
    }

    @Override
    public void delete(Path path) throws IOException {
        throw new IOException("read only");
    }

    @Override
    public SeekableByteChannel newByteChannel(Path path, Set<? extends OpenOption> options,
        FileAttribute<?>... attributes) throws IOException {
        byte[] content = files.get(key(path));
        if (content == null) throw new NoSuchFileException(path.toString());
        return new ReadOnlyChannel(content);
    }

    @Override
    public DirectoryStream<Path> newDirectoryStream(Path directory, DirectoryStream.Filter<? super Path> filter)
        throws IOException {
        throw new IOException("not listable");
    }

    @Override
    public void setCurrentWorkingDirectory(Path directory) {
        workingDirectory = directory;
    }

    @Override
    public Path toAbsolutePath(Path path) {
        return path.isAbsolute() ? path : workingDirectory.resolve(path);
    }

    @Override
    public Path toRealPath(Path path, LinkOption... options) {
        return toAbsolutePath(path).normalize();
    }

    @Override
    public Map<String, Object> readAttributes(Path path, String attributes, LinkOption... options)
        throws IOException {
        byte[] content = files.get(key(path));
        if (content == null) throw new NoSuchFileException(path.toString());

        Map<String, Object> result = new HashMap<>();
        result.put("isRegularFile", true);
        result.put("isDirectory", false);
        result.put("isSymbolicLink", false);
        result.put("isOther", false);
        result.put("size", (long) content.length);
        return result;
    }

    private static final class ReadOnlyChannel implements SeekableByteChannel {
        private final ByteBuffer buffer;
        private boolean open = true;

        ReadOnlyChannel(byte[] content) {
            this.buffer = ByteBuffer.wrap(content);
        }

        @Override
        public int read(ByteBuffer destination) {
            if (!buffer.hasRemaining()) return -1;
            int count = Math.min(destination.remaining(), buffer.remaining());
            byte[] chunk = new byte[count];
            buffer.get(chunk);
            destination.put(chunk);
            return count;
        }

        @Override
        public int write(ByteBuffer source) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long position() {
            return buffer.position();
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            buffer.position((int) newPosition);
            return this;
        }

        @Override
        public long size() {
            return buffer.capacity();
        }

        @Override
        public SeekableByteChannel truncate(long size) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
