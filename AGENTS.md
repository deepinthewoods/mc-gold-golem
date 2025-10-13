# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java`: Core mod sources (server/shared).
- `src/client/java`: Client-only code (rendering, screens).
- `src/main/resources`: Mod metadata and assets (`fabric.mod.json`, mixins, textures).
- `src/client/resources`: Client mixin config.
- `build.gradle`, `gradle.properties`: Build and version settings.
- Output: built JARs in `build/libs`.

## Build, Test, and Development Commands
- `./gradlew build`: Compiles, remaps, and packages the mod JAR.
- `./gradlew runClient`: Launches a Fabric dev client with the mod.
- `./gradlew clean`: Removes build outputs.
- `./gradlew tasks`: Lists available Gradle/Loom tasks.

Example: Windows PowerShell `./gradlew.bat runClient`.

## Coding Style & Naming Conventions
- Language: Java 21 (see `build.gradle`).
- Indentation: 4 spaces; wrap at ~120 columns.
- Names: `UpperCamelCase` classes, `lowerCamelCase` methods/fields, `UPPER_SNAKE_CASE` constants.
- Packages: `ninja.trek.mc.goldgolem...` for sources; place Mixins under `mixin` packages and register in `gold-golem.mixins.json` or `gold-golem.client.mixins.json`.
- Resources: Keep IDs and paths lowercase with dashes (e.g., `assets/gold-golem/...`).

## Testing Guidelines
- Framework: JUnit 5 recommended (none present yet).
- Location: `src/test/java` mirroring package structure.
- Naming: `ClassNameTest.java`; test methods describe behavior.
- Run: `./gradlew test` when tests exist; ensure build passes before PRs.

## Commit & Pull Request Guidelines
- Commits: Imperative, concise subject (≤72 chars), optional scope. Example: `feat(mixin): add health boost for gold golems`.
- Include brief body explaining rationale and any side effects.
- PRs: Clear description, link issues (e.g., `Closes #12`), summarize changes, add screenshots/logs if client-visible.
- Checks: Ensure `build` and (if present) `test` pass; run local `runClient` sanity check.

## Security & Configuration Tips
- Do not commit secrets or local paths.
- Update versions in `gradle.properties`; keep Loader/Fabric API compatible with `minecraft_version`.
- If adding data generation, keep outputs versioned and deterministic under `src/generated` or excluded from VCS as appropriate.

