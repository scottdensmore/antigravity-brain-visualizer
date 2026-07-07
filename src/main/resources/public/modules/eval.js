/*
 * Copyright 2026 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import { escapeHtml, formatTime } from "./utils.js";

const FLAVOR_LABELS = {
  "antigravity-cli": "Antigravity CLI",
  "antigravity-ide": "Antigravity IDE",
  antigravity: "Antigravity Agent",
  codex: "OpenAI Codex",
  "claude-code": "Claude Code",
};

// Human-readable descriptions for the deterministic checks (must match EvalScorer check names).
const CHECK_LABELS = {
  "schema-complete": "Has title, summary & flow",
  "has-recommendations": "Proposes a recommendation",
  "issues-have-fixes": "Every issue records a fix",
  "concise-summary": "Summary present & not oversized",
  "not-degenerate": "No repetition / Base64 blobs",
  "error-coverage": "Errored runs surface an issue",
};

function scoreColor(score) {
  if (score >= 80) return "#10b981";
  if (score >= 50) return "#f59e0b";
  return "var(--error)";
}

function section(title, color, bodyHtml) {
  return `<div style="margin-top:28px;">
      <div style="font-size:0.75rem; font-weight:700; color:${color}; margin-bottom:16px; letter-spacing:0.05em; text-transform:uppercase;">${escapeHtml(
    title
  )}</div>
      ${bodyHtml}
    </div>`;
}

function passRateRow(item, evaluated) {
  const label = CHECK_LABELS[item.name] || item.name;
  const pct = evaluated > 0 ? Math.round((item.count / evaluated) * 100) : 0;
  return `<div style="display:flex; align-items:center; gap:16px; margin-bottom:10px;">
      <div style="flex:0 0 260px; font-size:0.82rem; color:var(--text-primary); text-align:right;" title="${escapeHtml(
        item.name
      )}">${escapeHtml(label)}</div>
      <div style="flex:1; height:8px; background:rgba(30,41,59,0.5); border-radius:4px; overflow:hidden;">
        <div style="height:100%; width:${pct}%; background:${scoreColor(
    pct
  )}; border-radius:4px;"></div>
      </div>
      <div style="flex:0 0 70px; font-size:0.85rem; color:var(--text-secondary); font-weight:600;">${
        item.count
      }/${evaluated} · ${pct}%</div>
    </div>`;
}

function rubricCard(label, value) {
  const v = value ?? 0;
  return `<div class="stat-card">
      <div class="stat-label">${label}</div>
      <div class="stat-value" style="color:${scoreColor(v * 20)};">${escapeHtml(
    String(v)
  )}<span style="font-size:0.9rem; color:var(--text-secondary);"> / 5</span></div>
      <div class="stat-sub">average</div>
    </div>`;
}

function judgedRow(c) {
  const s = c.score || {};
  return `<div style="display:flex; gap:16px; align-items:baseline; margin-bottom:12px;">
      <div style="flex:0 0 150px; font-size:0.82rem; color:var(--text-secondary); font-weight:600;">F ${
        s.faithfulness
      } · A ${s.actionability} · C ${s.clarity}</div>
      <div style="min-width:0;">
        <div style="font-size:0.88rem; color:var(--text-primary); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(
          c.sessionId || ""
        )}">${escapeHtml(c.title || c.sessionId || "session")}</div>
        <div class="stat-sub" style="margin-top:2px;">${escapeHtml(
          s.comment || ""
        )}</div>
      </div>
    </div>`;
}

function judgeHtmlFor(report, evaluated) {
  const j = report.judge || {};
  if (j.ran) {
    const cards = `<div class="stats-grid">
        ${rubricCard("FAITHFULNESS", j.avgFaithfulness)}
        ${rubricCard("ACTIONABILITY", j.avgActionability)}
        ${rubricCard("CLARITY", j.avgClarity)}
      </div>`;
    const rows = (j.cases || []).map(judgedRow).join("");
    return section(
      `LLM judge · rubric 1–5 · ${j.judgedSessions || 0} judged`,
      "var(--accent-purple)",
      cards + `<div style="margin-top:20px;">${rows}</div>`
    );
  }
  if (evaluated > 0) {
    return `<div style="margin-top:28px;">
        <button id="run-judge-btn" class="btn" style="padding:8px 14px; font-size:0.85rem; background:rgba(139,92,246,0.15); border:1px solid var(--accent-purple); color:var(--text-primary); cursor:pointer; border-radius:8px;">⚖️ Run LLM judge</button>
        <div class="stat-sub" style="margin-top:8px;">${escapeHtml(
          j.note || ""
        )}</div>
      </div>`;
  }
  return "";
}

function worstRow(c) {
  const failed = (c.failed || []).map((f) => CHECK_LABELS[f] || f);
  const failsHtml = failed.length
    ? failed.map((f) => `<span>✗ ${escapeHtml(f)}</span>`).join("")
    : "<span>—</span>";
  return `<div style="display:flex; gap:16px; align-items:baseline; margin-bottom:12px;">
      <div style="flex:0 0 44px; font-size:1rem; font-weight:700; color:${scoreColor(
        c.score
      )};">${c.score}</div>
      <div style="min-width:0;">
        <div style="font-size:0.88rem; color:var(--text-primary); overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(
          c.sessionId || ""
        )}">${escapeHtml(c.title || c.sessionId || "session")}</div>
        <div class="stat-sub" style="display:flex; flex-wrap:wrap; gap:10px; margin-top:2px;">${failsHtml}</div>
      </div>
    </div>`;
}

function round1(n) {
  return Math.round((n || 0) * 10) / 10;
}

// A signed delta badge vs the previous run's score (green up / red down / muted flat).
function deltaBadge(delta) {
  if (delta === null || delta === undefined) return "";
  const color =
    delta > 0
      ? "#10b981"
      : delta < 0
      ? "var(--error)"
      : "var(--text-secondary)";
  const sign = delta > 0 ? "+" : "";
  return `<span style="margin-left:8px; font-size:0.78rem; color:${color};">${sign}${escapeHtml(
    String(round1(delta))
  )}</span>`;
}

function historyRow(run, prev) {
  const delta = prev ? run.avgScore - prev.avgScore : null;
  const judged = run.judged
    ? ` · judge F ${round1(run.avgFaithfulness)} / A ${round1(
        run.avgActionability
      )} / C ${round1(run.avgClarity)}`
    : "";
  return `<div style="display:flex; gap:16px; align-items:baseline; margin-bottom:10px;">
      <div style="flex:0 0 70px; font-size:1rem; font-weight:700; color:${scoreColor(
        run.avgScore || 0
      )};">${escapeHtml(String(round1(run.avgScore)))}${deltaBadge(delta)}</div>
      <div style="min-width:0;">
        <div style="font-size:0.85rem; color:var(--text-primary);">${escapeHtml(
          run.modelLabel || "—"
        )}</div>
        <div class="stat-sub">${escapeHtml(formatTime(run.savedAt))} · ${
    run.evaluatedSessions || 0
  } evaluated${escapeHtml(judged)}</div>
      </div>
    </div>`;
}

function historySection(history) {
  if (!Array.isArray(history) || history.length === 0) {
    return section(
      "Run history",
      "var(--text-secondary)",
      `<div class="stat-sub">No saved runs yet — save a run to start tracking quality over time.</div>`
    );
  }
  // history is newest-first; compare each run to the next (older) one.
  const rows = history
    .map((run, i) => historyRow(run, history[i + 1]))
    .join("");
  return section("Run history", "var(--text-secondary)", rows);
}

export function renderEval(report, container, history = []) {
  if (!container) return;
  const r = report || {};
  const label = FLAVOR_LABELS[r.flavor] || r.flavor || "Sessions";
  const evaluated = r.evaluatedSessions || 0;
  const passRates = r.checkPassRates || [];
  const worst = r.worstCases || [];

  const bodyHtml =
    evaluated === 0
      ? `<div style="margin-top:20px; padding:14px 16px; background:rgba(245,158,11,0.1); border:1px solid rgba(245,158,11,0.4); border-radius:8px; font-size:0.88rem; color:var(--text-primary);">No analyzed sessions yet — run AI analysis on some sessions, then re-run the eval to score them.</div>`
      : section(
          "Check pass-rates",
          "var(--accent-purple)",
          passRates.map((it) => passRateRow(it, evaluated)).join("")
        ) +
        section(
          "Lowest-scoring analyses",
          "var(--error)",
          worst.map(worstRow).join("")
        );

  const saveBtn =
    evaluated > 0
      ? `<button id="save-run-btn" class="btn" style="padding:6px 12px; font-size:0.82rem; background:rgba(30,41,59,0.5); border:1px solid var(--border-color); color:var(--text-primary); cursor:pointer; border-radius:8px;">💾 Save run</button>`
      : "";

  container.innerHTML = `
    <div class="eval-view" style="padding:8px 4px 40px;">
      <div style="display:flex; justify-content:space-between; align-items:flex-start; gap:12px;">
        <div>
          <h2 style="margin:0 0 4px; font-size:1.4rem; color:var(--text-primary);">Analysis Eval</h2>
          <div class="stat-sub" style="margin-bottom:20px;">${escapeHtml(
            label
          )} · ${r.sessionCount || 0} sessions · scored by <strong>${escapeHtml(
    r.modelLabel || "—"
  )}</strong></div>
        </div>
        ${saveBtn}
      </div>

      <div class="stats-grid">
        <div class="stat-card">
          <div class="stat-label">AVG SCORE</div>
          <div class="stat-value" style="color:${scoreColor(
            r.avgScore || 0
          )};">${escapeHtml(String(r.avgScore ?? 0))}</div>
          <div class="stat-sub">out of 100</div>
        </div>
        <div class="stat-card">
          <div class="stat-label">EVALUATED</div>
          <div class="stat-value">${evaluated}</div>
          <div class="stat-sub">of ${r.sessionCount || 0} sessions</div>
        </div>
      </div>
      ${bodyHtml}
      ${judgeHtmlFor(r, evaluated)}
      ${historySection(history)}
    </div>`;

  // The "Run LLM judge" button re-fetches this same source with the judge enabled.
  const judgeBtn = container.querySelector("#run-judge-btn");
  if (judgeBtn) {
    judgeBtn.addEventListener("click", () => showEval(r.flavor, true));
  }

  // "Save run" persists this snapshot, then refreshes the history in place (no re-eval).
  const saveRunBtn = container.querySelector("#save-run-btn");
  if (saveRunBtn) {
    saveRunBtn.addEventListener("click", () => saveRun(r, container));
  }
}

export async function showEval(flavor, judge = false) {
  const container = document.getElementById("transcript-container");
  if (!container) return;
  const loading = judge
    ? "Running the LLM judge…"
    : "Scoring analysis quality…";
  container.innerHTML = `<div class="loading-state" style="text-align:center; padding:40px; color:#94a3b8;">${loading}</div>`;
  try {
    const url = `/api/eval?flavor=${encodeURIComponent(flavor)}${
      judge ? "&judge=true" : ""
    }`;
    const [res, history] = await Promise.all([
      fetch(url),
      fetchHistory(flavor),
    ]);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const report = await res.json();
    renderEval(report, container, history);
  } catch (e) {
    container.innerHTML =
      '<div class="loading-state" style="text-align:center; color:red;">Failed to load the eval.</div>';
  }
}

async function fetchHistory(flavor) {
  try {
    const res = await fetch(
      `/api/eval/runs?flavor=${encodeURIComponent(flavor)}`
    );
    return res.ok ? await res.json() : [];
  } catch (e) {
    return [];
  }
}

// Persist the current report as a run, then refresh just the history (no re-eval / no re-judge).
async function saveRun(report, container) {
  const btn = container.querySelector("#save-run-btn");
  if (btn) {
    btn.textContent = "Saving…";
    btn.disabled = true;
  }
  try {
    await fetch("/api/eval/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(report),
    });
  } catch (e) {
    // Non-fatal: fall through and re-render with whatever history we can fetch.
  }
  const history = await fetchHistory(report.flavor);
  renderEval(report, container, history);
}
