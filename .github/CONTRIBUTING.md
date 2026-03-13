# Contributing to Mutagen

Thanks for your interest in contributing!

## Getting started

Requirements: Java 21, Maven 3.9+

```bash
git clone https://github.com/arjanassink1978/mutagen.git
cd mutagen
mvn test
```

## How to contribute

1. **Check existing issues** before opening a new one
2. **For bugs**: open an issue first so we can discuss before you spend time on a fix
3. **For features**: open an issue to discuss the approach — the scope matters
4. Fork the repo, create a branch, submit a PR

## Pull request guidelines

- Keep PRs focused — one thing per PR
- Add or update tests for any changed behaviour
- All tests must pass: `mvn test`
- The project builds cleanly: `mvn package -DskipTests`
- No breaking changes to the CLI interface without discussion

## Project structure

```
src/main/java/dev/mutagen/
├── parser/        # AST-based Spring Boot controller scanner
├── generator/     # LLM-driven RestAssured test generator
├── skill/         # Skill loader (built-in + user overrides)
├── llm/           # LLM client interface, factory, providers
├── mutation/      # Pitest runner, report parser, mutation loop
├── output/        # MavenModuleWriter — generates rest-assured-tests module
├── git/           # GitLab MR and GitHub PR integration
└── model/         # Shared data models

src/main/resources/skills/
├── restassured-test.md       # system prompt for test generation
├── abstract-it.md            # system prompt for AbstractIT generation
└── mutation-gap-analysis.md  # system prompt for mutation gap-fill
```

## Skill files

The skill files in `src/main/resources/skills/` are the system prompts sent to the LLM. They have the most direct impact on test quality. Improvements here are very welcome — just make sure the generated tests still compile and pass against a real Spring Boot project.

## Questions?

Open an issue with the `question` label.
