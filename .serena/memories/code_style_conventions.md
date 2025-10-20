# Code Style and Conventions

## Java Standards
- **Java Version**: 21 (with release target)
- **Package Structure**: `ninja.trek.mc.goldgolem` base package
- **Source Compatibility**: Java 21

## Naming Conventions
- **Classes**: PascalCase (e.g., `GoldGolemEntity`, `WallScanner`)
- **Constants**: UPPER_SNAKE_CASE (e.g., `MOD_ID`)
- **Methods**: camelCase
- **Package Names**: lowercase with dots
- **Static Factory**: Uses static `id()` method for Identifiers

## Code Organization
- **Main Source**: `src/main/java`
- **Client Source**: `src/client/java`  
- **Resources**: `src/main/resources`
- **Client Resources**: `src/client/resources`
- **Generated Data**: `src/main/generated`

## Fabric-Specific Patterns
- **Mod ID**: `gold-golem` (used consistently across all resources)
- **Registry Pattern**: Static `init()` methods in registry classes
- **Networking**: Custom payload classes for client-server communication
- **Entrypoints**: Separate client and server initialization

## Inner Classes
- Used extensively for related functionality (e.g., `GoldGolemEntity.BuildMode`)
- Static nested classes for data structures

## Resource Conventions
- **Animations**: JSON format in `assets/gold-golem/animations/`
- **Models**: GeckoLib geo format in `assets/gold-golem/geo/`
- **Translations**: `assets/gold-golem/lang/en_us.json`

## Mixin Conventions
- Separate mixin configs for client and common
- Mixin classes suffixed with `Mixin`
- Located in `mixin` subpackage