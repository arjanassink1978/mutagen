# Mutagen — CLAUDE.md

## Wat is dit project?

AI-powered integration test generator voor Spring Boot. Mutagen scant een repository, genereert RestAssured tests via een LLM, voert Pitest mutation testing uit, vult coverage-gaps, en opent een GitLab MR of GitHub PR — alles in één CI-stap.

## Tech stack

- Java 21, Maven
- JavaParser (AST-based controller scanning)
- RestAssured (gegenereerd testformaat)
- Pitest (mutation testing via subprocess)
- gitlab4j-api / github-api (MR/PR aanmaken)
- Anthropic Claude als primaire LLM (ook OpenAI en proxy)
- Picocli (CLI)
- Jackson (JSON + XML parsing)

## Projectstructuur

```
src/main/java/dev/mutagen/
├── Main.java                    ← CLI entry point (picocli)
├── model/                       ← EndpointInfo, ParseResult, ParamInfo, RequestBodyInfo, HttpMethod
├── parser/                      ← ControllerParser, DtoFieldResolver, RepoScanner
├── llm/
│   ├── client/                  ← LlmClient (interface), LlmClientFactory, LlmException
│   ├── model/                   ← LlmRequest, LlmResponse
│   └── provider/                ← AnthropicLlmClient, OpenAiLlmClient, ProxyLlmClient
├── skill/                       ← SkillLoader, Skill (prompt .md bestanden)
├── generator/                   ← TestGeneratorService, PromptBuilder, GeneratedTest
├── mutation/
│   ├── model/                   ← Mutant, MutationReport
│   ├── MutationReportParser.java
│   ├── MutationRunner.java      ← interface
│   ├── PitestRunner.java
│   ├── PitestException.java
│   ├── MutationLoopService.java
│   └── MutationLoopResult.java
└── git/
    ├── GitClient.java           ← interface
    ├── GitClientFactory.java
    ├── GitException.java
    ├── GitLabClient.java
    ├── GitHubClient.java
    ├── MrDescription.java
    └── PrService.java

src/main/resources/skills/
├── restassured-test.md          ← systeem prompt voor testgeneratie
└── mutation-gap-analysis.md     ← systeem prompt voor mutation gap-fill
```

## LLM provider prioriteit

1. `TESTGEN_API_KEY` → hosted proxy (`ProxyLlmClient`)
2. `ANTHROPIC_API_KEY` → Anthropic Claude (`AnthropicLlmClient`)
3. `OPENAI_API_KEY` → OpenAI (`OpenAiLlmClient`, met kwaliteitswaarschuwing)

Optionele overrides: `ANTHROPIC_MODEL`, `ANTHROPIC_BASE_URL`, `OPENAI_MODEL`, `OPENAI_BASE_URL`, `TESTGEN_BASE_URL`

## Git platform env vars

**GitLab:** `GITLAB_TOKEN`, `CI_PROJECT_ID`, `CI_SERVER_URL` (standaard: `https://gitlab.com`)
**GitHub:** `GITHUB_TOKEN`, `GITHUB_REPOSITORY` (formaat: `owner/repo`)

## Build en tests

```bash
mvn package -q                        # bouwt target/mutagen.jar
mvn test                              # 102 tests, allemaal groen
java -jar target/mutagen.jar /pad/naar/repo
```

## Wat is er gebouwd ✅

### Module 1 — Parser
`RepoScanner` → `ControllerParser` → `DtoFieldResolver`

- Walkt de repo, vindt alle `@RestController` / `@Controller` klassen via AST (JavaParser)
- Extraheert endpoints: HTTP methode, volledig pad, path/query/header params, request body, auth-annotaties, validatieconstraints
- Verrijkt request bodies met DTO-veldtypes
- Output: `ParseResult` (serialiseerbaar naar `endpoints.json`)
- Alle tests slagen

### Module 2 — LLM Test Generator
`TestGeneratorService` → `PromptBuilder` → `LlmClient`

