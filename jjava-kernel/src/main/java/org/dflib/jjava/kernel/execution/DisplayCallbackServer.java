package org.dflib.jjava.kernel.execution;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.dflib.jjava.kernel.JavaKernel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class DisplayCallbackServer implements Closeable {

    private static final Logger LOGGER = LoggerFactory.getLogger(DisplayCallbackServer.class);
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private final ServerSocket serverSocket;
    private volatile JavaKernel kernel;

    public DisplayCallbackServer() throws IOException {
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread listenerThread = new Thread(this::listen, "jjava-display-callback");
        listenerThread.setDaemon(true);
        listenerThread.start();
    }

    public void setKernel(JavaKernel kernel) {
        this.kernel = kernel;
    }

    public int getPort() {
        return serverSocket.getLocalPort();
    }

    private void listen() {
        while (!serverSocket.isClosed()) {
            try {
                handleConnection(serverSocket.accept());
            } catch (IOException e) {
                if (!serverSocket.isClosed()) {
                    LOGGER.warn("Display callback server accept failed", e);
                }
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
             BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8))) {

            socket.setSoTimeout(10_000);
            String response;
            try {
                dispatch(reader.readLine());
                response = "ok";
            } catch (Exception e) {
                String message = e.getMessage();
                response = GSON.toJson(message == null ? e.getClass().getSimpleName() : message);
            }
            writer.write(response);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            if (!serverSocket.isClosed()) {
                LOGGER.warn("Display callback connection failed", e);
            }
        }
    }

    private void dispatch(String line) {
        JsonObject json = GSON.fromJson(line, JsonObject.class);
        if (json == null) {
            throw new IllegalArgumentException("Display callback request must be a JSON object");
        }

        JsonElement actionElement = json.get("action");
        if (actionElement == null || !actionElement.isJsonPrimitive()
                || !actionElement.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("Display callback action must be display or updateDisplay");
        }
        String action = actionElement.getAsString();
        if (!"display".equals(action) && !"updateDisplay".equals(action)) {
            throw new IllegalArgumentException("Unsupported display callback action: " + action);
        }

        String id = json.has("id") && !json.get("id").isJsonNull() ? json.get("id").getAsString() : null;
        if ("updateDisplay".equals(action) && id == null) {
            throw new IllegalArgumentException("updateDisplay requires a non-null id");
        }

        JsonElement bundleElement = json.get("bundle");
        if (bundleElement == null || bundleElement.isJsonNull()) {
            throw new IllegalArgumentException("Display callback bundle must not be null");
        }
        DisplayData data = GSON.fromJson(bundleElement, DisplayData.class);

        JavaKernel currentKernel = kernel;
        if (currentKernel == null) {
            throw new IllegalStateException("Display callback kernel is not available");
        }
        if ("updateDisplay".equals(action)) {
            currentKernel.getIO().display.updateDisplay(id, data);
        } else {
            currentKernel.display(data);
        }
    }

    @Override
    public void close() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
    }
}
