package org.dflib.jjava.jupyter.kernel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BaseNotebookStaticsDisplayTest {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    private BaseKernel previousKernel;
    private String previousPort;
    private ServerSocket serverSocket;

    @BeforeEach
    public void setUp() throws IOException {
        previousKernel = BaseKernel.notebookKernel;
        previousPort = System.getProperty("jjava.display.port");
        BaseKernel.notebookKernel = null;
        serverSocket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        serverSocket.setSoTimeout(10_000);
        System.setProperty("jjava.display.port", Integer.toString(serverSocket.getLocalPort()));
    }

    @AfterEach
    public void tearDown() throws IOException {
        try {
            serverSocket.close();
        } finally {
            BaseKernel.notebookKernel = previousKernel;
            if (previousPort == null) {
                System.clearProperty("jjava.display.port");
            } else {
                System.setProperty("jjava.display.port", previousPort);
            }
        }
    }

    @Test
    public void sendsCompleteBundleAndRequiresSuccessAcknowledgement() throws Exception {
        DisplayData data = new DisplayData();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("values", Arrays.asList("kept", true, null));
        nested.put("nullValue", null);
        data.putData("application/json", nested);
        data.putData("application/null", null);
        data.putMetaData("render", nested);
        data.putTransientData("custom", nested);
        data.setDisplayId("display-id");

        JsonObject request = exchange("ok", () -> assertEquals("display-id", BaseNotebookStatics.display(data)));

        assertEquals("display", request.get("action").getAsString());
        assertFalse(request.has("id"));
        assertEquals(GSON.toJsonTree(data), request.get("bundle"));
    }

    @Test
    public void eofAndErrorAcknowledgementsFailVisibly() throws Exception {
        JsonObject eof = exchange(null, () -> {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> BaseNotebookStatics.display("text"));
            assertTrue(failure.getMessage().contains("closed without a response"));
        });
        assertEquals("display", eof.get("action").getAsString());

        JsonObject error = exchange("\"remote\\nerror\"", () -> {
            RuntimeException failure = assertThrows(RuntimeException.class, () -> BaseNotebookStatics.display("text"));
            assertTrue(failure.getMessage().contains("remote\nerror"));
        });
        assertEquals("display", error.get("action").getAsString());
    }

    @Test
    public void sendsUpdateIdAndCompleteBundle() throws Exception {
        DisplayData data = new DisplayData("updated");
        data.putMetaData("render", Map.of("expanded", true));
        data.putTransientData("custom", "preserved");
        JsonObject request = exchange("ok", () -> BaseNotebookStatics.updateDisplay("requested-id", data));

        assertEquals("updateDisplay", request.get("action").getAsString());
        assertEquals("requested-id", request.get("id").getAsString());
        assertEquals(GSON.toJsonTree(data), request.get("bundle"));
    }

    @Test
    public void rejectsNonPortableValuesBeforeConnectingAndAcceptsCharacterArrays() throws Exception {
        DisplayData nestedValue = new DisplayData();
        nestedValue.putData("application/json", Arrays.asList(Map.of("bad", new Object())));
        assertNonPortable(nestedValue);

        DisplayData nonStringKeysData = new DisplayData();
        Map<Object, Object> nonStringKeys = new LinkedHashMap<>();
        nonStringKeys.put(1, "bad");
        nonStringKeysData.putData("application/json", nonStringKeys);
        assertNonPortable(nonStringKeysData);

        DisplayData transientValue = new DisplayData();
        transientValue.putTransientData("bad", new Object());
        assertNonPortable(transientValue);

        DisplayData characters = new DisplayData();
        characters.putData("application/characters", new char[]{'o', 'k'});
        JsonObject request = exchange("ok", () -> assertNotNull(BaseNotebookStatics.display(characters)));
        JsonObject bundle = request.getAsJsonObject("bundle");
        assertEquals("[\"o\",\"k\"]", bundle.getAsJsonObject("data").get("application/characters").toString());
        assertTrue(bundle.getAsJsonObject("transient").has("display_id"));
    }

    private void assertNonPortable(DisplayData data) {
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> BaseNotebookStatics.display(data));
        assertTrue(failure.getMessage().contains("Non-portable display value"));
    }

    private JsonObject exchange(String response, Runnable operation) throws Exception {
        FutureTask<JsonObject> request = new FutureTask<>(() -> {
            try (Socket socket = serverSocket.accept()) {
                socket.setSoTimeout(2_000);
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                     BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {
                    JsonObject json = GSON.fromJson(reader.readLine(), JsonObject.class);
                    if (response != null) {
                        writer.write(response);
                        writer.newLine();
                        writer.flush();
                    }
                    return json;
                }
            }
        });
        Thread server = new Thread(request, "test-display-callback");
        server.setDaemon(true);
        server.start();
        operation.run();
        return request.get(2_000, TimeUnit.MILLISECONDS);
    }
}