- Groepeert endpoints per controller
- Bouwt een gestructureerde prompt per controller
- Stuurt naar LLM met `restassured-test.md` skill als systeem prompt
- Sanitizeert output (strips markdown fences, preamble)
- Geeft `GeneratedTest` objecten terug met broncode + tokengebruik
- Alle tests slagen

### Module 3 — Pitest Mutation Loop
`PitestRunner` → `MutationReportParser` → `MutationLoopService`

- `Mutant` / `MutationReport`: model voor `mutations.xml` (Jackson XML)
- `MutationRunner`: interface voor testbaarheid (`MockMutationRunner` in tests)
- `PitestRunner`: start `mvn org.pitest:pitest-maven:mutationCoverage` als subprocess, detecteert Maven/Gradle automatisch, leidt `targetClasses` en `targetTests` af uit gegenereerde tests
- `MutationLoopService`: orkestreert de loop — schrijf tests → pitest → parse → LLM gap-fill → herhaal tot drempel of max iteraties
- `MutationLoopResult`: record met `initialScore`, `finalScore`, `iterationsRun`, augmented tests
- Score: `killed / (killed + survived + no_coverage) * 100`
- Alle tests slagen (37 nieuwe tests)

### Module 4 — GitLab MR / GitHub PR
`GitClient` → `GitLabClient` / `GitHubClient` → `PrService`

- `GitClient`: interface met `openPullRequest(branch, tests, description)`
- `GitLabClient`: maakt branch, commit bestanden via `RepositoryFileApi`, opent MR via `MergeRequestApi`. Accepteert `GitLabApi` via constructor (injecteerbaar)
- `GitHubClient`: zelfde flow via `GHRepository` van kohsuke. SHA-based branch creatie
- `GitClientFactory`: kiest platform op basis van env vars, met duidelijke foutmeldingen
- `MrDescription`: bouwt markdown MR-body met test classes, endpoints, mutation score voor/na
- `PrService`: genereert branchnaam (`mutagen/tests-yyyyMMdd-HHmmss`), orkestreert de flow
- Alle tests slagen (32 nieuwe tests)

### Module 5 — GitLab CI Component
`Dockerfile` → `docker-compose.yml` → `templates/scan.yml` → `.gitlab-ci.yml`

- `Dockerfile`: multi-stage build (Maven builder → Alpine JRE + git + maven runtime)
- `docker-compose.yml`: lokaal testen — mount project als volume, env vars voor API keys
- `templates/scan.yml`: GitLab CI/CD Catalog component met `spec.inputs` (`mutation_threshold`, `source_path`, `test_output_path`, `image`)
- `.gitlab-ci.yml`: eigen pipeline — test stage (`mvn verify`) + publish-image stage (Docker build + push naar registry, alleen main/tags)
- Alle bestanden aangemaakt

## Openstaande refactor ⏳

### fat JAR → Maven plugin
Modules 1–4 zijn klaar als fat JAR met picocli CLI. Dit moet omgezet worden naar een Maven plugin zodat teams het als `<plugin>` in hun eigen `pom.xml` kunnen configureren:

- `Main.java` vervalt
- `pom.xml` krijgt `<packaging>maven-plugin</packaging>`
- `maven-plugin-plugin` toegevoegd als build plugin
- Elke subcommand wordt een aparte Mojo: `@Mojo(name = "generate")`
- Picocli dependency vervalt — Maven plugin gebruikt eigen `@Parameter`-annotaties

## Technische aandachtspunten

- **Java 25 + Mockito**: Byte Buddy ondersteunt Java 25 nog niet officieel. Fix: `-Dnet.bytebuddy.experimental=true` in Surefire `argLine` (staat in `pom.xml`)
- **Locale**: Gebruik altijd `Locale.US` in `String.format` voor decimalen (systeem is NL-locale)
- **gitlab4j 6.x**: `RepositoryFileApi.createFile()` geeft `RepositoryFileResponse` terug (niet `RepositoryFile`). Encoding via `Constants.Encoding.BASE64` enum (niet String)
- **`@JsonIgnoreProperties(ignoreUnknown = true)`** op `Mutant` — Pitest's XML heeft extra attributen (`detected`, `numberOfTestsRun`) die we negeren
