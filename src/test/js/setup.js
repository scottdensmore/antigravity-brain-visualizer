// Global stubs for browser-only libraries loaded via CDN <script> tags in index.html.
// The frontend modules reference these as ambient globals.

// `marked` (markdown renderer) — stub with an identity-ish parser so rendering logic is testable.
globalThis.marked = {
  parse: (text) => (text == null ? "" : String(text)),
};

// `hljs` (syntax highlighter) — stub so calls are no-ops in tests.
globalThis.hljs = {
  highlightElement: () => {},
};
