package dev.mutagen.mutation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Starts a Spring Boot (or any HTTP) backend as a subprocess and waits until
 * it accepts TCP connections on its port.
 *
 * <p>Supports Maven projects ({@code mvn spring-boot:run}) and pre-built JARs
 * ({@code java -jar target/*.jar}).
 *
 * <p>Usage:
 * <pre>
 *   BackendStarter starter = BackendStarter.detect(repoPath);
 *   int port = starter.start();
 *   // ... run tests against http://localhost:{port}
 *   starter.stop();
 * </pre>
 */
public class BackendStarter implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(BackendStarter.class);

    private static final int STARTUP_TIMEOUT_SECONDS = 120;
    private static final int POLL_INTERVAL_MS        = 500;

    private final Path projectDir;
    private final int  port;
    private Process    process;

    public BackendStarter(Path projectDir, int port) {
        this.projectDir = projectDir;
        this.port       = port;
    }

    /**
     * Auto-detects the project type and port from {@code application.properties} /
     * {@code application.yml}.
     */
    public static BackendStarter detect(Path repoPath) {
        int port = readPort(repoPath);
        log.debug("Detected backend port {} for {}", port, repoPath);
        return new BackendStarter(repoPath, port);
    }

    /** Starts the backend and blocks until it is ready. Returns the port. */
    public int start() throws IOException, InterruptedException {
        if (process != null && process.isAlive()) {
            log.debug("Backend already running on port {}", port);
            return port;
        }

        List<String> cmd = buildStartCommand(projectDir);
        log.info("Starting backend: {} in {}", String.join(" ", cmd), projectDir);

        process = new ProcessBuilder(cmd)
                .directory(projectDir.toFile())
                .redirectErrorStream(true)
                .start();

        // Drain stdout/stderr in background so the process doesn't block
        Thread drainer = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[backend] {}", line);
                }
            } catch (IOException ignored) {}
        }, "backend-stdout-drainer");
        drainer.setDaemon(true);
        drainer.start();

        waitForPort(port, STARTUP_TIMEOUT_SECONDS);
        log.info("Backend is ready on port {}", port);
        return port;
    }

    /** Stops the backend process. */
    public void stop() {
        if (process != null && process.isAlive()) {
            log.info("Stopping backend on port {}", port);
            process.destroy();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }

    @Override
    public void close() {
        stop();
    }

    public int getPort() {
        return port;
    }

    // -----------------------------------------------------------------------

    private static List<String> buildStartCommand(Path projectDir) {
        // Maven wrapper preferred, fall back to system mvn
        if (projectDir.resolve("mvnw").toFile().exists()) {
            return List.of("./mvnw", "spring-boot:run", "-q");
        }
        if (projectDir.resolve("pom.xml").toFile().exists()) {
            return List.of("mvn", "spring-boot:run", "-q");
        }
        if (projectDir.resolve("gradlew").toFile().exists()) {
            return List.of("./gradlew", "bootRun", "-q");
        }
        return List.of("mvn", "spring-boot:run", "-q");
    }

    private static int readPort(Path repoPath) {
        // Try application.properties
        Path props = repoPath.resolve("src/main/resources/application.properties");
        if (props.toFile().exists()) {
            try {
                for (String line : Files.readAllLines(props)) {
                    line = line.trim();
                    if (line.startsWith("server.port")) {
                        String[] parts = line.split("=", 2);
                        if (parts.length == 2) {
                            String val = parts[1].trim();
                            if (!val.isEmpty() && !val.startsWith("$")) {
                                return Integer.parseInt(val);
                            }
                        }
                    }
                }
            } catch (IOException | NumberFormatException ignored) {}
        }
        // Try application.yml
        Path yml = repoPath.resolve("src/main/resources/application.yml");
        if (yml.toFile().exists()) {
            try {
                for (String line : Files.readAllLines(yml)) {
                    Matcher m = Pattern.compile("port:\\s*(\\d+)").matcher(line);
                    if (m.find()) return Integer.parseInt(m.group(1));
                }
            } catch (IOException | NumberFormatException ignored) {}
        }
        return 8080; // Spring Boot default
    }

    private static void waitForPort(int port, int timeoutSeconds)
            throws IOException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try (Socket s = new Socket("localhost", port)) {
                return; // success
            } catch (IOException e) {
                Thread.sleep(POLL_INTERVAL_MS);
            }
        }
        throw new IOException(
                "Backend did not start on port " + port + " within " + timeoutSeconds + "s");
    }
}
