package dev.mutagen.parser;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.*;
import com.github.javaparser.ast.expr.*;
import com.github.javaparser.ast.nodeTypes.NodeWithAnnotations;
import dev.mutagen.model.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

public class ControllerParser {

    private static final Logger log = LoggerFactory.getLogger(ControllerParser.class);

    private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
            "RestController", "Controller"
    );

    private static final Set<String> AUTH_ANNOTATIONS = Set.of(
            "PreAuthorize", "Secured", "RolesAllowed"
    );

    private static final Set<String> VALIDATION_ANNOTATIONS = Set.of(
            "Valid", "Validated", "NotNull", "NotBlank", "NotEmpty",
            "Size", "Min", "Max", "Pattern", "Email"
    );

    public ControllerParser() {
        StaticJavaParser.setConfiguration(
                new ParserConfiguration().setLanguageLevel(ParserConfiguration.LanguageLevel.JAVA_21));
    }

    public ParseResult scan(Path sourcePath) {

        ParseResult result = new ParseResult();
        result.setRepoPath(sourcePath.toAbsolutePath().toString());

        List<Path> javaFiles = collectJavaFiles(sourcePath, result);

        Set<String> uniqueEndpoints = new HashSet<>();

        for (Path file : javaFiles) {

            try {

                List<EndpointInfo> endpoints = parseFile(file, result);

                for (EndpointInfo e : endpoints) {

                    String key = e.getHttpMethod() + ":" + e.getFullPath();

                    if (uniqueEndpoints.add(key)) {
                        result.getEndpoints().add(e);
                    }
                }

            } catch (Exception e) {

                result.getWarnings().add(
                        "Could not parse " + file + ": " + e.getMessage()
                );

            }

        }

        return result;

    }

    private List<Path> collectJavaFiles(Path root, ParseResult result) {

        List<Path> files = new ArrayList<>();

        try {

            Files.walkFileTree(root, new SimpleFileVisitor<>() {

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {

                    if (file.toString().endsWith(".java")) {
                        files.add(file);
                    }

                    return FileVisitResult.CONTINUE;

                }

            });

        } catch (IOException e) {

            result.getWarnings().add(e.getMessage());

        }

        return files;

    }

    private List<EndpointInfo> parseFile(Path file, ParseResult result) throws IOException {

        CompilationUnit cu = StaticJavaParser.parse(file);

        List<EndpointInfo> endpoints = new ArrayList<>();

        cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {

            if (!isController(clazz)) return;

            result.setControllersFound(result.getControllersFound() + 1);

            String basePath = extractBasePath(clazz);
            List<String> classConsumes = extractClassLevelConsumes(clazz);

            clazz.getMethods().forEach(method -> {

                Optional<EndpointInfo> endpoint =
                        parseMethod(method, clazz.getNameAsString(), file.toString(), basePath, classConsumes, file, result);

                endpoint.ifPresent(endpoints::add);

            });

        });

        return endpoints;

    }

    private boolean isController(ClassOrInterfaceDeclaration clazz) {

        return clazz.getAnnotations()
                .stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(CONTROLLER_ANNOTATIONS::contains);

    }

    private String extractBasePath(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotationByName("RequestMapping")
                .map(this::extractPathFromAnnotation)
                .orElse("");
    }

    /** Extracts class-level consumes values from {@code @RequestMapping(consumes = ...)}. */
    private List<String> extractClassLevelConsumes(ClassOrInterfaceDeclaration clazz) {
        return clazz.getAnnotationByName("RequestMapping")
                .map(this::extractConsumesFromAnnotation)
                .orElse(List.of());
    }

    private List<String> extractConsumesFromAnnotation(AnnotationExpr annotation) {
        if (!(annotation instanceof NormalAnnotationExpr normal)) return List.of();
        return normal.getPairs().stream()
                .filter(p -> p.getNameAsString().equals("consumes"))
                .flatMap(p -> {
                    String val = p.getValue().toString();
                    // Could be a single string or an array initializer
                    return java.util.Arrays.stream(val
                            .replaceAll("[{}\"\\[\\]]", "")
                            .split(","))
                            .map(s -> resolveMediaTypeConstant(s.strip()))
                            .filter(s -> !s.isBlank());
                })
                .toList();
    }

    /** Resolves common Spring media type constant names to their actual values. */
    private String resolveMediaTypeConstant(String value) {
        return switch (value) {
            case "APPLICATION_JSON_VALUE", "MediaType.APPLICATION_JSON_VALUE" -> "application/json";
            case "APPLICATION_XML_VALUE",  "MediaType.APPLICATION_XML_VALUE"  -> "application/xml";
            case "TEXT_PLAIN_VALUE",       "MediaType.TEXT_PLAIN_VALUE"       -> "text/plain";
            case "MULTIPART_FORM_DATA_VALUE", "MediaType.MULTIPART_FORM_DATA_VALUE" -> "multipart/form-data";
            default -> value.startsWith("\"") ? stripQuotes(value) : value;
        };
    }

    /**
     * Reads the raw source lines of a method from the controller file.
     * Returns the method declaration + body, capped at 25 lines to keep prompts compact.
     */
    private String extractMethodSource(MethodDeclaration method, Path file) {
        try {
            if (!method.getBegin().isPresent()) return "";
            int startLine = method.getBegin().get().line - 1; // 0-based
            List<String> allLines = Files.readAllLines(file, StandardCharsets.UTF_8);
            int endLine = Math.min(allLines.size(), startLine + 25);
            return allLines.subList(startLine, endLine).stream()
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (IOException e) {
            return "";
        }
    }

    private Optional<EndpointInfo> parseMethod(
            MethodDeclaration method,
            String className,
            String filePath,
            String basePath,
            List<String> classConsumes,
            Path file,
            ParseResult result
    ) {

        Optional<AnnotationExpr> mapping = resolveMapping(method);

        if (mapping.isEmpty()) {
            return Optional.empty();
        }

        AnnotationExpr annotation = mapping.get();

        HttpMethod httpMethod = resolveHttpMethod(annotation);

        String methodPath = extractPathFromAnnotation(annotation);

        EndpointInfo endpoint = new EndpointInfo();

        endpoint.setControllerClass(className);
        endpoint.setControllerFile(filePath);
        endpoint.setMethodName(method.getNameAsString());
        endpoint.setHttpMethod(httpMethod);

        endpoint.setPath(methodPath);

        endpoint.setFullPath(
                normalizePath(basePath + "/" + methodPath)
        );

        endpoint.setResponseType(method.getTypeAsString());

        boolean auth = hasAnyAnnotation(method, AUTH_ANNOTATIONS);
        endpoint.setRequiresAuth(auth);
        if (auth) {
            endpoint.setRequiredRole(extractRequiredRole(method));
        }

        method.getParameters().forEach(
                p -> parseParameter(p, endpoint, result)
        );

        // Inherit class-level consumes if method has none
        if (endpoint.getConsumes().isEmpty() && !classConsumes.isEmpty()) {
            endpoint.setConsumes(new ArrayList<>(classConsumes));
        }

        // Attach method source snippet for LLM context
        String src = extractMethodSource(method, file);
        if (!src.isBlank()) {
            endpoint.setMethodSource(src);
        }

        return Optional.of(endpoint);

    }

    private Optional<AnnotationExpr> resolveMapping(MethodDeclaration method) {

        return method.getAnnotationByName("GetMapping")
                .or(() -> method.getAnnotationByName("PostMapping"))
                .or(() -> method.getAnnotationByName("PutMapping"))
                .or(() -> method.getAnnotationByName("PatchMapping"))
                .or(() -> method.getAnnotationByName("DeleteMapping"))
                .or(() -> method.getAnnotationByName("RequestMapping"));

    }

    private HttpMethod resolveHttpMethod(AnnotationExpr annotation) {

        String name = annotation.getNameAsString();

        return switch (name) {

            case "GetMapping" -> HttpMethod.GET;
            case "PostMapping" -> HttpMethod.POST;
            case "PutMapping" -> HttpMethod.PUT;
            case "PatchMapping" -> HttpMethod.PATCH;
            case "DeleteMapping" -> HttpMethod.DELETE;
            case "RequestMapping" -> extractHttpMethodFromRequestMapping(annotation);
            default -> HttpMethod.GET;

        };

    }

    private void parseParameter(Parameter param, EndpointInfo endpoint, ParseResult result) {

        String javaType = param.getTypeAsString();

        if (param.isAnnotationPresent("PathVariable")) {

            endpoint.getPathParams().add(
                    new ParamInfo(param.getNameAsString(), javaType)
            );

        }
        else if (param.isAnnotationPresent("RequestParam")) {

            endpoint.getQueryParams().add(
                    new ParamInfo(param.getNameAsString(), javaType)
            );

        }
        else if (param.isAnnotationPresent("RequestBody")) {

            RequestBodyInfo body = new RequestBodyInfo();
            body.setJavaType(javaType);
            body.setValidated(hasAnyAnnotation(param, VALIDATION_ANNOTATIONS));
            endpoint.setRequestBody(body);

        }
        else if (param.isAnnotationPresent("RequestHeader")) {

            AnnotationExpr ann = param.getAnnotationByName("RequestHeader").orElseThrow();

            String name = param.getNameAsString();

            if (ann instanceof NormalAnnotationExpr normal) {
                name = normal.getPairs().stream()
                        .filter(p -> p.getNameAsString().equals("value") ||
                                p.getNameAsString().equals("name"))
                        .map(p -> stripQuotes(p.getValue().toString()))
                        .findFirst()
                        .orElse(name);
            }

            ParamInfo header = new ParamInfo(name, javaType);

            endpoint.getHeaderParams().add(header);
        }
    }

    private HttpMethod extractHttpMethodFromRequestMapping(AnnotationExpr annotation) {

        if (annotation instanceof NormalAnnotationExpr normal) {

            return normal.getPairs().stream()
                    .filter(p -> p.getNameAsString().equals("method"))
                    .map(p -> parseHttpMethodLiteral(p.getValue().toString()))
                    .findFirst()
                    .orElse(HttpMethod.GET);

        }

        return HttpMethod.GET;

    }

    private HttpMethod parseHttpMethodLiteral(String literal) {

        String upper = literal.toUpperCase();

        for (HttpMethod m : HttpMethod.values()) {

            if (upper.contains(m.name())) {
                return m;
            }

        }

        return HttpMethod.GET;

    }

    private String extractPathFromAnnotation(AnnotationExpr annotation) {

        if (annotation instanceof SingleMemberAnnotationExpr single) {
            return stripQuotes(single.getMemberValue().toString());
        }

        if (annotation instanceof NormalAnnotationExpr normal) {

            return normal.getPairs()
                    .stream()
                    .filter(p ->
                            p.getNameAsString().equals("value") ||
                                    p.getNameAsString().equals("path"))
                    .map(p -> stripQuotes(p.getValue().toString()))
                    .findFirst()
                    .orElse("");

        }

        return "";

    }

    /**
     * Extracts required roles from {@code @PreAuthorize} or {@code @Secured}.
     *
     * <p>For {@code @PreAuthorize("hasRole('X') or hasRole('Y')")}: extracts all role names
     * from {@code hasRole('...')} and {@code hasAnyRole('...')} calls.
     * For {@code @Secured({"ROLE_ADMIN"})}: strips the ROLE_ prefix for readability.
     *
     * <p>Returns a human-readable string like {@code "ADMIN"}, {@code "USER or ADMIN"},
     * or the raw expression if no roles can be parsed. Returns null if the annotation
     * is not present or produces no roles.
     */
    private String extractRequiredRole(MethodDeclaration method) {
        // Try @PreAuthorize first
        Optional<AnnotationExpr> preAuth = method.getAnnotationByName("PreAuthorize");
        if (preAuth.isPresent()) {
            String expr = preAuth.get().toString();
            // Extract role names from hasRole('X') and hasAnyRole('X','Y')
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("hasAnyRole\\(([^)]+)\\)|hasRole\\('([^']+)'\\)")
                    .matcher(expr);
            List<String> roles = new ArrayList<>();
            while (m.find()) {
                if (m.group(1) != null) {
                    // hasAnyRole('X','Y') — split on comma
                    for (String r : m.group(1).split(",")) {
                        roles.add(r.trim().replace("'", "").replace("ROLE_", ""));
                    }
                } else if (m.group(2) != null) {
                    roles.add(m.group(2).replace("ROLE_", ""));
                }
            }
            if (!roles.isEmpty()) {
                // If any role allows USER-level access → not ADMIN-only
                // Return the most restrictive role (ADMIN-only) or all roles joined
                List<String> uniqueRoles = roles.stream().distinct().toList();
                return String.join(" or ", uniqueRoles);
            }
        }

        // Try @Secured
        Optional<AnnotationExpr> secured = method.getAnnotationByName("Secured");
        if (secured.isPresent()) {
            String expr = secured.get().toString();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"(ROLE_)?([^\"]+)\"")
                    .matcher(expr);
            List<String> roles = new ArrayList<>();
            while (m.find()) roles.add(m.group(2));
            if (!roles.isEmpty()) return String.join(" or ", roles.stream().distinct().toList());
        }

        return null;
    }

    private boolean hasAnyAnnotation(NodeWithAnnotations<?> node, Set<String> names) {

        return node.getAnnotations()
                .stream()
                .map(AnnotationExpr::getNameAsString)
                .anyMatch(names::contains);

    }

    private String normalizePath(String path) {

        return path
                .replaceAll("/+", "/")
                .replaceAll("/$", "");

    }

    private String stripQuotes(String s) {

        return s.replaceAll("^\"|\"$", "");

    }

}