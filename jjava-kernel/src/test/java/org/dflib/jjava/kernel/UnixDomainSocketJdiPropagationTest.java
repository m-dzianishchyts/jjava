package org.dflib.jjava.kernel;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gate test (tasks T009): a real JDI executor receives the kernel-owned Unix-domain socket path, which contains spaces,
 * and the child reads the exact value and connects to it. The kernel owns the endpoint path and passes it to the
 * executor as {@code -Djjava.display.socket}. Writes its result to target/uds-gate/{os}-jdk{N}-propagation.txt.
 */
class UnixDomainSocketJdiPropagationTest {

    @Test
    void childReceivesExactPathAndConnects() throws Exception {
        String os = System.getProperty("os.name").toLowerCase().replace(' ', '-');
        int jdk = Runtime.version().feature();
        Path dir = Files.createTempDirectory("uds gate prop ");
        Path socket = dir.resolve("display.sock");
        String value = socket.toString();
        boolean ok = false;
        try {
            JavaKernel kernel = JavaKernel.builder()
                    .extensionsEnabled(false)
                    .displayEndpointPath(socket)
                    .build();
            try {
                // base64 avoids escaping the Windows path inside the JShell source
                String b64 = Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8));
                String code = "var ch = java.nio.channels.SocketChannel.open(java.net.StandardProtocolFamily.UNIX);\n"
                        + "ch.connect(java.net.UnixDomainSocketAddress.of(System.getProperty(\"jjava.display.socket\")));\n"
                        + "ch.close();\n"
                        + "System.getProperty(\"jjava.display.socket\").equals("
                        + "new String(java.util.Base64.getDecoder().decode(\"" + b64 + "\"), java.nio.charset.StandardCharsets.UTF_8))";
                Object result = kernel.evalBuilder(code).resolveMagics().eval();
                assertEquals("true", String.valueOf(result));
                ok = true;
            } finally {
                kernel.onShutdown(false);
            }
        } finally {
            Path out = Path.of("target", "uds-gate", os + "-jdk" + jdk + "-propagation.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, List.of("os=" + System.getProperty("os.name") + " jdk=" + jdk + " path=" + value,
                    "propagation=" + (ok ? "PASS" : "FAIL")));
            Files.deleteIfExists(socket);
        }
        assertTrue(ok);
    }
}
