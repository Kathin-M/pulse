// Small fetch wrapper. A 401 anywhere sends the app back to the login screen.
const listeners = new Set();
export const onAuthLost = (fn) => (listeners.add(fn), () => listeners.delete(fn));

async function request(method, path, body) {
  const res = await fetch(path, {
    method,
    credentials: "same-origin",
    headers: body ? { "Content-Type": "application/json" } : undefined,
    body: body ? JSON.stringify(body) : undefined,
  });
  if (res.status === 401) {
    listeners.forEach((fn) => fn());
    throw new Error("login required");
  }
  if (!res.ok) {
    let detail = res.statusText;
    try { detail = (await res.json()).detail || detail; } catch {}
    throw new Error(detail);
  }
  return res.json();
}

export const api = {
  get: (p) => request("GET", p),
  post: (p, b) => request("POST", p, b || {}),
  put: (p, b) => request("PUT", p, b || {}),
  patch: (p, b) => request("PATCH", p, b || {}),
  del: (p) => request("DELETE", p),
};

export function interact(story_id, action, extra = {}) {
  return api.post("/api/interact", { story_id, action, ...extra });
}

export function haptic(ms = 10) {
  try { navigator.vibrate && navigator.vibrate(ms); } catch {}
}

export function ago(iso) {
  const s = (Date.now() - new Date(iso).getTime()) / 1000;
  if (s < 60) return "just now";
  if (s < 3600) return `${Math.floor(s / 60)}m ago`;
  if (s < 86400) return `${Math.floor(s / 3600)}h ago`;
  const d = Math.floor(s / 86400);
  return d === 1 ? "yesterday" : `${d}d ago`;
}

export function dayBucket(iso) {
  const d = new Date(iso);
  const now = new Date();
  const start = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const diff = (start - new Date(d.getFullYear(), d.getMonth(), d.getDate())) / 86400000;
  if (diff <= 0) return "Today";
  if (diff === 1) return "Yesterday";
  if (diff < 7) return "This week";
  return "Earlier";
}

/** System share sheet where available, otherwise copy the link. Sharing also tells the ranker it mattered. */
export async function shareStory(card) {
  const url = card.url;
  const title = card.title;
  interact(card.id > 0 ? card.id : (card.story_ids || [card.id])[0], "share").catch(() => {});
  if (navigator.share) {
    try { await navigator.share({ title, text: title, url }); return "shared"; } catch { return null; }
  }
  try { await navigator.clipboard.writeText(`${title}\n${url}`); return "copied"; } catch { return null; }
}
