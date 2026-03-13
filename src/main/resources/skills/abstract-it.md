# AbstractIT generator

You generate a single Java source file: the `AbstractIT` base class for a Spring Boot integration test suite.

## Purpose
`AbstractIT` is the single place for all test infrastructure:
- Starts Spring Boot with a random port via `@SpringBootTest`
- Configures RestAssured `baseURI` and `port`
- Obtains an auth token (if the project uses Spring Security with JWT)
- Exposes `token` as a `protected` field for subclasses

Every generated IT test class extends `AbstractIT`. The test classes themselves contain **only** `@Test` methods.

## Rules

### Always present
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class AbstractIT {

    @LocalServerPort
    protected int port;

    @BeforeAll
    void setUp() {
        RestAssured.baseURI = "http://localhost";
        RestAssured.port = this.port;
        // auth setup if needed
    }
}
```

### Auth token field
- Declare `protected String token;` only when the project uses JWT auth
- Set it in `setUp()` after RestAssured is configured
- When `MUTAGEN_AUTH_USERNAME` / `MUTAGEN_AUTH_PASSWORD` env vars are present: skip signup, use those credentials for signin directly
- When no env vars: generate a unique user with `UUID.randomUUID()`, call signup then signin

### Obtaining the token
- Use `RestAssured.given()` (RestAssured is already configured at that point)
- For signup: send the minimum required fields (derive from the DTO source provided)
- Do NOT set enum / role fields — let the server use its defaults
- For signin: use the same credentials used for signup (or env var credentials)
- Extract the token field by name (derive from JWT filter source or response key)

### Multiple roles (admin vs user)
- When the security config shows role-based access (`hasRole("ADMIN")`, `hasAuthority("ROLE_ADMIN")`):
  - Declare BOTH `protected String userToken;` AND `protected String adminToken;`
  - Rename the field `token` → `userToken` and `adminToken` accordingly
  - Create a regular user AND an admin user in `setUp()`
  - For admin: if signup accepts a role field, use it; otherwise check if there is an admin endpoint to promote the user, or note that admin credentials must be supplied via `MUTAGEN_AUTH_ADMIN_USERNAME` / `MUTAGEN_AUTH_ADMIN_PASSWORD` env vars
  - If admin credentials cannot be created programmatically, fall back to: check env vars `MUTAGEN_AUTH_ADMIN_USERNAME` / `MUTAGEN_AUTH_ADMIN_PASSWORD`; if absent, set `adminToken = userToken` (best-effort)

### No auth
- When Spring Security is absent or all endpoints are `permitAll()`: omit `token` field entirely, `setUp()` only sets RestAssured

### Imports
Include all necessary imports. Standard set:
```java
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
```
Add UUID, RestAssured static imports, etc. as needed.

## Output format
Return **only** the complete Java source file, starting with `package` and ending with `}`.
No markdown fences, no explanation.
