package dev.mutagen.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.mutagen.model.ParseResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Orchestrates the full parse flow:
 * <ol>
 *   <li>Scan controllers → endpoints</li>
 *   <li>Index DTOs</li>
 *   <li>Enrich request bodies with field info</li>
 * </ol>
 */
public class RepoScanner {

    private static final Logger log = LoggerFactory.getLogger(RepoScanner.class);

    private final ControllerParser controllerParser;
    private final DtoFieldResolver dtoResolver;
    private final ObjectMapper objectMapper;

    public RepoScanner() {
        this.controllerParser = new ControllerParser();
        this.dtoResolver      = new DtoFieldResolver();
        this.objectMapper     = new ObjectMapper()
                .registerModule(new Jdk8Module())
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .enable(SerializationFeature.INDENT_OUTPUT);
    }

    public ParseResult scan(Path repoRoot) {
        Path sourceDir = resolveSourceDir(repoRoot);
        log.info("Scanning: {}", sourceDir);

        ParseResult result = controllerParser.scan(sourceDir);
        dtoResolver.index(sourceDir);
        result.getEndpoints().forEach(endpoint -> {
            if (endpoint.getRequestBody() != null) {
                dtoResolver.enrichRequestBody(endpoint.getRequestBody());
            }
        });

        printSummary(result);
        return result;
    }

    public ParseResult scanAndWrite(Path repoRoot, Path outputPath) throws IOException {
        ParseResult result = scan(repoRoot);
        objectMapper.writeValue(outputPath.toFile(), result);
        log.info("Result written to: {}", outputPath);
        return result;
    }

    private Path resolveSourceDir(Path repoRoot) {
        return List.of(
                repoRoot.resolve("src/main/java"),
                repoRoot.resolve("src/main"),
                repoRoot.resolve("src"),
                repoRoot
        ).stream().filter(p -> Files.isDirectory(p)).findFirst().orElse(repoRoot);
    }

    private void printSummary(ParseResult result) {
        log.info("Files scanned : {}", result.getFilesScanned());
        log.info("Controllers   : {}", result.getControllersFound());
        log.info("Endpoints     : {}", result.getEndpoints().size());
        log.info("Warnings      : {}", result.getWarnings().size());
        result.getEndpoints().forEach(e -> log.info("  {} {}", e.getHttpMethod(), e.getFullPath()));
        result.getWarnings().forEach(w -> log.warn("  ⚠ {}", w));
    }
}
