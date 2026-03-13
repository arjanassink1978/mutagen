package dev.mutagen.parser;

import dev.mutagen.model.EndpointInfo;
import dev.mutagen.model.HttpMethod;
import dev.mutagen.model.ParseResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.*;

class ControllerParserTest {

    private ControllerParser parser;
    private Path fixturesDir;

    @BeforeEach
    void setUp() {
        parser = new ControllerParser();
        fixturesDir = Paths.get("src/test/resources/fixtures");
    }

    @Test
    void scan_detectsRestController() {

        ParseResult result = parser.scan(fixturesDir);

        assertThat(result.getControllersFound()).isEqualTo(1);
        assertThat(result.getEndpoints()).isNotEmpty();
    }

    @Test
    void scan_extractsCorrectNumberOfEndpoints() {

        ParseResult result = parser.scan(fixturesDir);

        // 6 endpoints in UserController
        assertThat(result.getEndpoints()).hasSize(6);
    }

    @Test
    void scan_extractsGetEndpointCorrectly() {

        ParseResult result = parser.scan(fixturesDir);

        EndpointInfo endpoint = findEndpoint(result, HttpMethod.GET, "/api/v1/users/{id}");

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.getMethodName()).isEqualTo("getUserById");

        assertThat(endpoint.getPathParams()).hasSize(1);
        assertThat(endpoint.getPathParams().getFirst().getName()).isEqualTo("id");
        assertThat(endpoint.getPathParams().getFirst().getJavaType()).isEqualTo("Long");
    }

    @Test
    void scan_extractsQueryParamsCorrectly() {

        ParseResult result = parser.scan(fixturesDir);

        EndpointInfo endpoint = findEndpoint(result, HttpMethod.GET, "/api/v1/users");

        assertThat(endpoint.getQueryParams())
                .extracting("name")
                .containsExactlyInAnyOrder("search", "page", "size");
    }

    @Test
    void scan_extractsRequestBodyCorrectly() {

        ParseResult result = parser.scan(fixturesDir);

        EndpointInfo createUser = findEndpoint(result, HttpMethod.POST, "/api/v1/users");

        assertThat(createUser).isNotNull();
        assertThat(createUser.getRequestBody()).isNotNull();
        assertThat(createUser.getRequestBody().getJavaType()).isEqualTo("CreateUserRequest");
        assertThat(createUser.getRequestBody().isValidated()).isTrue();
    }

    @Test
    void scan_detectsAuthAnnotations() {

        ParseResult result = parser.scan(fixturesDir);

        EndpointInfo deleteUser = findEndpoint(result, HttpMethod.DELETE, "/api/v1/users/{id}");
        assertThat(deleteUser.isRequiresAuth()).isTrue();

        EndpointInfo getUsers = findEndpoint(result, HttpMethod.GET, "/api/v1/users");
        assertThat(getUsers.isRequiresAuth()).isFalse();
    }

    @Test
    void scan_extractsHeaderParamsCorrectly() {

        ParseResult result = parser.scan(fixturesDir);

        EndpointInfo endpoint =
                findEndpoint(result, HttpMethod.GET, "/api/v1/users/{userId}/orders");

        assertThat(endpoint).isNotNull();
        assertThat(endpoint.getHeaderParams()).hasSize(1);

        assertThat(endpoint.getHeaderParams().getFirst().getJavaType())
                .isEqualTo("String");
    }

    @Test
    void scan_setsFullPathCorrectlyWithBasePath() {

        ParseResult result = parser.scan(fixturesDir);

        List<String> paths = result.getEndpoints()
                .stream()
                .map(EndpointInfo::getFullPath)
                .toList();

        assertThat(paths).containsExactlyInAnyOrder(
                "/api/v1/users",
                "/api/v1/users/{id}",
                "/api/v1/users",
                "/api/v1/users/{id}",
                "/api/v1/users/{id}",
                "/api/v1/users/{userId}/orders"
        );

        // maar unieke combinaties method+path moeten 6 zijn
        Set<String> unique = result.getEndpoints()
                .stream()
                .map(e -> e.getHttpMethod() + ":" + e.getFullPath())
                .collect(java.util.stream.Collectors.toSet());

        assertThat(unique).hasSize(6);
    }

    @Test
    void scan_emptyDirectory_returnsEmptyResult() {

        ParseResult result = parser.scan(Paths.get("src/test/resources/empty"));

        assertThat(result.getEndpoints()).isEmpty();
        assertThat(result.getControllersFound()).isEqualTo(0);
    }

    // ----------------------------------------------------------

    private EndpointInfo findEndpoint(ParseResult result,
                                      HttpMethod method,
                                      String path) {

        return result.getEndpoints()
                .stream()
                .filter(e ->
                        e.getHttpMethod() == method &&
                                path.equals(e.getFullPath()))
                .findFirst()
                .orElse(null);
    }
}