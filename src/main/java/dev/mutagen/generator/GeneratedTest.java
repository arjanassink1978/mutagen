package dev.mutagen.generator;

import dev.mutagen.model.EndpointInfo;
import java.util.List;

/** The result of test generation for a single controller. */
public class GeneratedTest {

    private final String controllerClass;
    private final String testClassName;
    private final String packageName;
    private final String sourceCode;
    private final List<EndpointInfo> endpoints;
    private final int inputTokens;
    private final int outputTokens;
    private final String provider;

    public GeneratedTest(String controllerClass, String testClassName, String packageName,
                         String sourceCode, List<EndpointInfo> endpoints,
                         int inputTokens, int outputTokens, String provider) {
        this.controllerClass = controllerClass;
        this.testClassName   = testClassName;
        this.packageName     = packageName;
        this.sourceCode      = sourceCode;
        this.endpoints       = endpoints;
        this.inputTokens     = inputTokens;
        this.outputTokens    = outputTokens;
        this.provider        = provider;
    }

    /**
     * Returns the relative file path where this test should be written,
     * e.g. {@code src/test/java/com/example/controller/UserControllerIT.java}.
     */
    public String getRelativeFilePath() {
        return "src/test/java/" + packageName.replace('.', '/') + "/" + testClassName + ".java";
    }

    /** Returns a copy of this test with the source code replaced (used by the mutation loop). */
    public GeneratedTest withSourceCode(String newSourceCode) {
        return new GeneratedTest(controllerClass, testClassName, packageName, newSourceCode,
                endpoints, inputTokens, outputTokens, provider);
    }

    public String getControllerClass() { return controllerClass; }
    public String getTestClassName()   { return testClassName; }
    public String getPackageName()     { return packageName; }
    public String getSourceCode()      { return sourceCode; }
    public List<EndpointInfo> getEndpoints() { return endpoints; }
    public int getInputTokens()        { return inputTokens; }
    public int getOutputTokens()       { return outputTokens; }
    public String getProvider()        { return provider; }

    @Override
    public String toString() {
        return "GeneratedTest[%s → %s, %d endpoints, tokens=%d+%d]"
                .formatted(controllerClass, testClassName, endpoints.size(), inputTokens, outputTokens);
    }
}
