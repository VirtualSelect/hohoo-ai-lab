import java.io.*;
import java.nio.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

/** Teaching journal: one local file, cooperative processes, bounded records.
 * The journal IS the business state; it cannot atomically commit remote effects.
 */
public final class Ledger {
    public static final class Entry {
        public final String key, body;
        public final int result;
        Entry(String key, String body, int result) {
            this.key = key; this.body = body; this.result = result;
        }
    }
    static final int MAGIC = 0x484F484F, MAX = 65536;
    static String hash(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder out = new StringBuilder();
        for (byte b : digest) out.append(String.format("%02x", b & 255));
        return out.toString();
    }
    static byte[] payload(String key, String body, int result) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeUTF(key); out.writeUTF(hash(body)); out.writeUTF(body); out.writeInt(result);
        return bytes.toByteArray();
    }
    static byte[] frame(String key, String body, int result) throws Exception {
        byte[] payload = payload(key, body, result);
        if (payload.length > MAX) throw new IOException("record too large");
        CRC32 crc = new CRC32(); crc.update(payload);
        return ByteBuffer.allocate(12 + payload.length).putInt(MAGIC).putInt(payload.length)
            .putInt((int) crc.getValue()).put(payload).array();
    }
    static LinkedHashMap<String, Entry> scan(RandomAccessFile file) throws Exception {
        LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
        file.seek(0);
        while (file.getFilePointer() < file.length()) {
            long start = file.getFilePointer();
            if (file.length() - start < 12) { file.setLength(start); file.getChannel().force(true); break; }
            int magic = file.readInt(), length = file.readInt(), expected = file.readInt();
            if (magic != MAGIC || length < 1 || length > MAX) throw new IOException("invalid frame header");
            if (file.length() - file.getFilePointer() < length) {
                file.setLength(start); file.getChannel().force(true); break;
            }
            byte[] bytes = new byte[length]; file.readFully(bytes);
            CRC32 crc = new CRC32(); crc.update(bytes);
            if ((int) crc.getValue() != expected) throw new IOException("checksum mismatch; manual recovery required");
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            String key = in.readUTF(), digest = in.readUTF(), body = in.readUTF(); int result = in.readInt();
            if (in.available() != 0 || !digest.equals(hash(body)) || entries.containsKey(key)
                || result != entries.size() + 1) throw new IOException("invalid record semantics");
            entries.put(key, new Entry(key, body, result));
        }
        return entries;
    }
    public static List<Entry> read(Path path) throws Exception {
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
             FileLock lock = file.getChannel().lock()) {
            return new ArrayList<>(scan(file).values());
        }
    }
    public static Entry apply(Path path, String key, String body, String fault) throws Exception {
        if (key.isEmpty() || key.length() > 200 || body.getBytes(StandardCharsets.UTF_8).length > 16000)
            throw new IllegalArgumentException("key/body outside limits");
        Files.createDirectories(path.toAbsolutePath().getParent());
        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "rw");
             FileLock lock = file.getChannel().lock()) {
            LinkedHashMap<String, Entry> entries = scan(file);
            Entry previous = entries.get(key);
            if (previous != null) {
                if (!previous.body.equals(body)) throw new IllegalArgumentException("KEY_CONFLICT");
                return previous;
            }
            byte[] bytes = frame(key, body, entries.size() + 1);
            if (fault.equals("before")) Runtime.getRuntime().halt(23);
            file.seek(file.length());
            if (fault.equals("partial")) {
                file.write(bytes, 0, bytes.length / 2); file.getChannel().force(true);
                Runtime.getRuntime().halt(23);
            }
            file.write(bytes); file.getChannel().force(true);
            if (fault.equals("after")) Runtime.getRuntime().halt(23);
            return new Entry(key, body, entries.size() + 1);
        }
    }
}
