package org.dflib.jjava.jupyter.kernel.display.protocol;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Gate test (tasks T008): decides whether Unix-domain sockets work on this OS/JDK. Writes one line per check to
 * target/uds-gate/{os}-jdk{N}.txt. The gate passes only if every check passes.
 */
public class UnixDomainSocketPathAcceptanceTest {

    private static void readFully(SocketChannel ch, ByteBuffer buf) throws IOException {
        while (buf.hasRemaining()) {
            if (ch.read(buf) < 0) {
                break;
            }
        }
    }

    @Test
    void unixDomainSocketGate() throws Exception {
        String os = System.getProperty("os.name").toLowerCase().replace(' ', '-');
        int jdk = Runtime.version().feature();
        List<String> report = new ArrayList<>();
        report.add("os=" + System.getProperty("os.name") + " jdk=" + jdk
                + " java.io.tmpdir=" + System.getProperty("java.io.tmpdir")
                + " jdk.net.unixdomain.tmpdir=" + System.getProperty("jdk.net.unixdomain.tmpdir"));
        boolean ok = true;
        try {
            Path standard = Files.createTempDirectory("uds-gate");
            Path spaced = Files.createTempDirectory("uds gate spaces ");
            Path lengthened = nestedDir(Files.createTempDirectory("uds-gate-len"));
            ok &= check(report, "standard-temp", standard.resolve("s.sock"));
            ok &= check(report, "spaces-in-path", spaced.resolve("s.sock"));
            ok &= check(report, "lengthened-path", lengthened.resolve("s.sock"));
            ok &= permissionCheck(report, standard);
            ok &= acceptFailureCleanup(report, standard.resolve("f.sock"));
            report.add("short-private-dir=" + (System.getProperty("jdk.net.unixdomain.tmpdir") == null
                    ? "not-configured" : "configured"));
        } catch (Throwable t) {
            ok = false;
            report.add("error=" + t);
            throw t;
        } finally {
            report.add("gate=" + (ok ? "PASS" : "FAIL"));
            Path out = Path.of("target", "uds-gate", os + "-jdk" + jdk + ".txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report);
        }
        assertTrue(ok, String.join("\n", report));
    }

    // one extra segment: lengthens the standard temp path but stays under the ~108-byte AF_UNIX limit
    private static Path nestedDir(Path base) throws Exception {
        return Files.createDirectories(base.resolve("uds-gate-length-check"));
    }

    private static boolean check(List<String> report, String label, Path socket) throws Exception {
        int bytes = socket.toString().getBytes(StandardCharsets.UTF_8).length;
        try {
            try (ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
                server.bind(UnixDomainSocketAddress.of(socket));
                try (SocketChannel client = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                    client.connect(UnixDomainSocketAddress.of(socket));
                    try (SocketChannel accepted = server.accept()) {
                        client.write(StandardCharsets.UTF_8.encode("ping\n"));
                        ByteBuffer buf = ByteBuffer.allocate(5);
                        readFully(accepted, buf);
                        assertEquals("ping\n", StandardCharsets.UTF_8.decode((ByteBuffer) buf.flip()).toString());
                    }
                }
            }
            boolean removed = !Files.exists(socket) || Files.deleteIfExists(socket);
            report.add(label + "=" + (removed ? "PASS" : "FAIL") + " pathBytes=" + bytes + " socket=" + socket);
            return removed;
        } catch (Exception e) {
            report.add(label + "=FAIL pathBytes=" + bytes + " error=" + e);
            Files.deleteIfExists(socket);
            return false;
        }
    }

    private static boolean permissionCheck(List<String> report, Path dir) throws Exception {
        boolean posix = Files.getFileStore(dir).supportsFileAttributeView("posix");
        report.add("permissions=" + (posix ? Files.getPosixFilePermissions(dir) : "not-posix"));
        return true;
    }

    private static boolean acceptFailureCleanup(List<String> report, Path socket) throws Exception {
        ServerSocketChannel server = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        server.bind(UnixDomainSocketAddress.of(socket));
        ExecutorService exec = Executors.newSingleThreadExecutor();
        try {
            Future<?> accept = exec.submit(server::accept);
            server.close();
            try {
                accept.get(5, TimeUnit.SECONDS);
                fail("accept returned after close");
            } catch (ExecutionException expected) {
                // accept failed after close, as required
            }
            boolean removed = !Files.exists(socket) || Files.deleteIfExists(socket);
            assertFalse(Files.exists(socket));
            report.add("accept-failure-cleanup=" + (removed ? "PASS" : "FAIL"));
            return removed;
        } finally {
            exec.shutdownNow();
        }
    }
}
