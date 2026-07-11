# Agent Brain Visualizer

You are an AI coding assistant working on the **Agent Brain Visualizer** project. 
This project provides an interactive web UI for inspecting Antigravity AI agent JSONL execution transcripts.
*(Context: Transcripts consist of sequential JSON objects like `USER_INPUT`, `MODEL`, and `TOOL_CALL`, which the frontend parses and renders into a timeline).*

## Technology Stack
- **Backend**: Java 21+, Micronaut
- **Frontend**: Vanilla JavaScript (ES6+), HTML5, CSS3 (No framework)
- **AI Integration**: LangChain4j, with a pluggable provider — Google Gemini (remote, default) or a local model via Ollama (e.g. Gemma). Selected with `AI_PROVIDER` (`gemini` | `ollama`). (Docs: https://docs.langchain4j.dev/integrations/language-models/google-genai/ and https://docs.langchain4j.dev/integrations/language-models/ollama/)
- **Build Tool**: Gradle

## Development Environment & Commands

### Running the Application
- The application requires the `GEMINI_API_KEY` environment variable to be set at runtime for the LangChain4j integration.
- Start the server: `export GEMINI_API_KEY="..." && ./gradlew run`
- By default, the application runs on `http://localhost:8080`.

### Testing
- Run the test suite: `./gradlew test`

### Formatting & Linting
- Always format code before committing: `./gradlew spotlessApply`
- Check formatting: `./gradlew spotlessCheck`

### Frontend Workflow
- Frontend files are located in `src/main/resources/public/`.
- No Node.js, npm, or build step is required for the frontend.
- When modifying CSS or JS, you need to run `./gradlew processResources` to update the classpath resources, and then refresh your browser.

## Development Workflow

Follow this workflow for every change. It is deliberately test-first and
review-gated; the sub-agents named below live in `.claude/agents/`.

1. **Orient before touching anything.** Inspect the repository, the current Git
   state (`git status`, current branch), and every applicable instruction file
   (this `AGENTS.md`, `README.md`, `CLAUDE.md` if present). Preserve unrelated
   staged, unstaged, and untracked work — never stage or revert changes you did
   not make.
2. **Branch first.** Create a branch off `main` before making code changes; do
   not commit feature work directly to `main`.
3. **Use test-driven development** whenever behavior or structure is testable:
   - Add or update a focused test *before* the implementation.
   - Run it and confirm it fails for the expected reason (a test that passes
     before you write the code is testing the wrong thing).
   - Implement the smallest appropriate change.
   - Re-run the focused test(s) while iterating (`./gradlew test --tests '…'`,
     `npm test`, `go test ./…`).
4. **Read the whole diff** (`git diff`, `git diff --staged`) and remove any
   accidental or unrelated changes before going further.
5. **Run the `ui-review` sub-agent** after an implementation pass, before
   verification, whenever the change touches a CLI or the web UI. It reviews the
   branch diff for CLI design, flags/arguments, help and error messages, exit
   codes, stdin/stdout/stderr and composability, and — for the web UI —
   standards, accessibility, and consistency. Address every actionable finding
   before verifying.
6. **Run the `verifier` sub-agent** to perform the builds and tests appropriate
   to the change (`./gradlew build`, `npm test`, `npm run e2e`, `go test`). It
   reports failures, flakes, missing coverage, and environment issues. Fix or
   explicitly resolve every actionable finding; if a fix changes code, rerun the
   verifier.
7. **Run the `code-review` sub-agent before every commit**, against the branch
   diff and all staged, unstaged, and untracked files. Address every actionable
   finding before committing. (This is the same review the built-in
   `/code-review` skill performs.)
8. **Commit** using Conventional Commits (`feat:`, `fix:`, `chore:`,
   `refactor:`, `docs:`, `test:`) once verification and code review are clean.
   Run `./gradlew spotlessApply` first.
9. **Before opening a pull request:** confirm local verification still holds;
   rerun `code-review` only if the reviewed state changed since the pre-commit
   review (code, tests, docs, generated files, conflict resolution, or any
   staged/unstaged/untracked content) — do not repeat it on an unchanged
   worktree. Push and open the PR only after that.
10. **Merge only on a clean state** — GitHub reports a clean merge and all
    configured checks (Backend, Frontend unit, End-to-end) pass. Self-merges are
    allowed when those conditions are met; see *Pull Request & Git Requirements*
    for the squash-merge command.

## Code Style & Conventions

### Java (Backend)
- Follow standard Java conventions.
- Format code using Spotless (`./gradlew spotlessApply`). This applies Prettier formatting to Java files and automatically injects the Apache 2.0 license header.
- Use dependency injection via Micronaut (`@Singleton`, `@Controller`).
- Do not use fully-qualified names (FQN) for classes in the code; always add the appropriate `import` statements at the top of the file.
- Do not use wildcard/star imports (e.g., `import java.util.*;`); always list each individual import explicitly.
- Do not expose sensitive endpoints or tokens.
- When searching for Java dependencies, use the Maven Central repository's REST API (documented here: https://central.sonatype.org/search/rest-api-guide/) and parse its `json` output.

### JavaScript (Frontend)
- Use modern Vanilla JS (ES6+).
- Organize code into modules (`import`/`export`) in `src/main/resources/public/modules/`.
- Do not use any UI frameworks (React, Vue, Angular, Tailwind).
- Prefer `document.querySelector` and standard DOM manipulation APIs.
- Keep rendering functions isolated and maintainable.

### CSS
- Use Vanilla CSS with CSS variables (`:root`) for theming and color palettes.
- Use Flexbox and CSS Grid for complex layouts.
- Keep a clean, responsive, and modern aesthetic (e.g. glassmorphism, transitions).

## Project Structure
- `src/main/java/io/github/glaforge/agybrainviz/` - Backend source code
  - `AnalysisController.java` - REST API for LangChain4j/Gemini processing and local file serving
  - `ChatModelFactory.java` - Configuration for the Gemini LLM
  - `BrainVisualizer.java` - Application entry point
- `src/main/resources/public/` - Frontend assets
  - `index.html` - Main HTML entry point
  - `style.css` - Global styles
  - `app.js` - Main frontend application logic
  - `modules/` - JS modules for specific domains (`timeline.js`, `stats.js`, `analysis.js`, `utils.js`, `ui.js`)
- `build.gradle` - Gradle build configuration

## Safety & Permissions

### Allowed without prompting
- Read files, list directories, search project code.
- Perform single-file edits or multi-file refactoring.
- Run `./gradlew spotlessApply` for formatting.
- Run `./gradlew processResources` to refresh UI assets.

### Require approval first
- Executing `./gradlew run`.
- Modifying `build.gradle` dependencies.
- Modifying `.gitignore`.
- Git operations (`commit`, `push`).

## Pull Request & Git Requirements
- Ensure all code is formatted (`./gradlew spotlessApply`).
- Do not commit any sensitive data or API keys (e.g. `GEMINI_API_KEY`).
- Use Conventional Commits (`feat:`, `fix:`, `chore:`, `refactor:`).
- Always **squash and merge** pull requests — the repository is configured
  squash-only (merge commits and rebase merges are disabled, and the branch is
  deleted on merge). Merge with `gh pr merge <n> --squash --delete-branch`,
  never `--merge` or `--rebase`. The squashed commit takes the PR title and
  body, so keep both clean and descriptive.
- **Merge as soon as all PR checks are green** — once every required CI check
  (Backend, Frontend unit, End-to-end) passes, proceed with the squash merge
  without waiting for further approval. To let GitHub merge automatically when
  the checks pass, queue it with `gh pr merge <n> --squash --auto --delete-branch`
  (repository auto-merge is enabled).
