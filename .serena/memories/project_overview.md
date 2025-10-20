# Gold Golem Minecraft Mod - Project Overview

## Purpose
Gold Golem is a Fabric mod for Minecraft 1.21.10 that adds a Gold Golem entity with wall-building capabilities. The golem can scan, analyze, and build complex wall structures based on templates and modules.

## Tech Stack
- **Platform**: Minecraft Fabric Mod
- **Minecraft Version**: 1.21.10
- **Java Version**: 21
- **Build Tool**: Gradle 9.1.0
- **Mod Loader**: Fabric Loader 0.17.3
- **Dependencies**:
  - Fabric API: 0.135.0+1.21.10
  - GeckoLib (for 3D entity animations): 5.3-alpha-1
  - Loom (Fabric build tool): 1.11-SNAPSHOT

## Architecture
- **Main Entry Point**: `ninja.trek.mc.goldgolem.GoldGolem` (server-side initialization)
- **Client Entry Point**: `ninja.trek.mc.goldgolem.GoldGolemClient` (client-side rendering and screens)
- **Data Generation**: `ninja.trek.mc.goldgolem.datagen.GoldGolemDataGenerator`
- **Mixins**: Used for modifying vanilla Minecraft classes (client and common mixins)

## Key Components
1. **Entities**: Gold Golem entity with custom AI goals
2. **Wall System**: Complex wall building system with modules, templates, validation
3. **Networking**: Custom packets for client-server communication
4. **GUI**: Custom screen handlers for golem interface
5. **Summoning**: Pumpkin-based summoning system

## Development Environment
- **OS**: Windows 11
- **IDE Support**: IntelliJ IDEA configuration present
- **Project Path**: C:\Users\niall\_dev\mc\goldgolem
- **Source Sets**: Split environment (main, client) using Fabric Loom