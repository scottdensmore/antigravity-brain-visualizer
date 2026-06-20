import { beforeEach, describe, expect, it } from "vitest";
import {
  state,
  escapeHtml,
  syntaxHighlight,
  formatTime,
  updateTranscriptFilter,
} from "../../main/resources/public/modules/utils.js";

beforeEach(() => {
  // Reset shared singleton state between tests.
  state.activeFilters = {
    userQueries: false,
    toolsCalled: false,
    outcomeErrors: false,
    modelResponses: false,
  };
  state.spansMultipleDays = false;
  document.body.innerHTML = "";
});

describe("escapeHtml", () => {
  it("escapes HTML-significant characters", () => {
    expect(escapeHtml('<a href="x">b & c\'d</a>')).toBe(
      "&lt;a href=&quot;x&quot;&gt;b &amp; c&#039;d&lt;/a&gt;"
    );
  });

  it("returns empty string for falsy input", () => {
    expect(escapeHtml("")).toBe("");
    expect(escapeHtml(null)).toBe("");
    expect(escapeHtml(undefined)).toBe("");
  });
});

describe("syntaxHighlight", () => {
  it("wraps each JSON token type in a classed span", () => {
    const html = syntaxHighlight({ a: 1, b: "x", c: true, d: null });
    expect(html).toContain('class="json-key"');
    expect(html).toContain('class="json-number"');
    expect(html).toContain('class="json-string"');
    expect(html).toContain('class="json-boolean"');
    expect(html).toContain('class="json-null"');
  });

  it("accepts a pre-stringified JSON string", () => {
    const html = syntaxHighlight('{"n": 42}');
    expect(html).toContain('<span class="json-number">42</span>');
  });
});

describe("formatTime", () => {
  it("returns empty string for missing input", () => {
    expect(formatTime("")).toBe("");
    expect(formatTime(null)).toBe("");
  });

  it("renders a 24-hour time for a single-day session", () => {
    const out = formatTime("2026-06-19T09:05:00Z");
    expect(out).toMatch(/^\d{2}:\d{2}$/);
  });

  it("prepends the date when the session spans multiple days", () => {
    state.spansMultipleDays = true;
    const out = formatTime("2026-06-19T09:05:00Z");
    // e.g. "Jun 19, 09:05"
    expect(out).toMatch(/[A-Za-z]{3}\s+\d{1,2},\s+\d{2}:\d{2}/);
  });
});

describe("updateTranscriptFilter", () => {
  function buildTranscript() {
    document.body.innerHTML = `
      <div class="sequence-wrapper">
        <div class="step-card" data-is-user="true" data-is-tool="false" data-is-error="false" data-is-model="false"></div>
        <div class="step-card" data-is-user="false" data-is-tool="true" data-is-error="false" data-is-model="false"></div>
      </div>
      <div class="sequence-wrapper">
        <div class="step-card" data-is-user="false" data-is-tool="false" data-is-error="true" data-is-model="false"></div>
      </div>
    `;
  }

  it("shows every card when no filter is active", () => {
    buildTranscript();
    updateTranscriptFilter();
    document
      .querySelectorAll(".step-card")
      .forEach((c) => expect(c.style.display).toBe("block"));
  });

  it("shows only matching cards and hides empty sequence wrappers", () => {
    buildTranscript();
    state.activeFilters.userQueries = true;
    updateTranscriptFilter();

    const cards = document.querySelectorAll(".step-card");
    expect(cards[0].style.display).toBe("block"); // user card
    expect(cards[1].style.display).toBe("none"); // tool card
    expect(cards[2].style.display).toBe("none"); // error card

    const wrappers = document.querySelectorAll(".sequence-wrapper");
    expect(wrappers[0].style.display).toBe("block"); // has the visible user card
    expect(wrappers[1].style.display).toBe("none"); // only the hidden error card
  });
});
