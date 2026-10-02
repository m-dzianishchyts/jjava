package org.dflib.jjava.kernel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import jdk.jshell.JShell;
import org.dflib.jjava.jupyter.channels.ShellReplyEnvironment;
import org.dflib.jjava.jupyter.kernel.JupyterIO;
import org.dflib.jjava.jupyter.kernel.LanguageInfo;
import org.dflib.jjava.jupyter.kernel.comm.CommManager;
import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.dflib.jjava.jupyter.kernel.display.Renderer;
import org.dflib.jjava.jupyter.kernel.magic.MagicTranspiler;
import org.dflib.jjava.jupyter.kernel.magic.MagicsRegistry;
import org.dflib.jjava.jupyter.kernel.magic.MagicsResolver;
import org.dflib.jjava.jupyter.kernel.util.StringStyler;
import org.dflib.jjava.jupyter.messages.Message;
import org.dflib.jjava.jupyter.messages.publish.PublishUpdateDisplayData;
import org.dflib.jjava.kernel.execution.CodeEvaluator;
import org.dflib.jjava.kernel.execution.DisplayCallbackServer;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class DisplayCallbackServerTest {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    @Test
    public void preservesStructuredBundleAndContinuesAfterMalformedRequest() throws Exception {
        try (CallbackKernel kernel = new CallbackKernel();
             DisplayCallbackServer server = new DisplayCallbackServer()) {
            server.setKernel(kernel);

            String error = GSON.fromJson(request(server, "not json"), String.class);
            assertNotNull(error);
            assertFalse(error.isBlank());
            assertNotEquals("ok", error);

            String request = displayRequest();
            JsonObject expectedBundle = GSON.fromJson(request, JsonObject.class).getAsJsonObject("bundle");
            assertEquals("ok", request(server, request));
            assertEquals(expectedBundle, GSON.toJsonTree(kernel.displayed));
        }
    }

    @Test
    public void returnsDispatchFailuresAndRoutesUpdatesThroughDisplayStream() throws Exception {
        CapturingEnvironment env = new CapturingEnvironment();
        try (CallbackKernel kernel = new CallbackKernel(new CapturingIO(env));
             DisplayCallbackServer server = new DisplayCallbackServer()) {
            server.setKernel(kernel);
            kernel.displayFailure = new IllegalStateException("display failed\nnow");

            assertEquals("\"display failed\\nnow\"", request(server, displayRequest()));
            assertNull(kernel.displayed);

            String update = "{\"action\":\"updateDisplay\",\"id\":\"existing\","
                    + "\"bundle\":{\"data\":{\"text/plain\":\"updated\"},\"metadata\":{},\"transient\":{}}}";
            assertEquals("ok", request(server, update));
            assertEquals(1, env.published.size());
            PublishUpdateDisplayData published = assertInstanceOf(
                    PublishUpdateDisplayData.class,
                    env.published.get(0).getContent());
            assertEquals("existing", published.getDisplayId());
            assertEquals("updated", published.getData().get("text/plain"));
        }
    }

    @Test
    public void rejectsUpdatesWithoutIdsInsteadOfDisplayingThem() throws Exception {
        try (CallbackKernel kernel = new CallbackKernel();
             DisplayCallbackServer server = new DisplayCallbackServer()) {
            server.setKernel(kernel);
            String response = request(server,
                    "{\"action\":\"updateDisplay\",\"bundle\":{\"data\":{},\"metadata\":{}}}");

            assertTrue(GSON.fromJson(response, String.class).contains("requires a non-null id"));
            assertNull(kernel.displayed);
        }
    }

    private String displayRequest() {
        return "{\"action\":\"display\",\"bundle\":{"
                + "\"data\":{\"application/json\":{\"values\":[1,true,null]},\"text/empty\":null},"
                + "\"metadata\":{\"render\":{\"nested\":false}},"
                + "\"transient\":{\"custom\":{\"values\":[\"kept\",null]}}}}";
    }

    private String request(DisplayCallbackServer server, String request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getPort()), 10_000);
            socket.setSoTimeout(10_000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                writer.write(request);
                writer.newLine();
                writer.flush();
                return reader.readLine();
            }
        }
    }

    private static class CapturingIO extends JupyterIO {
        CapturingIO(CapturingEnvironment env) {
            super(StandardCharsets.UTF_8);
            setEnv(env);
        }
    }

    private static class CapturingEnvironment extends ShellReplyEnvironment {
        final List<Message<?>> published = new ArrayList<>();

        CapturingEnvironment() {
            super(null, null, null, null);
        }

        @Override
        public void publish(Message<?> message) {
            published.add(message);
        }
    }

    private static class CallbackKernel extends JavaKernel implements AutoCloseable {
        DisplayData displayed;
        RuntimeException displayFailure;

        CallbackKernel() {
            this(new JupyterIO(StandardCharsets.UTF_8));
        }

        CallbackKernel(JupyterIO io) {
            super(
                    "test", "0",
                    new LanguageInfo.Builder("Java").build(),
                    Collections.emptyList(), null,
                    io, new CommManager(), new Renderer(),
                    new MagicsResolver("%", "%%", new MagicTranspiler()),
                    new MagicsRegistry(), false, new StringStyler.Builder().build(),
                    // These tests exercise callback I/O, not remote executor behavior.
                    JShell.builder().executionEngine("local").build(),
                    new CodeEvaluator(-1, TimeUnit.MILLISECONDS));
        }

        @Override
        public void display(DisplayData data) {
            if (displayFailure != null) {
                throw displayFailure;
            }
            displayed = data;
        }

        @Override
        public void close() {
            onShutdown(false);
        }
    }
}
