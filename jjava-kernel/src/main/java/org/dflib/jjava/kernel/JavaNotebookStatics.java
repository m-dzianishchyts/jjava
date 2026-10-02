package org.dflib.jjava.kernel;

import org.dflib.jjava.jupyter.kernel.BaseKernel;

/**
 * An automatically-loaded extension that exposes a collection of static methods for notebook code to interact with the
 * kernel.
 */
public class JavaNotebookStatics {

    /**
     * @deprecated in favor of {@link #kernel()}
     */
    @Deprecated(since = "1.0", forRemoval = true)
    public static JavaKernel getKernelInstance() {
        // TODO: should we have some kind of abstract logger?
        System.err.println("'getKernelInstance()' was deprecated in favor of 'kernel()' and will be eventually removed.");
        return kernel();
    }

    public static JavaKernel kernel() {
        try {
            return (JavaKernel) BaseKernel.notebookKernel();
        } catch (NullPointerException e) {
            throw new UnsupportedOperationException("JavaKernel.kernel() cannot expose the managing JVM from executor code", e);
        }
    }
}
