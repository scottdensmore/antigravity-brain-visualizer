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

function formatDuration(totalSeconds) {
  const secs = Math.round(totalSeconds || 0);
  const mins = Math.floor(secs / 60);
  const hrs = Math.floor(mins / 60);
  if (hrs > 0) return `${hrs}h ${mins % 60}m`;
  if (mins > 0) return `${mins}m ${secs % 60}s`;
  return `${secs}s`;
}

function statCard(label, value, sub) {
  return `<div class="stat-card">
      <div class="stat-label">${label}</div>
      <div class="stat-value">${escapeHtml(String(value))}</div>
      <div class="stat-sub">${escapeHtml(sub)}</div>
    </div>`;
}

// Each row drills into the sessions behind it: `category` is the drill-down kind (tool/error/…).
function barList(items, color, emptyText, category) {
  if (!items || items.length === 0) {
    return `<div class="stat-sub">${escapeHtml(emptyText)}</div>`;
  }
  const max = items[0].count || 1;
  return items
    .map((it) => {
      const width = Math.max((it.count / max) * 100, 2);
      return `<div class="drill-row" data-drill-category="${escapeHtml(
        category
      )}" data-drill-key="${escapeHtml(it.name)}">
          <div class="drill-bar" style="display:flex; align-items:center; gap:16px; margin-bottom:4px; cursor:pointer;" title="Show the sessions behind this">
            <div style="flex:0 0 280px; font-size:0.82rem; color:var(--text-primary); text-align:right; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;">${escapeHtml(
              it.name
            )}</div>
            <div style="flex:1; height:8px; background:rgba(30,41,59,0.5); border-radius:4px; overflow:hidden;">
              <div style="height:100%; width:${width}%; background:${color}; border-radius:4px;"></div>
            </div>
            <div style="flex:0 0 36px; font-size:0.85rem; color:var(--text-secondary); font-weight:600;">${
              it.count
            }</div>
          </div>
          <div class="drill-sessions" style="display:none;"></div>
        </div>`;
    })
    .join("");
}

function renderDrillSessions(result) {
  const sessions = (result && result.sessions) || [];
  if (sessions.length === 0) {
    return `<div class="stat-sub" style="padding:4px 0 10px 24px;">No sessions.</div>`;
  }
  const total = result.totalMatches || sessions.length;
  const more =
    total > sessions.length ? ` (showing ${sessions.length} of ${total})` : "";
  const rows = sessions
    .map(
      (s) =>
        `<div class="drill-session" data-id="${escapeHtml(
          s.id
        )}" style="padding:3px 0 3px 24px; font-size:0.82rem; color:var(--accent-purple); cursor:pointer;" title="${escapeHtml(
          s.id
        )}">↳ ${escapeHtml(s.title || s.id)}</div>`
    )
    .join("");
  return `<div style="border-left:2px solid rgba(148,163,184,0.2); margin:2px 0 12px 8px;">${rows}
      <div class="stat-sub" style="padding:2px 0 4px 24px;">${escapeHtml(
        `${total} session${total === 1 ? "" : "s"}${more}`
      )}</div>
    </div>`;
}

// Opens a session by clicking its sidebar item (reusing the app's own selection), else via the hash.
// Matches by dataset rather than a built selector, so any id (even one with quotes) is safe.
function openSession(id) {
  const item = Array.from(document.querySelectorAll(".conv-item")).find(
    (el) => el.dataset.id === id
  );
  if (item) item.click();
  else window.location.hash = id;
}

