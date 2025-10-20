# Project Structure Overview

## Source Code Structure

### Main Source (`src/main/java/ninja/trek/mc/goldgolem/`)
- **Root**: Main mod initializer
- **block/entity/**: Block entity classes  
- **datagen/**: Data generation for resources
- **mixin/**: Common mixins
- **net/**: Network packets and handlers
- **registry/**: Entity and screen registrations
- **screen/**: Screen handlers and GUI logic
- **summon/**: Golem summoning mechanics
- **wall/**: Wall building system components
  - `WallDefinition`: Wall structure definitions
  - `WallScanner`: Scans existing walls
  - `WallModuleExtractor`: Extracts building modules
  - `WallModuleTemplate`: Template system
  - `WallModuleValidator`: Validates wall structures
  - `WallJoinSlice`: Join calculations
- **world/entity/**: Entity classes and AI goals

### Client Source (`src/client/java/ninja/trek/mc/goldgolem/`)
- **client/net/**: Client network handlers
- **client/render/**: Entity renderers
- **client/screen/**: GUI screens
- **client/state/**: Client-side state management
- **mixin/client/**: Client-specific mixins

### Resources
- **Main Resources** (`src/main/resources/`)
  - `fabric.mod.json`: Mod metadata
  - `gold-golem.mixins.json`: Common mixins config
  - **assets/gold-golem/**:
    - `animations/`: GeckoLib animations
    - `geo/`: 3D models
    - `lang/`: Translations
    - `icon.png`: Mod icon

- **Client Resources** (`src/client/resources/`)
  - `gold-golem.client.mixins.json`: Client mixins config

### Build Output
- **build/**: Gradle build directory
- **run/**: Development runtime directory (gitignored)
- **.gradle/**: Gradle cache and Fabric Loom files