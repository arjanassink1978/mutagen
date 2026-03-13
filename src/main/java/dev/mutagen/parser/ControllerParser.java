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

            clazz.getMethods().forEach(method -> {

                Optional<EndpointInfo> endpoint =
                        parseMethod(method, clazz.getNameAsString(), file.toString(), basePath, result);

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

    private Optional<EndpointInfo> parseMethod(
            MethodDeclaration method,
            String className,
            String filePath,
            String basePath,
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

        endpoint.setRequiresAuth(
                hasAnyAnnotation(method, AUTH_ANNOTATIONS)
        );

        method.getParameters().forEach(
                p -> parseParameter(p, endpoint, result)
        );

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