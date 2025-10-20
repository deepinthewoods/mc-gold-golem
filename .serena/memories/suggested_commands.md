# Suggested Commands for Gold Golem Development

## Build Commands (Windows)
```batch
# Clean build
gradlew.bat clean build

# Build without tests
gradlew.bat build -x test

# Generate sources (important for Minecraft development)
gradlew.bat genSources

# Generate Eclipse/IntelliJ files
gradlew.bat eclipse
gradlew.bat idea
```

## Run Commands
```batch
# Run Minecraft client
gradlew.bat runClient

# Run Minecraft server
gradlew.bat runServer

# Run data generation
gradlew.bat runDatagen
```

## Testing Commands
```batch
# Run tests
gradlew.bat test

# Run with debug output
gradlew.bat runClient --debug

# Run with specific Java version
gradlew.bat runClient -Dorg.gradle.java.home="C:\Program Files\Java\jdk-21"
```

## Development Workflow Commands
```batch
# Refresh dependencies
gradlew.bat --refresh-dependencies

# Check for dependency updates
gradlew.bat dependencyUpdates

# Create JAR file
gradlew.bat jar

# Create sources JAR
gradlew.bat sourcesJar

# Remap JAR for production
gradlew.bat remapJar
```

## Fabric-Specific Commands
```batch
# Download Minecraft assets
gradlew.bat downloadAssets

# Prepare development environment
gradlew.bat vscode

# Generate migration mappings
gradlew.bat migrateMappings --mappings "1.21.10+build.2"
```

## Git Commands (Windows)
```batch
# Check status
git status

# Stage changes
git add .

# Commit
git commit -m "message"

# Push to remote
git push origin main
```

## Utility Commands
```batch
# List tasks
gradlew.bat tasks

# Project properties
gradlew.bat properties

# Clean cache
gradlew.bat cleanLoomBinaries
gradlew.bat cleanLoomMappings
```

## System Commands (Windows)
```batch
# List directory
dir /b

# Change directory  
cd src\main\java

# Find files
dir /s /b *.java | findstr "Golem"

# Search in files
findstr /s /i "pattern" *.java
```