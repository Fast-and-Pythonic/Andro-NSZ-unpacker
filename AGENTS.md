# AGENTS.md

Instructions for AI agents working with the AndroNSZ project.

## About the project

AndroNSZ is an Android application for converting NSZ to NSP (Nintendo Switch).
Kotlin/Compose UI + native C engine via JNI. The nicoboss/nsz Python reference port.

## Instruction files

| File                    | Purpose                                                                                                                                                                            |
|-------------------------|------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `project_structure.md`  | Project map: file tree, description of each module (Kotlin and C), dependency graph, data flow for all conversion modes, JNI interface, error codes. Read this first for any task. |
| `BUILD_DEBUG_CHECKS.md` | Build and verification checklist. Gradle commands for Kotlin compilation, full build, unit tests, lint. Order of checks, recommendations. Use after making code changes.           |
| `README.md`             | Brief project description for humans.                                                                                                                                              |
| `.claude/memory/`       | Claude Code auto-memory between sessions (project context, user profile, known bugs). For Claude Code only — other agents can ignore.                                              |

## Working rules

- **Communication language:** Russian.
- **The project owner** is not a professional programmer. Explain things simply, avoid jargon when unnecessary.
- **Tech stack:** Kotlin + Jetpack Compose (UI), C11 + CMake (native engine), JNI (bridge).
- **Do not touch without explicit request:** cryptography (AES-CTR, AES-XTS, SHA-256), decompression algorithm (ncz_decompress.c), JNI signatures. These modules are debugged and match the Python reference.
- **Build:** `.\gradlew.bat :app:assembleDebug` — main verification. Details in `BUILD_DEBUG_CHECKS.md`.
- **Architecture:** before making changes, check `project_structure.md` to understand the relationships between modules.

### Code style

### STRICTLY MANDATORY CODE FORMATTING RULES:

- **Indentation**: always exactly **3 spaces**. Never use tabs.
- **Empty lines inside blocks**:
- NEVER REMOVE THE INDENTATION ON EMPTY LINES.
    - An empty line inside a function, class, loop, or condition must maintain the current indentation level (3 spaces for each nesting level).
- **Keep indents on empty lines** is a strict rule.

Examples of correct formatting of indents and empty lines:

```
int calculate_sum(int arr[], int size) {
   if (size <= 0) {
      return 0;
   }
   
   int sum = 0;
   
   for (int i = 0; i < size; i++) {
      if (arr[i] > 0) {
         sum += arr[i];
         
         if (arr[i] > 100) {
            sum += 50;
         }
      }
   }
   
   return sum;
}
```



