# Mutagen

> AI-powered API test generator for Spring Boot. Scans your repository, generates RestAssured integration tests, runs mutation testing, and opens a merge request — all in one CI step.

---

## How it works

```
Your repo
   │
   ├── 1. PARSE      Scans @RestController classes via AST (no regex)
   ├── 2. GENERATE   Sends endpoint data to an LLM → writes RestAssured tests
   ├── 3. MUTATE     Runs Pitest, analyses surviving mutants, fills coverage gaps
   └── 4. MR         Opens a GitLab MR (or GitHub PR) with the generated tests
```

Mutagen is optimized for **Claude** (Anthropic) but also supports OpenAI and Azure OpenAI.

---

## Quick start

### GitLab CI

Add to your `.gitlab-ci.yml`:

```yaml
include:
  - component: gitlab.com/mutagen-dev/mutagen/scan@1.0.0
    inputs:
      mutation_threshold: 80
```

Add your API key as a masked CI/CD variable in `Settings → CI/CD → Variables`:

| Variable | Description |
|---|---|
| `ANTHROPIC_API_KEY` | Claude API key (recommended) |
| `OPENAI_API_KEY` | OpenAI API key (alternative) |
| `TESTGEN_API_KEY` | Mutagen hosted key (coming soon) |

### Local

```bash
export ANTHROPIC_API_KEY=sk-ant-...
java -jar mutagen.jar /path/to/your/repo
```

---

## LLM providers

Mutagen picks the provider based on the first environment variable it finds:

| Priority | Variable | Provider |
|---|---|---|
| 1 | `TESTGEN_API_KEY` | Mutagen hosted (managed Claude) |
| 2 | `ANTHROPIC_API_KEY` | Anthropic Claude (BYOK) |
| 3 | `OPENAI_API_KEY` | OpenAI GPT-4o (BYOK) |

Optional overrides:

```bash
ANTHROPIC_MODEL=claude-opus-4-5           # default: claude-sonnet-4-20250514
OPENAI_MODEL=gpt-4-turbo                  # default: gpt-4o
OPENAI_BASE_URL=https://...openai.azure.com/...  # Azure OpenAI
TESTGEN_BASE_URL=https://your-proxy.com   # self-hosted proxy
```

---

## Configuration

Add a `mutagen.yml` to your repository root to override defaults:

```yaml
source_paths:
  - src/main/java
test_output_path: src/test/java
mutation_threshold: 80        # minimum mutation score to aim for (%)
max_mutation_iterations: 3    # how many gap-fill loops to run
build_tool: maven             # maven | gradle
```

---

## Custom skills

Mutagen uses skill files to drive code generation. The built-in skills work out of the box, but you can override them to match your team's conventions.

Create a `mutagen-skills/` directory in your repository:

```
mutagen-skills/
├── restassured-test.md       # how to write RestAssured tests
└── mutation-gap-analysis.md  # how to analyse surviving mutants
```

Then point Mutagen to it:

```bash
MUTAGEN_SKILLS_PATH=./mutagen-skills java -jar mutagen.jar .
```

Or in GitLab CI:

```yaml
variables:
  MUTAGEN_SKILLS_PATH: "$CI_PROJECT_DIR/mutagen-skills"
```

Skill files are plain markdown. They become the system prompt for every generation request. See the [built-in skills](src/main/resources/skills/) for reference.

---

## API key security

**Always** use environment variables or CI/CD secrets. Never hardcode keys.

```bash
# correct
export ANTHROPIC_API_KEY=sk-ant-...
java -jar mutagen.jar .

# never do this
java -jar mutagen.jar --api-key sk-ant-...   # visible in process list
```

In GitLab, set `ANTHROPIC_API_KEY` as a **masked** and **protected** variable under `Settings → CI/CD → Variables`. Mutagen reads it automatically — no further configuration needed.

---

## Building from source

Requirements: Java 21, Maven 3.9+

```bash
git clone https://gitlab.com/mutagen-dev/mutagen.git
cd mutagen
mvn package -q
java -jar target/mutagen.jar /path/to/repo
```

---

## Project structure

```
src/main/java/dev/mutagen/
├── parser/        # AST-based Spring Boot controller scanner
├── generator/     # LLM-driven RestAssured test generator
├── skill/         # Skill loader with user-override support
├── llm/
│   ├── client/    # LlmClient interface, factory, exception
│   ├── model/     # LlmRequest, LlmResponse
│   └── provider/  # Anthropic, OpenAI, Proxy implementations
├── mutation/      # Pitest runner, report parser, mutation loop
├── git/           # GitLab MR and GitHub PR integration
└── model/         # EndpointInfo, ParseResult, ParamInfo, ...

src/main/resources/skills/
├── restassured-test.md
└── mutation-gap-analysis.md
```

---

## Roadmap

- [x] Endpoint parser (AST-based, Spring Boot)
- [x] Multi-provider LLM client (Anthropic, OpenAI, proxy)
- [x] Skill system with user overrides
- [x] RestAssured test generator
- [x] Pitest mutation loop (subprocess, XML report parsing, LLM gap-fill)
- [x] GitLab MR integration
- [x] GitHub PR integration
- [ ] GitLab CI/CD Catalog component
- [ ] Hosted proxy (mutagen.dev)

---

## Easter egg

In the TMNT universe, the substance that mutates the turtles into heroes is called **Mutagen**. This tool mutates your test suite — and kills the mutants. The circle is complete.

---

## License

MIT
