package org.dflib.jjava.distro;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KernelMagicIT extends ContainerizedKernelCase {

    @Deprecated
    @Test
    public void jars() throws Exception {
        String jar = CONTAINER_RESOURCES + "/jakarta.annotation-api-3.0.0.jar";
        Container.ExecResult fetchResult = container.execInContainer(
                "curl", "-L", "-s", "-S", "-f",
                "https://repo1.maven.org/maven2/jakarta/annotation/jakarta.annotation-api/3.0.0/jakarta.annotation-api-3.0.0.jar",
                "-o", jar
        );
        assertEquals(0, fetchResult.getExitCode(), fetchResult.getStdout());

        KernelRun run = executeInKernel(
                "%jars " + jar,
                "import jakarta.annotation.Nullable;",
                "Nullable.class.getName()"
        ).assertNoErrors();

        assertEquals("jakarta.annotation.Nullable", run.cell(3).result());
    }

    @Test
    public void classpath() throws Exception {
        KernelRun run = executeInKernel(
                "%classpath " + TEST_CLASSPATH,
                "import org.dflib.jjava.Dummy;",
                "\"className = \" + Dummy.class.getName();"
        ).assertNoErrors();

        assertEquals("className = org.dflib.jjava.Dummy", run.cell(3).result());
    }

    @Test
    public void maven() throws Exception {
        KernelRun run = executeInKernel(
                "%maven org.dflib:dflib-jupyter:1.0.0-RC1",
                "System.getProperty(\"java.class.path\")"
        ).assertNoErrors();

        assertThat(run.cell(2).result(), containsString("dflib-jupyter-1.0.0-RC1.jar"));
    }

    @Deprecated
    @Test
    public void mavenIvySyntax() throws Exception {
        KernelRun run = executeInKernel(
                "%maven jakarta.annotation#jakarta.annotation-api;3.0.0",
                "System.getProperty(\"java.class.path\")"
        ).assertNoErrors();

        assertThat(run.cell(2).result(), containsString("jakarta.annotation-api-3.0.0.jar"));
    }

    @Test
    public void load() throws Exception {
        KernelRun run = executeInKernel(
                "%load " + CONTAINER_RESOURCES + "/test-ping.jshell",
                "ping()"
        ).assertNoErrors();

        assertEquals("pong!", run.cell(2).result());
    }

    @Test
    public void loadFromPOM() throws Exception {
        KernelRun run = executeInKernel(
                "%loadFromPOM " + CONTAINER_RESOURCES + "/test-pom.xml",
                "import jakarta.annotation.Nullable;",
                "Nullable.class.getName()"
        ).assertNoErrors();

        assertEquals("jakarta.annotation.Nullable", run.cell(3).result());
    }
}
