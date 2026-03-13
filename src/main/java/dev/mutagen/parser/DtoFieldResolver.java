package dev.mutagen.parser;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import dev.mutagen.model.RequestBodyInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/**
 * Resolves DTO/record field types from the AST to enrich {@link RequestBodyInfo}
 * with field names and types for richer test generation context.
 */
public class DtoFieldResolver {

    private static final Logger log = LoggerFactory.getLogger(DtoFieldResolver.class);

    private final Map<String, Map<String, String>> dtoCache         = new HashMap<>();
    private final Map<String, String>               qualifiedNames   = new HashMap<>();

    public void index(Path sourcePath) {
        log.info("Building DTO index under {}", sourcePath);
        try {
            Files.walkFileTree(sourcePath, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    if (file.toString().endsWith(".java")) indexFile(file);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            log.warn("Error indexing DTOs: {}", e.getMessage());
        }
        log.info("DTO index built: {} classes indexed", dtoCache.size());
    }

    public void enrichRequestBody(RequestBodyInfo body) {
        if (body == null || body.getJavaType() == null) return;
        String simpleName = extractSimpleName(body.getJavaType());
        Map<String, String> fields = dtoCache.get(simpleName);
        if (fields != null) {
            body.setFields(fields);
            log.debug("Enriched DTO '{}' with {} fields", simpleName, fields.size());
        }
        String fqn = qualifiedNames.get(simpleName);
        if (fqn != null) {
            body.setQualifiedJavaType(fqn);
        }
    }

    private void indexFile(Path file) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(file);
            String pkg = cu.getPackageDeclaration()
                    .map(p -> p.getNameAsString() + ".")
                    .orElse("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                Map<String, String> fields = new LinkedHashMap<>();
                clazz.getFields().forEach(field -> {
                    String type = field.getElementType().asString();
                    field.getVariables().forEach(var -> fields.put(var.getNameAsString(), type));
                });
                if (fields.isEmpty()) {
                    clazz.getConstructors().stream()
                            .filter(c -> !c.getParameters().isEmpty())
                            .findFirst()
                            .ifPresent(c -> c.getParameters().forEach(p ->
                                    fields.put(p.getNameAsString(), p.getTypeAsString())));
                }
                if (!fields.isEmpty()) {
                    String simpleName = clazz.getNameAsString();
                    dtoCache.put(simpleName, fields);
                    qualifiedNames.put(simpleName, pkg + simpleName);
                }
            });

            cu.findAll(com.github.javaparser.ast.body.RecordDeclaration.class).forEach(record -> {
                Map<String, String> fields = new LinkedHashMap<>();
                record.getParameters().forEach(p -> fields.put(p.getNameAsString(), p.getTypeAsString()));
                if (!fields.isEmpty()) {
                    String simpleName = record.getNameAsString();
                    dtoCache.put(simpleName, fields);
                    qualifiedNames.put(simpleName, pkg + simpleName);
                }
            });

        } catch (Exception ignored) {
            // Not every file is a DTO — silently skip
        }
    }

    private String extractSimpleName(String javaType) {
        String withoutGenerics = javaType.replaceAll("<.*>", "").trim();
        int lastDot = withoutGenerics.lastIndexOf('.');
        return lastDot >= 0 ? withoutGenerics.substring(lastDot + 1) : withoutGenerics;
    }
}
