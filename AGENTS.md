# AGENTS

## Scope
These guidelines apply to the entire repository.

## Code style
- Kotlin files use four spaces for indentation and follow the official Kotlin coding conventions.
- Keep lines reasonably short (≈100 characters).
- Organize imports automatically and remove unused ones.
- Prefer idiomatic Kotlin and Compose patterns; avoid leaving trailing whitespace.

## Testing and linting
Before committing, make a best effort to run:

```bash
./gradlew test
./gradlew lint
```

Both commands require the presence of `bitvavo.properties` and `trading212.properties` in the project
root. These files should contain API keys but **must not** be committed to version control.

## Documentation
- Update `README.md` or other relevant docs when behavior or setup changes.

## Commit messages
- Use the imperative mood (e.g., "Add feature" not "Added feature").
- Keep the first line under 72 characters.
