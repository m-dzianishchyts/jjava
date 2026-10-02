package org.dflib.jjava.jupyter.kernel;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import org.dflib.jjava.jupyter.kernel.display.DisplayData;
import org.dflib.jjava.jupyter.kernel.magic.UndefinedMagicException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A collection of static methods for notebook code to interact with the kernel. The methods are automatically exposed
 * in notebooks via a static import on bootstrap.
 */
public class BaseNotebookStatics {

    private static final Gson GSON = new GsonBuilder().serializeNulls().create();

    public static void printf(String format, Object... args) {
        System.out.printf(format, args);
    }

    public static Object eval(String source) {
        BaseKernel kernel = BaseKernel.notebookKernel;
        if (kernel == null) {
            throw new UnsupportedOperationException("eval() cannot be called from executor code in JDI mode");
        }
        return kernel.evalBuilder(source).resolveMagics().eval();
    }

    public static <T> T lineMagic(String name, List<String> args) {
        BaseKernel kernel = BaseKernel.notebookKernel;
        if (kernel == null) {
            throw new UnsupportedOperationException("Magics cannot be called programmatically from executor code in JDI mode; use %magic syntax");
        }

        try {
            return kernel.getMagicsRegistry().evalLineMagic(kernel, name, args);
        } catch (UndefinedMagicException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(String.format("Exception running line magic '%s': %s", name, e.getMessage()), e);
        }
    }

    public static <T> T cellMagic(String name, List<String> args, String body) {
        BaseKernel kernel = BaseKernel.notebookKernel;
        if (kernel == null) {
            throw new UnsupportedOperationException("Magics cannot be called programmatically from executor code in JDI mode; use %%magic syntax");
        }

        try {
            return kernel.getMagicsRegistry().evalCellMagic(kernel, name, args, body);
        } catch (UndefinedMagicException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(String.format("Exception running cell magic '%s': %s", name, e.getMessage()), e);
        }
    }

    public static DisplayData render(Object o) {
        BaseKernel kernel = BaseKernel.notebookKernel;
        return kernel != null ? kernel.getRenderer().render(o) : renderPortable(o);
    }

    public static DisplayData render(Object o, String... as) {
        BaseKernel kernel = BaseKernel.notebookKernel;
        return kernel != null ? kernel.getRenderer().renderAs(o, as) : renderPortable(o);
    }

    public static String display(Object o) {
        return displayData(render(o));
    }

    public static String display(Object o, String... as) {
        return displayData(render(o, as));
    }

    public static void updateDisplay(String id, Object o) {
        updateDisplayData(id, render(o));
    }

    public static void updateDisplay(String id, Object o, String... as) {
        updateDisplayData(id, render(o, as));
    }

    private static String displayData(DisplayData data) {
        String id = data.getDisplayId();
        if (id == null) {
            id = UUID.randomUUID().toString();
            data.setDisplayId(id);
        }

        BaseKernel kernel = BaseKernel.notebookKernel;
        if (kernel != null) {
            kernel.display(data);
        } else {
            sendRemoteDisplay("display", id, data);
        }
        return id;
    }

    private static void updateDisplayData(String id, DisplayData data) {
        BaseKernel kernel = BaseKernel.notebookKernel;
        if (kernel != null) {
            kernel.getIO().display.updateDisplay(id, data);
        } else {
            sendRemoteDisplay("updateDisplay", id, data);
        }
    }

    private static void sendRemoteDisplay(String action, String id, DisplayData data) {
        validateDisplayData(data);
        int port = Integer.getInteger("jjava.display.port", -1);
        if (port <= 0) {
            throw new IllegalStateException("Remote display channel is not available");
        }

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 10_000);
            socket.setSoTimeout(10_000);
            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));
                 BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))) {
                JsonObject json = new JsonObject();
                json.addProperty("action", action);
                if ("updateDisplay".equals(action) && id != null) {
                    json.addProperty("id", id);
                }
                json.add("bundle", GSON.toJsonTree(data));

                writer.write(json.toString());
                writer.newLine();
                writer.flush();

                checkDisplayResponse(reader.readLine());
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to send display data: " + e.getMessage(), e);
        }
    }

    private static void checkDisplayResponse(String response) throws IOException {
        if (response == null) {
            throw new IOException("Display callback closed without a response");
        }
        if (!"ok".equals(response)) {
            String message;
            try {
                message = GSON.fromJson(response, String.class);
            } catch (RuntimeException e) {
                message = response;
            }
            throw new IOException("Display callback failed: " + (message == null ? response : message));
        }
    }

    private static void validateDisplayData(DisplayData data) {
        validatePortable(data.getData());
        validatePortable(data.getMetadata());
        validatePortable(data.getTransientData());
    }

    private static void validatePortable(Object value) {
        if (value == null
                || value instanceof CharSequence
                || value instanceof Number
                || value instanceof Boolean
                || value instanceof Character) {
            return;
        }
        if (value instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!(entry.getKey() instanceof String)) {
                    Object key = entry.getKey();
                    throw new IllegalArgumentException("Non-portable display value: "
                            + (key == null ? "null" : key.getClass().getName()));
                }
                validatePortable(entry.getValue());
            }
            return;
        }
        if (value instanceof Iterable) {
            ((Iterable<?>) value).forEach(BaseNotebookStatics::validatePortable);
            return;
        }
        if (value.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(value);
            for (int i = 0; i < len; i++) {
                validatePortable(java.lang.reflect.Array.get(value, i));
            }
            return;
        }
        throw new IllegalArgumentException("Non-portable display value: " + value.getClass().getName());
    }

    private static DisplayData renderPortable(Object value) {
        if (value instanceof DisplayData) {
            return (DisplayData) value;
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return new DisplayData(String.valueOf(value));
        }
        throw new IllegalArgumentException("Non-portable display value: " + value.getClass().getName());
    }
}
