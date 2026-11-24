import { useEffect, useState } from "react";
import { api } from "../api.js";

/* Feed mix presets: how much local vs national vs world news. */
const MIXES = {
  local: { label: "Local first", tiers: { city: 0.95, state: 0.75, india: 0.5, world: 0.25 } },
  balanced: { label: "Balanced", tiers: { city: 0.7, state: 0.5, india: 0.6, world: 0.4 } },
  world: { label: "India & world", tiers: { city: 0.35, state: 0.3, india: 0.75, world: 0.8 } },
};
const SURPRISE = [["0", "Off", 0], ["0.1", "A little", 0.1], ["0.25", "Lots", 0.25]];
const TRUST = [["Low", 0.3], ["Normal", 0.6], ["High", 0.9]];
const NEXT_MODE = { neutral: "follow", follow: "mute", mute: "neutral", always: "neutral" };

function mixOf(tiers) {
  for (const [k, m] of Object.entries(MIXES)) {
    if (Object.keys(m.tiers).every((t) => Math.abs((tiers?.[t] ?? 0) - m.tiers[t]) < 0.03)) return k;
  }
  return "custom";
}

function Segmented({ options, value, onChange }) {
  return (
    <div className="lg-segmented w-full" data-liquid-glass="regular">
      {options.map(([k, label]) => (
        <button key={k} type="button" aria-pressed={value === k} onClick={() => onChange(k)}>{label}</button>
      ))}
    </div>
  );
}

function Card({ children, className = "" }) {
  return <div className={`lg rounded-[22px] p-4 ${className}`} data-liquid-glass="regular">{children}</div>;
}

function Row({ title, detail, onClick }) {
  return (
    <button onClick={onClick} className="w-full flex items-center gap-3 px-4 py-3.5 text-left border-b border-divider/70 last:border-0">
      <span className="t-summary flex-1">{title}</span>
      <span className="t-meta text-ink-2 truncate max-w-[50%]">{detail}</span>
      <span className="text-ink-2">›</span>
    </button>
  );
}

function Page({ title, onBack, children }) {
  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="px-5 pt-4 pb-10 space-y-5">
        <button onClick={onBack} className="t-meta text-ink-2">‹ Controls</button>
        <h1 className="font-serif text-[28px] font-semibold -mt-2">{title}</h1>
        {children}
      </div>
    </div>
  );
}

function Toggle({ on, onChange, label, hint }) {
  return (
    <label className="flex items-center gap-3 py-2">
      <span className="flex-1">
        <span className="t-summary block">{label}</span>
        {hint && <span className="t-chip text-ink-2 block mt-0.5">{hint}</span>}
      </span>
      <span className="lg-switch"><input type="checkbox" checked={on} onChange={(e) => onChange(e.target.checked)} aria-label={label} /></span>
    </label>
  );
}

