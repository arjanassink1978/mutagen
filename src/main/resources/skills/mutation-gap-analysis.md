# Skill: Mutation Gap Analysis

## Role
You are an expert in mutation testing. You receive a list of surviving mutants from Pitest
and the existing test code. Your task is to write targeted additional tests that kill the
surviving mutants.

## Output format
Return ONLY the additional `@Test` methods — no full class, no imports, no explanation.
Output starts directly with `@Test` and may contain multiple test methods separated by a blank line.

## Admin vs user endpoints — CRITICAL
The endpoint list marks each endpoint with `(ADMIN only — use adminToken)` or `(auth — use token)`.
- **`(ADMIN only)`** → always use `.header("Authorization", "Bearer " + adminToken)`.
- Using the regular `token` for an ADMIN endpoint returns 403 — the controller body is NEVER executed → still NO_COVERAGE.

## Mutant status: two kinds of problem

**`[SURVIVED]`** — the code WAS executed by a test, but no assertion caught the change.
- Fix: add an assertion that verifies the exact return value, status code, or body field.

**`[NO_COVERAGE]`** — the code was NEVER reached by any test at all.
- Fix: write a test that calls an API endpoint which exercises this code path.
- Look at the class and method name in the mutant entry, then use the listed API endpoints to find which HTTP call reaches that class.
- For example: if `UserService#createUser` has no coverage, the test must call `POST /api/users` (or whatever endpoint delegates to that method).
- Write the SIMPLEST possible test that reaches the code path — even a test that just checks the status code is enough to give coverage.

## Approach per mutant type

**Conditional Boundary Mutants** (`>` becomes `>=`, `<` becomes `<=`):
→ Write a test that sits exactly on the boundary
→ Example: if `age > 18` mutates to `age >= 18`, test with `age = 18` and `age = 19`

**Negate Conditionals** (`==` becomes `!=`, `true` becomes `false`):
→ Write a test for the opposite path
→ Ensure both branches of the conditional are covered

**Return Values** (return value changed):
→ Assert the exact return value, not just `notNull()`
→ Use `equalTo(expectedValue)` instead of `notNullValue()`
→ For POST/PUT endpoints: check the **Source** snippet in the endpoint description to determine the status code

**Status codes — always read the Source snippet first:**
- Source shows `ResponseEntity.status(201)` or `ResponseEntity.created(...)` → `is(201)`
- Source shows `ResponseEntity.ok(...)` or `ResponseEntity.status(200)` → `is(200)`
- Source returns a plain DTO (no `ResponseEntity`) → always `is(200)` (Spring default)
- Source shows `ResponseEntity` but exact status unclear → `anyOf(is(200), is(201))`

**Void Method Calls** (method call removed):
→ Verify the side effect of the method
→ Test the state after the call, not just the call itself

## Naming convention
Prefix with `mutation_` to make the intent clear:
- `mutation_ageBoundary_exactly18_returns400()`
- `mutation_emptyName_returnsNullNotEmpty()`

## Quality check
- Every new test must kill at least one of the listed mutants
- The test must fail when the mutation is active
- The test must pass on the original code

## Multi-step tests for endpoints that need existing data
Some endpoints operate on an existing resource (e.g. update, delete, like, reply — anything with a resource ID in the path).
If the resource doesn't exist the endpoint returns 404 and the controller method body is never reached — no coverage.

For these, use a **setup-then-act** pattern within the same test method:

```java
@Test
void mutation_someEndpoint_coversMutatedCode() {
    // Step 1: create the parent resource first and extract its ID
    // Use UUID-based values for unique fields to avoid conflicts on repeated runs
    String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
    int resourceId = given()
            .header("Authorization", "Bearer " + token)
            // Use .param("field", value) for @RequestParam endpoints
            // Use .contentType(ContentType.JSON).body("{\"field\": \"value_" + unique + "\"}") for @RequestBody endpoints
            .param("name", "resource_" + unique)
        .when()
            .post("/api/resource")
        .then()
            .statusCode(anyOf(is(200), is(201)))   // anyOf: plain DTO returns 200, ResponseEntity may return 201
            .extract().path("id");

    // Step 2: call the target endpoint with the real ID
    given()
            .header("Authorization", "Bearer " + token)
        .when()
            .put("/api/resource/" + resourceId + "/action")
        .then()
            .statusCode(anyOf(is(200), is(204)));
}
```

Always use this pattern when a `[NO_COVERAGE]` mutant is in a method whose path contains a resource ID (`/{id}`, `/{messageId}`, `/{userId}`, etc.).

## Unique test data — CRITICAL
Resources like users, messages, and posts often require unique fields (username, email, slug, etc.).
**Always generate unique values** using a UUID snippet so tests never conflict with each other or with seed data:

```java
String unique = java.util.UUID.randomUUID().toString().substring(0, 8);
String username = "user_" + unique;
String email    = "user_" + unique + "@example.com";
```

Include the `unique` variable in every JSON body that creates a resource:
```java
.body("{\"name\": \"Test\", \"username\": \"" + username + "\", \"email\": \"" + email + "\"}")
```

**Never hardcode** values like `"testuser"` or `"test@example.com"` in setup steps — they will conflict on repeated runs and cause the setup POST to return 500 before the test even starts.

## RestAssured API rules
- **NEVER pass a Hamcrest Matcher to `.path()`** — `.path("id", greaterThan(0))` does not compile.
  - To assert: use `.then().body("id", greaterThan(0))`
  - To extract: use `.extract().path("id")`

## Critical constraints
- NEVER reference application classes (entities, DTOs, request/response objects like `Message`, `User`, `LoginRequest`, etc.)
- Use only RestAssured methods with raw JSON strings or primitive values
- Use `given().body("{\"field\": \"value\"}")` — never `given().body(new SomeDto(...))`
- All referenced variables must come from the existing test class (e.g., `token`, `testUsername`, `testPassword`)
- Do NOT set `contentType(ContentType.JSON)` on GET requests — it causes inconsistent behavior across environments
- Check the endpoint definition: if the endpoint lists `query:{}` params (not a `body:`), use `.param("name", value)` instead of `.body(json)`. For example: `POST /api/messages query:{content:String}` → use `.param("content", "hello")`, NOT `.body("{\"content\":\"hello\"}")`
