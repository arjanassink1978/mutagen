# Mutagen

![Java](https://img.shields.io/badge/java-21-orange)
![Mutation Testing](https://img.shields.io/badge/mutation-pitest-green)
![License](https://img.shields.io/badge/license-MIT-blue)
![AI](https://img.shields.io/badge/AI-Claude%20%7C%20OpenAI-purple)

> **AI-powered API test generator for Spring Boot**

Mutagen scans your repository, generates **RestAssured integration tests**, validates them with **mutation testing**, and automatically opens a **GitHub PR or GitLab MR**.

Unlike typical AI test generators, Mutagen **does not stop at generating tests**.

It uses **mutation testing feedback loops** to improve tests until they actually detect bugs.

⭐ **If you like the idea, consider starring the repository.**

---

# Why Mutagen?

Writing high-quality API tests is slow and often incomplete.

Mutagen automates the entire workflow:

1. **Discovers endpoints** directly from your code using AST parsing  
2. **Generates integration tests** using an LLM  
3. **Runs mutation testing** to measure test quality  
4. **Improves tests automatically** based on surviving mutants  
5. **Creates a PR / MR** with the generated tests

Result:

- stronger test suites
- higher mutation coverage
- less manual test writing

---

# Example

Input controller:

```java
@RestController
@RequestMapping("/users")
class UserController {

    @GetMapping("/{id}")
    User getUser(@PathVariable Long id) { ... }

    @PostMapping
    User createUser(@RequestBody CreateUserRequest req) { ... }
}
```

Mutagen generates a test:

```java
@Test
void getUser_returns200() {
    given()
        .pathParam("id", 1)
    .when()
        .get("/users/{id}")
    .then()
        .statusCode(200);
}
```

Mutation testing then runs:

```
Pitest mutation score: 62%
Surviving mutants detected in UserService
```

Mutagen generates **additional tests** to kill the remaining mutants.

---

# How it works

```
Your repo
   │
   ├── 1. PARSE      Scans @RestController classes via AST
   ├── 2. GENERATE   Sends endpoint data to an LLM → writes RestAssured tests
   ├── 3. MUTATE     Runs Pitest mutation testing
   └── 4. PR/MR      Opens GitHub PR or GitLab MR
```

Architecture overview:

```
Spring Boot repo
      │
      ▼
AST Controller Parser
      │
      ▼
Endpoint Model
      │
      ▼
LLM Test Generator
      │
      ▼
Generated RestAssured tests
      │
      ▼
Pitest Mutation Testing
      │
      ▼
LLM Mutation Gap Analysis
      │
      ▼
GitHub PR / GitLab MR
```

---

# Quick start

## Run locally

Mutagen uses **BYOK (Bring Your Own Key)**.

You can use your own LLM provider key. Mutagen **never proxies or stores your keys**.

Supported providers:

- **Anthropic (Claude)**
- **OpenAI**

Export one of the following environment variables.

Anthropic:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
```

OpenAI:

```bash
export OPENAI_API_KEY=sk-...
```

Then run Mutagen:

```bash
java -jar mutagen.jar /path/to/repo mutate
```

Other commands:

```bash
java -jar mutagen.jar /path/to/repo generate
java -jar mutagen.jar /path/to/repo parse
```

---

# CI usage

## GitLab CI

Add to `.gitlab-ci.yml`:

```yaml
include:
  - component: gitlab.com/mutagen-dev/mutagen/scan@1.0.0
    inputs:
      mutation_threshold: 80
```

Add your API key in:

```
Settings → CI/CD → Variables
```

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Claude API key |
| `OPENAI_API_KEY` | OpenAI API key |

---

# LLM providers

Mutagen automatically selects the provider based on environment variables.

| Priority | Variable | Provider |
|---|---|---|
| 1 | `ANTHROPIC_API_KEY` | Claude |
| 2 | `OPENAI_API_KEY` | OpenAI |

Optional overrides:

```bash
ANTHROPIC_MODEL=claude-opus-4-5
OPENAI_MODEL=gpt-4.1-turbo
OPENAI_BASE_URL=https://...openai.azure.com
```

---

# Configuration

Create `mutagen.yml` in your repository:

```yaml
source_paths:
  - src/main/java

test_output_path: src/test/java

mutation_threshold: 80
max_mutation_iterations: 3

build_tool: maven
```

---

# Custom skills

Mutagen uses **skill files** to guide LLM generation.

Create:

```
mutagen-skills/
├── restassured-test.md
└── mutation-gap-analysis.md
```

Run with custom skills:

```bash
MUTAGEN_SKILLS_PATH=./mutagen-skills java -jar mutagen.jar .
```

Skill files are plain markdown and become the **system prompt**.

---

# API key security

Always use **environment variables or CI/CD secrets**.

Correct:

```bash
export ANTHROPIC_API_KEY=sk-ant-...
java -jar mutagen.jar .
```

Never expose API keys in command arguments.

Mutagen reads keys directly from the environment.

---

# Building from source

Requirements:

- Java 21
- Maven 3.9+

```bash
git clone https://github.com/arjanassink1978/mutagen.git
cd mutagen

mvn package -q

java -jar target/mutagen.jar /path/to/repo mutate
```

---

# Project structure

```
src/main/java/dev/mutagen/

parser/        AST-based Spring Boot controller scanner
generator/     LLM-driven RestAssured test generator
skill/         skill loader

llm/
 ├ client/
 ├ model/
 └ provider/

mutation/      Pitest runner and mutation loop
git/           GitHub PR and GitLab MR integration
model/         Endpoint models
```

Built-in skills:

```
src/main/resources/skills/
├── restassured-test.md
└── mutation-gap-analysis.md
```

---

# Limitations

Currently optimized for:

- Spring Boot REST APIs
- Maven projects
- RestAssured integration tests

---

# Roadmap

- [x] AST-based endpoint parser
- [x] Multi-provider LLM client
- [x] Skill system
- [x] RestAssured test generator
- [x] Pitest mutation feedback loop
- [x] Auth probing
- [x] Embedded `@SpringBootTest` support
- [x] GitHub PR integration
- [x] GitLab MR integration
- [ ] GitLab CI/CD Catalog component
- [ ] Incremental runs
- [ ] GitHub Action
- [ ] Free vs paid plan

---

# Easter egg

In the TMNT universe, the substance that mutates the turtles into heroes is called **Mutagen**.

This tool mutates your test suite — and **kills the mutants**.

---

# License

This project is licensed under the **MIT License**.

You are free to use, modify and distribute it.  
See the `LICENSE` file for details.