export default function Controls({ onInsights, onOnboarding, notify }) {
  const [c, setC] = useState(null);
  const [page, setPage] = useState(null);
  const [appUrl, setAppUrl] = useState(null);

  const load = () => api.get("/api/controls").then(setC);
  useEffect(() => { load(); api.get("/api/app/latest").then((r) => setAppUrl(r.releases)).catch(() => {}); }, []);

  const putPrefs = async (patch) => {
    const prefs = await api.put("/api/prefs", patch);
    setC((x) => ({ ...x, prefs }));
  };

  if (!c) return <div className="h-full" />;
  const back = () => setPage(null);
  const topicMode = (t) => c.rules.find((r) => r.kind === "topic" && r.label === t)?.mode || "neutral";
  const followed = c.rules.filter((r) => r.kind === "topic" && r.mode === "follow").length;
  const mutedTopics = c.rules.filter((r) => r.kind === "topic" && r.mode === "mute").length;
  const others = c.rules.filter((r) => r.kind !== "topic");
  const offSources = c.sources.filter((s) => !s.enabled).length;
  const glass = localStorage.getItem("pulse.glass") || "full";
  const theme = localStorage.getItem("pulse.theme") || "system";

  if (page === "topics") return (
    <Page title="Topics" onBack={back}>
      <p className="t-meta text-ink-2">Tap once to follow (more of it), twice to mute (never shown), three times to reset.</p>
      <div className="flex flex-wrap gap-2">
        {c.topics.map((t) => {
          const m = topicMode(t);
          return (
            <button key={t}
              onClick={async () => { await api.post("/api/rules", { label: t, kind: "topic", mode: NEXT_MODE[m] }); load(); }}
              className={`t-meta px-3.5 py-2 rounded-full ${m === "follow" ? "bg-accent text-black" : m === "mute" ? "bg-raised text-less line-through" : "bg-raised"}`}>
              {m === "follow" ? "✓ " : m === "mute" ? "✕ " : ""}{t}
            </button>
          );
        })}
      </div>
    </Page>
  );

  if (page === "keywords") return <KeywordsPage c={c} load={load} onBack={back} />;

  if (page === "location") return (
    <Page title="Location" onBack={back}>
      <p className="t-meta text-ink-2">Local news comes from your city and state.</p>
      <div className="flex flex-wrap gap-2">
        {c.cities.map((x) => (
          <button key={x.name} onClick={() => putPrefs({ city: x.name })}
            className={`t-meta px-3.5 py-2 rounded-full ${c.prefs.city === x.name ? "bg-accent text-black" : "bg-raised"}`}>{x.name}</button>
        ))}
      </div>
    </Page>
  );

  if (page === "sources") return <SourcesPage c={c} load={load} onBack={back} notify={notify} />;

  if (page === "look") return (
    <Page title="Look & feel" onBack={back}>
      <Card className="space-y-3">
        <p className="t-meta">Theme</p>
        <Segmented options={[["system", "System"], ["dark", "Dark"], ["light", "Light"]]} value={theme}
          onChange={(t) => { localStorage.setItem("pulse.theme", t); if (t === "system") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = t; setPage("look"); setC({ ...c }); }} />
        <p className="t-meta pt-2">Text size</p>
        <Segmented options={[["s", "Small"], ["m", "Medium"], ["l", "Large"]]} value={localStorage.getItem("pulse.size") || "m"}
          onChange={(v) => { localStorage.setItem("pulse.size", v); document.documentElement.dataset.size = v; setC({ ...c }); }} />
      </Card>
      <Card className="space-y-2">
        <p className="t-meta">Glass effects</p>
        <Segmented options={[["full", "Full"], ["light", "Light"], ["off", "Off"]]} value={glass}
          onChange={(v) => { localStorage.setItem("pulse.glass", v); location.reload(); }} />
        <p className="t-chip text-ink-2">
          {glass === "full" ? "Real liquid-glass refraction. Looks best, works your phone the hardest."
            : glass === "light" ? "A simple frosted blur. Lighter on battery."
            : "Flat surfaces, no blur. Lightest on your phone."}
        </p>
      </Card>
      <Card>
        <Toggle label="Swipe hints" hint="Faint markers on the card showing what each swipe does"
          on={localStorage.getItem("pulse.hints") !== "off"}
          onChange={(on) => { localStorage.setItem("pulse.hints", on ? "on" : "off"); setC({ ...c }); notify(on ? "Swipe hints on" : "Swipe hints hidden"); }} />
      </Card>
    </Page>
  );

  if (page === "account") return <AccountPage c={c} onBack={back} onOnboarding={onOnboarding} notify={notify} />;

  // ---------------------------------------------------------------- home
  const mix = mixOf(c.prefs.tiers);
  const surprise = SURPRISE.reduce((best, s) => (Math.abs(s[2] - c.prefs.discovery) < Math.abs(best[2] - c.prefs.discovery) ? s : best), SURPRISE[1])[0];
  const s = c.stats || {};
  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="px-5 pt-4 pb-10 space-y-4">
        <h1 className="font-serif text-[28px] font-semibold">Controls</h1>

        <button onClick={onInsights} className="w-full text-left">
          <Card>
            <div className="flex items-baseline justify-between">
              <span className="t-meta text-ink-2">This week</span>
              <span className="t-meta text-accent">What Pulse learned ›</span>
            </div>
            <div className="grid grid-cols-4 gap-2 mt-3 text-center">
              {[["read", s.read_week], ["liked", s.liked_week], ["saved", s.saved], ["interests", s.interests]].map(([l, n]) => (
                <div key={l}><div className="font-serif text-[26px] font-semibold leading-none">{n ?? 0}</div><div className="t-chip text-ink-2 mt-1">{l}</div></div>
              ))}
            </div>
          </Card>
        </button>

        <Card className="space-y-3">
          <p className="t-meta">Feed mix</p>
          <Segmented options={Object.entries(MIXES).map(([k, m]) => [k, m.label])} value={mix}
            onChange={(k) => { putPrefs({ tiers: MIXES[k].tiers }); notify(`Feed mix: ${MIXES[k].label}`); }} />
          <p className="t-meta pt-1">Surprise me</p>
          <Segmented options={SURPRISE.map(([k, l]) => [k, l])} value={surprise}
            onChange={(k) => putPrefs({ discovery: parseFloat(k) })} />
          <p className="t-chip text-ink-2">How often Pulse shows stories outside your usual interests.</p>
        </Card>

        <Card className="!p-0 overflow-hidden">
          <Row title="Topics" detail={`${followed} followed${mutedTopics ? ` · ${mutedTopics} muted` : ""}`} onClick={() => setPage("topics")} />
          <Row title="Keywords & people" detail={others.length ? `${others.length} rules` : "Add"} onClick={() => setPage("keywords")} />
          <Row title="Location" detail={`${c.prefs.city}, ${c.prefs.state}`} onClick={() => setPage("location")} />
          <Row title="News sources" detail={`${c.sources.length - offSources} on${offSources ? ` · ${offSources} off` : ""}`} onClick={() => setPage("sources")} />
        </Card>

        <Card className="!p-0 overflow-hidden">
          <Row title="Look & feel" detail={`${theme} · glass ${glass}`} onClick={() => setPage("look")} />
          <Row title="Account" detail={c.username} onClick={() => setPage("account")} />
        </Card>

        {appUrl && (
          <a href={appUrl} target="_blank" rel="noreferrer noopener" className="block">
            <Card><span className="t-summary">Get Pulse for Android</span><span className="t-chip text-ink-2 block mt-1">Same account, offline reading, morning brief. Updates itself.</span></Card>
          </a>
        )}
        <p className="t-chip text-ink-2 text-center">
          {c.last_ingest ? `News updated ${new Date(c.last_ingest.at).toLocaleString([], { hour: "numeric", minute: "2-digit", weekday: "short" })}` : "Waiting for the first news fetch."}
        </p>
      </div>
    </div>
  );
}

