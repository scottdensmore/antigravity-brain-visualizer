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
import { escapeHtml } from "./utils.js";

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

export function renderEval(report, container) {
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

  container.innerHTML = `
    <div class="eval-view" style="padding:8px 4px 40px;">
      <h2 style="margin:0 0 4px; font-size:1.4rem; color:var(--text-primary);">Analysis Eval</h2>
      <div class="stat-sub" style="margin-bottom:20px;">${escapeHtml(
        label
      )} · ${r.sessionCount || 0} sessions · scored by <strong>${escapeHtml(
    r.modelLabel || "—"
  )}</strong></div>

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
    </div>`;
}

export async function showEval(flavor) {
  const container = document.getElementById("transcript-container");
  if (!container) return;
  container.innerHTML =
    '<div class="loading-state" style="text-align:center; padding:40px; color:#94a3b8;">Scoring analysis quality…</div>';
  try {
    const res = await fetch(`/api/eval?flavor=${encodeURIComponent(flavor)}`);
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const report = await res.json();
    renderEval(report, container);
  } catch (e) {
    container.innerHTML =
      '<div class="loading-state" style="text-align:center; color:red;">Failed to load the eval.</div>';
  }
}
