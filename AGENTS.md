# Repository Guidelines

## Project Structure & Module Organization
- `src/main/java`: Core mod sources (server/shared).
- `src/client/java`: Client-only code (rendering, screens).
- `src/main/resources`: Mod metadata and assets (`fabric.mod.json`, mixins, textures).
- `src/client/resources`: Client mixin config.
- `build.gradle`, `gradle.properties`: Build and version settings.
- Output: built JARs in `build/libs`.

## Build, Test, and Development Commands
- `./gradlew build`: Compiles, remaps, and packages the mod JAR. ALWAYS run via cmd
- `./gradlew runClient`: Launches a Fabric dev client with the mod.
- `./gradlew clean`: Removes build outputs.
- `./gradlew tasks`: Lists available Gradle/Loom tasks.

search the javadocs for correct api usage: https://maven.fabricmc.net/docs/fabric-api-0.135.0+1.21.10/ https://maven.fabricmc.net/docs/fabric-loader-0.17.3/ https://maven.fabricmc.net/docs/yarn-25w41a+build.2/

Always run gradlew build via cmd after every code modification, and use search to look up api methods.

## Coding Style & Naming Conventions
- Language: Java 21 (see `build.gradle`).
- Indentation: 4 spaces; wrap at ~120 columns.
- Names: `UpperCamelCase` classes, `lowerCamelCase` methods/fields, `UPPER_SNAKE_CASE` constants.
- Packages: `ninja.trek.mc.goldgolem...` for sources; place Mixins under `mixin` packages and register in `gold-golem.mixins.json` or `gold-golem.client.mixins.json`.
- Resources: Keep IDs and paths lowercase with dashes (e.g., `assets/gold-golem/...`).
Long-running tooling (tests, docker compose, migrations, etc.) must always be invoked with sensible timeouts or in non-interactive batch mode. Never leave a shell command waiting indefinitely—prefer explicit timeouts, scripted runs, or log polling after the command exits.

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
