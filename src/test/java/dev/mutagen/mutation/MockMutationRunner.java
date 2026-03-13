package dev.mutagen.mutation;

import dev.mutagen.generator.GeneratedTest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Test double for {@link MutationRunner}.
 *
 * <p>Returns a pre-configured report path instead of running a real subprocess.
 * Supports queued responses so successive calls can return different reports.
 */
public class MockMutationRunner implements MutationRunner {

    private final List<Path> responseQueue   = new ArrayList<>();
    private final List<List<GeneratedTest>> recordedCalls = new ArrayList<>();
    private Path defaultResponse;
    private IOException throwOnNext;

    public MockMutationRunner(Path defaultReportPath) {
        this.defaultResponse = defaultReportPath;
    }

    /** Queue a report path to return on the next call. */
    public MockMutationRunner thenReturn(Path reportPath) {
        responseQueue.add(reportPath);
        return this;
    }

    /** Causes the next call to throw an {@link IOException}. */
    public MockMutationRunner thenThrow(IOException e) {
        this.throwOnNext = e;
        return this;
    }

    @Override
    public Path run(List<GeneratedTest> tests, Path repoPath) throws IOException {
        recordedCalls.add(List.copyOf(tests));
        if (throwOnNext != null) {
            IOException toThrow = throwOnNext;
            throwOnNext = null;
            throw toThrow;
        }
        return responseQueue.isEmpty() ? defaultResponse : responseQueue.remove(0);
    }

    public int getCallCount()                         { return recordedCalls.size(); }
    public List<GeneratedTest> getLastCall()          { return recordedCalls.getLast(); }
    public List<List<GeneratedTest>> getAllCalls()     { return List.copyOf(recordedCalls); }
}
