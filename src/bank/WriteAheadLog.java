package bank;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/**
 * Append-only write-ahead log.
 *
 * Record layout on disk:   [int payloadLength][int crc32(payload)][payload bytes]
 *
 * append() writes the record and then calls FileChannel.force(true), which asks the OS to
 * flush the data AND file metadata to the physical disk. Only after force() returns do we
 * consider the operation "durable" and let the bank change its in-memory state.
 *
 * recover() reads the log from the start. A record that is cut off (crash mid-write) or
 * whose CRC does not match is treated as the end of the valid log, and the file is
 * truncated there so new records are appended after the last good one.
 */
public class WriteAheadLog implements AutoCloseable {
    private static final int HEADER_BYTES = 8;
    private static final int MAX_PAYLOAD = 1 << 20; // sanity limit: 1 MB per record

    private final FileChannel channel;

    public WriteAheadLog(Path path) throws IOException {
        this.channel = FileChannel.open(path,
                StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
    }

    /** Durably appends one record. Synchronized so records never interleave. */
    public synchronized void append(byte[] payload) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + payload.length);
        buf.putInt(payload.length);
        buf.putInt(crc(payload));
        buf.put(payload);
        buf.flip();
        long pos = channel.size();
        while (buf.hasRemaining()) {
            pos += channel.write(buf, pos);
        }
        channel.force(true); // the "write-ahead" guarantee: on disk before we apply it
    }

    /**
     * Replays every valid record in order. Returns the number of records replayed.
     * Any torn/corrupt tail is truncated away.
     */
    public synchronized int recover(Consumer<byte[]> handler) throws IOException {
        long size = channel.size();
        long pos = 0;
        int count = 0;
        while (pos + HEADER_BYTES <= size) {
            ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES);
            if (!readFully(header, pos)) break;
            header.flip();
            int len = header.getInt();
            int expectedCrc = header.getInt();
            if (len < 0 || len > MAX_PAYLOAD || pos + HEADER_BYTES + len > size) break; // torn write
            ByteBuffer body = ByteBuffer.allocate(len);
            if (!readFully(body, pos + HEADER_BYTES)) break;
            byte[] payload = body.array();
            if (crc(payload) != expectedCrc) break; // corrupt record
            handler.accept(payload);
            pos += HEADER_BYTES + len;
            count++;
        }
        if (pos < size) {
            channel.truncate(pos);
            channel.force(true);
        }
        return count;
    }

    private boolean readFully(ByteBuffer buf, long position) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf, position + buf.position());
            if (n < 0) return false;
        }
        return true;
    }

    private static int crc(byte[] data) {
        CRC32 c = new CRC32();
        c.update(data);
        return (int) c.getValue();
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