async function toggleDrill(row, flavor) {
  const sub = row.querySelector(".drill-sessions");
  if (sub.dataset.loaded === "1") {
    sub.style.display = sub.style.display === "none" ? "block" : "none";
    return;
  }
  const category = row.dataset.drillCategory;
  const key = row.dataset.drillKey;
  sub.style.display = "block";
  sub.innerHTML = `<div class="stat-sub" style="padding:4px 0 8px 24px;">Loading sessions…</div>`;
  try {
    const res = await fetch(
      `/api/insights/sessions?flavor=${encodeURIComponent(
        flavor
      )}&category=${encodeURIComponent(category)}&key=${encodeURIComponent(
        key
      )}`
    );
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    sub.innerHTML = renderDrillSessions(await res.json());
    sub.dataset.loaded = "1";
    sub.querySelectorAll(".drill-session").forEach((el) => {
      el.addEventListener("click", () => openSession(el.dataset.id));
    });
  } catch (e) {
    sub.innerHTML = `<div class="stat-sub" style="padding:4px 0 8px 24px; color:red;">Failed to load sessions.</div>`;
  }
}

function section(title, color, bodyHtml) {
  return `<div style="margin-top:28px;">
      <div style="font-size:0.75rem; font-weight:700; color:${color}; margin-bottom:16px; letter-spacing:0.05em; text-transform:uppercase;">${escapeHtml(
    title
  )}</div>
      ${bodyHtml}
    </div>`;
}

export function renderInsights(report, container) {
  if (!container) return;
  const r = report || {};
  const tools = r.topTools || [];
  const errors = r.topErrors || [];
  const recs = r.topRecommendations || [];
  const issues = r.topIssues || [];
  const label = FLAVOR_LABELS[r.flavor] || r.flavor || "Sessions";

  const sampledNote =
    r.sampledSessions < r.sessionCount
      ? ` (analyzed ${r.sampledSessions} most recent)`
      : "";

  container.innerHTML = `
    <div class="insights-view" style="padding:8px 4px 40px;">
      <h2 style="margin:0 0 4px; font-size:1.4rem; color:var(--text-primary);">Fleet Insights</h2>
      <div class="stat-sub" style="margin-bottom:20px;">${escapeHtml(
        label
      )} · ${r.sessionCount || 0} sessions${escapeHtml(sampledNote)}</div>

      <div class="stats-grid">
        ${statCard(
          "SESSIONS",
          r.sessionCount || 0,
          `${r.analyzedSessions || 0} AI-analyzed`
        )}
        ${statCard(
          "OUTCOMES",
          `${r.cleanSessions || 0} clean`,
          `${r.sessionsWithErrors || 0} hit errors`
        )}
        ${statCard(
          "TOOL CALLS",
          r.toolCallTotal || 0,
          `${r.avgToolsPerSession || 0} avg per session`
        )}
        ${statCard(
          "AVG DURATION",
          formatDuration(r.avgDurationSeconds),
          "per session"
        )}
      </div>

      ${section(
        "Top Tools",
        "var(--accent-purple)",
        barList(tools, "var(--accent-purple)", "No tool calls recorded", "tool")
      )}
      ${section(
        "Most Common Errors",
        "var(--error)",
        barList(errors, "var(--error)", "No errors detected 🎉", "error")
      )}
      ${section(
        "Recommendation Backlog",
        "#10b981",
        barList(
          recs,
          "#10b981",
          "No recommendations yet — generate AI analysis on more sessions",
          "recommendation"
        )
      )}
      ${section(
        "Recurring Issues",
        "#f59e0b",
        barList(issues, "#f59e0b", "No issues recorded yet", "issue")
      )}
    </div>`;

  // Each tally row expands to the sessions behind it (drill-down).
  container.querySelectorAll(".drill-row").forEach((row) => {
    row
      .querySelector(".drill-bar")
      .addEventListener("click", () => toggleDrill(row, r.flavor));
  });
}

export async function showInsights(flavor) {
  const container = document.getElementById("transcript-container");
  if (!container) return;
  container.innerHTML =
    '<div class="loading-state" style="text-align:center; padding:40px; color:#94a3b8;">Computing fleet insights…</div>';
  try {
    const res = await fetch(
      `/api/insights?flavor=${encodeURIComponent(flavor)}`
    );
    const report = await res.json();
    renderInsights(report, container);
  } catch (e) {
    container.innerHTML =
      '<div class="loading-state" style="text-align:center; color:red;">Failed to load insights.</div>';
  }
}
