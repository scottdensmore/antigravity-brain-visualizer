# Antigravity Brain Visualizer

<p align="center">
  <img src="screenshot.png" alt="Antigravity Brain Visualizer Screenshot" width="800"/>
</p>

## What is this project?
The Antigravity Brain Visualizer is a dedicated companion tool for developers working with Antigravity AI agents. Antigravity agents construct complex reasoning chains, dispatch background tasks, spawn subagents, and execute system commands over long-running sessions. The agent records all of these interactions in detailed JSONL transcript files (the agent's "brain"). 

This visualizer parses those raw JSONL brain transcripts and renders them in a scannable and interactive web interface, allowing developers to inspect the agent's exact decision-making process.

## How it works
The visualizer automatically scans your local filesystem for agent session transcripts. When you select a conversation session from the sidebar, the application parses the JSONL steps and visually organizes the execution flow into interactive sequences. 

Additionally, it leverages Google's Gemini LLMs to automatically generate comprehensive executive summaries of long conversations—distilling thousands of lines of transcript into the core user intent, key technical decisions, and the final outcome of the session.

### Key Features
- **Interactive Timeline**: An expandable sequence timeline of user inputs, agent thoughts, system context events, and tool executions.
- **AI-Powered Summarization**: Automated multi-stage reduction of long transcripts using Gemini to provide instant high-level context.
- **Rich Formatting**: Code syntax highlighting for generated artifacts, JSON payloads, and terminal outputs.
- **Session Analytics**: Session statistics including step counts and tool usage frequency metrics.
- **Filtering**: Toggle specific event types (User Queries, Tool Calls, Errors, Model Responses) to cut through the noise of a long session.

## Technology Stack & Implementation
This project prioritizes a lightweight, high-performance, and maintainable architecture:

- **Backend**: Built with [Micronaut](https://micronaut.io/) (Java). It serves the frontend static assets and provides native REST APIs to securely read and parse the local file-system transcripts.
- **AI Integration**: Powered by [LangChain4j](https://github.com/langchain4j/langchain4j) connecting directly to [Google Gemini models](https://docs.langchain4j.dev/integrations/language-models/google-genai/). It uses chunking and recursive consolidation to process large transcript files that exceed standard token limits.
- **Frontend**: A zero-build Vanilla JavaScript, HTML, and CSS single-page application. It avoids heavy framework overhead, relying instead on standard browser DOM APIs, customized CSS grid/flexbox layouts, and minimal dependencies (`marked.js` and `highlight.js`) for efficient rendering and responsiveness.

## Running the Application

To run the application locally, you must provide your Gemini API key:

```bash
export GEMINI_API_KEY="your-api-key-here"
./gradlew run
```

By default, the visualizer will be available at [http://localhost:8080](http://localhost:8080).

### Customizing the Port

If you need to run the application on a different port, you can override it using the `MICRONAUT_SERVER_PORT` environment variable:

```bash
export MICRONAUT_SERVER_PORT=9090
export GEMINI_API_KEY="your-api-key-here"
./gradlew run
```

*(If you are running the compiled native executable directly, you can also append `-Dmicronaut.server.port=9090` to the command).*

## Building a Native Executable

Because this project is built with Micronaut, you can compile it into a highly-optimized, standalone native executable using GraalVM. 

1. Ensure you have [GraalVM](https://www.graalvm.org/) installed and set up as your active Java environment.
2. Run the native compilation task:

```bash
./gradlew nativeCompile
```

This generates a native executable in the `build/native/nativeCompile/` directory. You can run it directly:

```bash
export GEMINI_API_KEY="your-api-key-here"
./build/native/nativeCompile/agy-brain-viz
```

*(Note: Start-up times will be practically instantaneous compared to the standard JVM version).*

## License
This project is licensed under the Apache 2.0 License. See the [LICENSE](../LICENSE) file for details.

## Disclaimer
This is not an officially supported Google product.