function KeywordsPage({ c, load, onBack }) {
  const [kw, setKw] = useState("");
  const [kind, setKind] = useState("keyword");
  const [mode, setMode] = useState("follow");
  const rules = c.rules.filter((r) => r.kind !== "topic");
  const add = async (e) => {
    e.preventDefault();
    if (!kw.trim()) return;
    await api.post("/api/rules", { label: kw.trim(), kind, mode });
    setKw("");
    load();
  };
  const group = (m, title) => {
    const list = rules.filter((r) => r.mode === m);
    if (!list.length) return null;
    return (
      <div>
        <p className="t-meta text-ink-2 mb-2">{title}</p>
        <div className="flex flex-wrap gap-2">
          {list.map((r) => (
            <span key={r.id} className={`t-meta pl-3.5 pr-1 py-1.5 rounded-full bg-raised flex items-center gap-1 ${m === "mute" ? "text-less" : ""}`}>
              {r.label}
              <button onClick={async () => { await api.del(`/api/rules/${r.id}`); load(); }} className="px-2 text-ink-2" aria-label={`Remove ${r.label}`}>×</button>
            </span>
          ))}
        </div>
      </div>
    );
  };
  return (
    <Page title="Keywords & people" onBack={onBack}>
      <p className="t-meta text-ink-2">Follow a name or word to see more of it, mute it to never see it, or pin it to always show at the top.</p>
      <form onSubmit={add} className="space-y-3">
        <input value={kw} onChange={(e) => setKw(e.target.value)} placeholder="e.g. ISRO, Formula 1, a person or company"
          className="w-full bg-raised rounded-full px-4 py-2.5 t-summary outline-none placeholder:text-ink-2" />
        <Segmented options={[["follow", "Follow"], ["always", "Always show"], ["mute", "Mute"]]} value={mode} onChange={setMode} />
        <div className="flex items-center gap-2">
          <Segmented options={[["keyword", "Word"], ["place", "Place"], ["source", "Outlet"]]} value={kind} onChange={setKind} />
          <button className="lg-button lg-button--prominent lg-button--small shrink-0">Add</button>
        </div>
      </form>
      {group("follow", "Following")}
      {group("always", "Always shown")}
      {group("mute", "Muted")}
      {!rules.length && <p className="t-meta text-ink-2">Nothing yet. Tip: long-press a chip on any card to follow or mute it.</p>}
    </Page>
  );
}

