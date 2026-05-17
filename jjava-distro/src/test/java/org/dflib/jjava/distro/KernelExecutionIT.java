package org.dflib.jjava.distro;

import org.junit.jupiter.api.Test;
import org.testcontainers.containers.Container;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class KernelExecutionIT extends ContainerizedKernelCase {

    /**
     * @see <a href="https://github.com/dflib/jjava/issues/119">#119</a>
     */
    @Test
    public void variableSurvivesLaterImports() throws Exception {
        String snippet = "%load " + CONTAINER_RESOURCES + "/nullifying_import.jshell";

        Container.ExecResult snippetResult = executeInKernel(snippet);

        assertEquals(0, snippetResult.getExitCode(), snippetResult.getStdout());
        assertThat(snippetResult.getStdout(), not(containsString("|")));
    }
}
