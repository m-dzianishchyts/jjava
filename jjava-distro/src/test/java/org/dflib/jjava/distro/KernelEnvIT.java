package org.dflib.jjava.distro;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

public class KernelEnvIT extends ContainerizedKernelCase {

    @Test
    public void compilerOpts() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_COMPILER_OPTS, "-source 9");
        String cell = "var value = 1;";
        KernelRun run = executeInKernel(env, cell);

        CellOutput output = run.cell(1);
        assertFalse(output.isOk(), run::toString);
        assertThat(output.error(), allOf(
                containsString("|   " + cell),
                containsString(Runtime.version().feature() == 11
                        ? "'var' is a restricted local variable type"
                        : "'var' is a restricted type name")
        ));
    }

    @Test
    public void timeout() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_TIMEOUT, "3000");
        String cell = "Thread.sleep(5000);";
        KernelRun run = executeInKernel(env, cell);

        CellOutput output = run.cell(1);
        assertFalse(output.isOk(), run::toString);
        assertThat(output.error(), allOf(
                containsString("|   " + cell),
                containsString("Evaluation timed out after 3000 milliseconds.")
        ));
    }

    @Test
    public void classpath() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_CLASSPATH, TEST_CLASSPATH);
        KernelRun run = executeInKernel(env,
                "import org.dflib.jjava.Dummy;",
                "\"className = \" + Dummy.class.getName();"
        ).assertNoErrors();

        assertEquals("className = org.dflib.jjava.Dummy", run.cell(2).result());
    }

    @Test
    public void startUpScriptsPath() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_STARTUP_SCRIPTS_PATH, CONTAINER_RESOURCES + "/test-ping.jshell");
        KernelRun run = executeInKernel(env, "ping()").assertNoErrors();

        assertEquals("pong!", run.cell(1).result());
    }

    @Test
    public void startUpScript() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_STARTUP_SCRIPT, "public String ping() { return \"pong!\"; }");
        KernelRun run = executeInKernel(env, "ping()").assertNoErrors();

        assertEquals("pong!", run.cell(1).result());
    }

    @Test
    public void loadExtensions_Default() throws Exception {
        KernelRun run = executeInKernel("printf(\"Hello, %s!\", \"world\");").assertNoErrors();

        assertEquals("Hello, world!", run.cell(1).stdout());
    }

    @Test
    public void loadExtensions_Disable() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_LOAD_EXTENSIONS, "0");
        String cell = "printf(\"Hello, %s!\", \"world\");";
        KernelRun run = executeInKernel(env, cell);

        CellOutput output = run.cell(1);
        assertFalse(output.isOk(), run::toString);
        assertThat(output.error(), allOf(
                containsString("|   " + cell),
                containsString("cannot find symbol")
        ));
    }

    @Test
    public void jvmOpts() throws Exception {
        Map<String, String> env = Map.of(Env.JJAVA_JVM_OPTS, "-Xmx300m");
        KernelRun run = executeInKernel(env, "Runtime.getRuntime().maxMemory()").assertNoErrors();

        assertEquals(String.valueOf(300 * (int) Math.pow(1024, 2)), run.cell(1).result());
    }
}