function SourcesPage({ c, load, onBack, notify }) {
  const [url, setUrl] = useState("");
  const add = async (e) => {
    e.preventDefault();
    try { await api.post("/api/sources", { url: url.trim() }); setUrl(""); notify("Feed added. It's fetched on the next update."); load(); }
    catch (err) { notify(err.message); }
  };
  const trustLabel = (t) => TRUST.reduce((b, x) => (Math.abs(x[1] - t) < Math.abs(b[1] - t) ? x : b), TRUST[1])[0];
  return (
    <Page title="News sources" onBack={onBack}>
      <p className="t-meta text-ink-2">Switch outlets off, or set how much you trust them. Trusted outlets lead the stories they cover.</p>
      <div className="space-y-2">
        {c.sources.map((s) => (
          <Card key={s.id} className="!py-3">
            <div className="flex items-center gap-3">
              <span className="flex-1 min-w-0">
                <span className="t-meta block truncate">{s.name}</span>
                <span className={`t-chip block ${s.error || s.dead ? "text-less" : "text-ink-2"}`}>
                  {s.error || (s.dead ? "Quiet for 7 days" : `${s.articles_3d} stories in 3 days`)}
                </span>
              </span>
              <span className="lg-switch"><input type="checkbox" checked={s.enabled} aria-label={`${s.name} on`}
                onChange={async (e) => { await api.patch(`/api/sources/${s.id}`, { enabled: e.target.checked }); load(); }} /></span>
            </div>
            {s.enabled && (
              <div className="flex gap-1.5 mt-2">
                {TRUST.map(([l, v]) => (
                  <button key={l} onClick={async () => { await api.patch(`/api/sources/${s.id}`, { trust: v }); load(); }}
                    className={`t-chip px-3 py-1 rounded-full ${trustLabel(s.trust) === l ? "bg-accent text-black" : "bg-raised"}`}>{l} trust</button>
                ))}
              </div>
            )}
          </Card>
        ))}
      </div>
      <form onSubmit={add} className="flex gap-2">
        <input value={url} onChange={(e) => setUrl(e.target.value)} placeholder="Add any RSS feed URL"
          className="flex-1 bg-raised rounded-full px-4 py-2.5 t-meta outline-none placeholder:text-ink-2" />
        <button className="lg-button lg-button--small">Add</button>
      </form>
    </Page>
  );
}

function AccountPage({ c, onBack, onOnboarding, notify }) {
  const [cur, setCur] = useState("");
  const [next, setNext] = useState("");
  const change = async (e) => {
    e.preventDefault();
    try { await api.post("/api/account/password", { current: cur, new: next }); setCur(""); setNext(""); notify("Password changed"); }
    catch (err) { notify(err.message); }
  };
  return (
    <Page title="Account" onBack={onBack}>
      <Card><span className="t-meta text-ink-2">Signed in as</span><div className="t-summary font-semibold">{c.username}</div></Card>
      <Card>
        <form onSubmit={change} className="space-y-2">
          <p className="t-meta">Change password</p>
          <input type="password" value={cur} onChange={(e) => setCur(e.target.value)} placeholder="Current password" autoComplete="current-password"
            className="w-full bg-raised rounded-full px-4 py-2.5 t-meta outline-none placeholder:text-ink-2" />
          <input type="password" value={next} onChange={(e) => setNext(e.target.value)} placeholder="New password (6+ characters)" autoComplete="new-password"
            className="w-full bg-raised rounded-full px-4 py-2.5 t-meta outline-none placeholder:text-ink-2" />
          <button disabled={!cur || next.length < 6} className="lg-button lg-button--small lg-button--prominent disabled:opacity-40">Save</button>
        </form>
      </Card>
      <div className="flex flex-wrap gap-2">
        <button onClick={onOnboarding} className="lg-button lg-button--small">Redo setup</button>
        <button onClick={async () => { await api.post("/api/logout"); location.reload(); }} className="lg-button lg-button--small">Log out</button>
      </div>
    </Page>
  );
}
