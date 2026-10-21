import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The bytes waiting to go out on one connection, and the thread that writes
 * them.
 *
 * <p>Writing every line as it arrives costs a system call per line, and the
 * write happens on whichever thread is talking to the room — so one slow
 * client holds up everybody while its socket buffer drains. Queueing the
 * bytes and writing whatever is pending in one go fixes both: the room is
 * never blocked by a reader, and a burst goes out in a few writes instead of
 * one per line.
 *
 * <p>The transport decides what a message looks like on the wire: a chat
 * transport appends a newline, the WebSocket transport wraps the text in a
 * frame.
 */
final class Outbound {
    /**
     * How many bytes may pile up for one connection before it is given up on.
     * A client that cannot read this much backlog is not going to catch up.
     */
    private static final long MAX_QUEUED_BYTES = 2L * 1024 * 1024;

    /** How long the writer waits for something to write before looking again. */
    private static final long POLL_MS = 20;

    private final OutputStream out;
    private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
    private long queuedBytes;
    private volatile boolean closed;

    Outbound(OutputStream out) {
        this.out = out;
    }

    /** Starts the writer thread. */
    void start() {
        Thread.ofVirtual().name("outbound").start(this::pump);
    }

    /** Queues one line of text. */
    void send(String line) {
        send(text(line));
    }

    /** Queues raw bytes, for transports that frame their own messages. */
    void send(byte[] payload) {
        if (closed) {
            return;
        }
        if (queuedBytes + payload.length > MAX_QUEUED_BYTES) {
            // The other end is not reading fast enough. Dropping the
            // connection is better than letting the queue grow without end.
            close();
            return;
        }
        queuedBytes += payload.length;
        queue.add(payload);
    }

    /** Writes everything that is pending, over and over, until the socket dies. */
    private void pump() {
        ByteArrayOutputStream batch = new ByteArrayOutputStream();
        try {
            while (!closed) {
                byte[] first = queue.poll(POLL_MS, TimeUnit.MILLISECONDS);
                if (first == null) {
                    continue;
                }
                // Everything that is pending goes out in one write.
                batch.reset();
                batch.write(first);
                queuedBytes -= first.length;
                byte[] more;
                while ((more = queue.poll()) != null) {
                    batch.write(more);
                    queuedBytes -= more.length;
                }
                out.write(batch.toByteArray());
                out.flush();
            }
        } catch (IOException e) {
            // The connection is broken; closing it makes the transport notice.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            close();
        }
    }

    /** Closes the connection and stops the writer. */
    void close() {
        closed = true;
        queue.clear();
        queuedBytes = 0;
        try {
            out.close();
        } catch (IOException ignored) {
        }
    }

    /** How a plain text transport frames a line. */
    static byte[] text(String line) {
        return (line + "\n").getBytes(StandardCharsets.UTF_8);
    }
}
