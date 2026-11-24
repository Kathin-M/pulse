import { useEffect, useState } from "react";
import { api } from "../api.js";

const TIER_LABELS = { city: "My city", state: "My state", india: "India", world: "World" };
const NEXT_MODE = { neutral: "follow", follow: "mute", mute: "neutral", always: "neutral" };

function Section({ title, children, hint }) {
  return (
    <section className="py-5 border-b border-divider">
      <h2 className="t-meta text-ink mb-1">{title}</h2>
      {hint && <p className="t-chip text-ink-2 mb-3">{hint}</p>}
      {children}
    </section>
  );
}

function Slider({ label, value, onChange, right }) {
  const [v, setV] = useState(value);
  useEffect(() => setV(value), [value]);
  return (
    <label className="flex items-center gap-3 py-1.5">
      <span className="t-meta w-28 shrink-0 truncate">{label}</span>
      <input type="range" min="0" max="1" step="0.05" value={v} className="flex-1"
        onChange={(e) => setV(parseFloat(e.target.value))}
        onPointerUp={() => onChange(v)} onKeyUp={() => onChange(v)} onTouchEnd={() => onChange(v)} />
      <span className="t-chip text-ink-2 w-10 text-right">{right ?? Math.round(v * 100)}</span>
    </label>
  );
}

export default function Controls({ onInsights, onOnboarding, notify }) {
  const [c, setC] = useState(null);
  const [kw, setKw] = useState("");
  const [kwKind, setKwKind] = useState("keyword");
  const [kwMode, setKwMode] = useState("follow");
  const [feedUrl, setFeedUrl] = useState("");
  const [showSources, setShowSources] = useState(false);
  const [theme, setTheme] = useState(localStorage.getItem("pulse.theme") || "system");
  const [size, setSize] = useState(localStorage.getItem("pulse.size") || "m");

  const load = () => api.get("/api/controls").then(setC);
  useEffect(() => { load(); api.get("/api/app/latest").then((r) => setAppUrl(r.releases)).catch(() => {}); }, []);
  const [appUrl, setAppUrl] = useState(null);
  const [pwOpen, setPwOpen] = useState(false);
  const [pwCur, setPwCur] = useState("");
  const [pwNew, setPwNew] = useState("");
  const changePw = async (e) => {
    e.preventDefault();
    try {
      await api.post("/api/account/password", { current: pwCur, new: pwNew });
      setPwOpen(false); setPwCur(""); setPwNew("");
      notify("Password changed");
    } catch (err) { notify(err.message); }
  };

  const putPrefs = async (patch) => {
    const prefs = await api.put("/api/prefs", patch);
    setC((x) => ({ ...x, prefs }));
  };
  const topicMode = (t) => c.rules.find((r) => r.kind === "topic" && r.label === t)?.mode || "neutral";
  const cycleTopic = async (t) => {
    const mode = NEXT_MODE[topicMode(t)];
    await api.post("/api/rules", { label: t, kind: "topic", mode });
    load();
  };
  const addRule = async (e) => {
    e.preventDefault();
    if (!kw.trim()) return;
    await api.post("/api/rules", { label: kw.trim(), kind: kwKind, mode: kwMode });
    setKw("");
    load();
  };
  const addFeed = async (e) => {
    e.preventDefault();
    try {
      await api.post("/api/sources", { url: feedUrl.trim() });
      setFeedUrl("");
      notify("Feed added. It's polled on the next update.");
      load();
    } catch (err) {
      notify(err.message);
    }
  };
  const applyTheme = (t) => {
    setTheme(t);
    localStorage.setItem("pulse.theme", t);
    if (t === "system") delete document.documentElement.dataset.theme;
    else document.documentElement.dataset.theme = t;
  };
  const applySize = (s) => {
    setSize(s);
    localStorage.setItem("pulse.size", s);
    document.documentElement.dataset.size = s;
  };

  if (!c) return <div className="h-full" />;
  const nonTopicRules = c.rules.filter((r) => r.kind !== "topic");
  const deadCount = c.sources.filter((s) => s.dead || s.error).length;

  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="px-5 pt-4 pb-10">
        <div className="flex items-center">
          <h1 className="font-serif text-[28px] font-semibold">Controls</h1>
          <button onClick={onInsights} className="ml-auto t-meta px-3 py-1.5 rounded-full bg-raised">Insights →</button>
        </div>

        <Section title="Location" hint="Each tier has its own slider. Local stories always get a minimum share of the feed.">
          <div className="flex items-center gap-3 pb-2">
            <span className="t-meta w-28">City</span>
            <select value={c.prefs.city} onChange={(e) => putPrefs({ city: e.target.value })} className="bg-raised rounded-full px-3 py-1.5 t-meta outline-none">
              {c.cities.map((x) => <option key={x.name} value={x.name}>{x.name}, {x.state}</option>)}
            </select>
          </div>
          {Object.keys(TIER_LABELS).map((k) => (
            <Slider key={k} label={k === "city" ? c.prefs.city : k === "state" ? c.prefs.state : TIER_LABELS[k]}
              value={c.prefs.tiers[k]} onChange={(v) => putPrefs({ tiers: { [k]: v } })} />
          ))}
        </Section>

        <Section title="Topics" hint="Tap to cycle: neutral → follow → mute.">
          <div className="flex flex-wrap gap-2">
            {c.topics.map((t) => {
              const m = topicMode(t);
              return (
                <button key={t} onClick={() => cycleTopic(t)}
                  className={`t-meta px-3 py-1.5 rounded-full ${m === "follow" ? "bg-accent text-black" : m === "mute" ? "bg-raised text-less line-through" : m === "always" ? "bg-accent text-black ring-2 ring-ink" : "bg-raised"}`}>
                  {m === "follow" ? "✓ " : ""}{t}
                </button>
              );
            })}
          </div>
        </Section>

        <Section title="Keywords, people, places, sources" hint="Follow boosts it, mute hides it completely, always show pins it to the top.">
          <form onSubmit={addRule} className="flex flex-wrap gap-2">
            <input value={kw} onChange={(e) => setKw(e.target.value)} placeholder="e.g. Formula 1, ISRO, celebrity"
              className="flex-1 min-w-40 bg-raised rounded-full px-4 py-2 t-meta outline-none placeholder:text-ink-2" />
            <select value={kwKind} onChange={(e) => setKwKind(e.target.value)} className="bg-raised rounded-full px-3 py-2 t-meta outline-none">
              <option value="keyword">Keyword</option><option value="place">Place</option><option value="source">Outlet</option>
            </select>
            <select value={kwMode} onChange={(e) => setKwMode(e.target.value)} className="bg-raised rounded-full px-3 py-2 t-meta outline-none">
              <option value="follow">Follow</option><option value="mute">Mute</option><option value="always">Always show</option>
            </select>
            <button className="px-4 py-2 rounded-full bg-accent text-black t-meta font-semibold">Add</button>
          </form>
          <div className="flex flex-wrap gap-2 mt-3">
            {nonTopicRules.map((r) => (
              <span key={r.id} className={`t-chip pl-3 pr-1 py-1 rounded-full bg-raised flex items-center gap-1 ${r.mode === "mute" ? "text-less" : ""}`}>
                {r.mode === "mute" ? "Muted" : r.mode === "always" ? "Always" : "Following"}: {r.label}
                <button onClick={async () => { await api.del(`/api/rules/${r.id}`); load(); }} className="px-2 text-ink-2" aria-label="Remove">×</button>
              </span>
            ))}
          </div>
        </Section>

        <Section title="Discovery" hint="How much of the feed explores outside your known interests.">
          <Slider label="Exploration" value={c.prefs.discovery * 2} right={`${Math.round(c.prefs.discovery * 100)}%`}
            onChange={(v) => putPrefs({ discovery: v / 2 })} />
        </Section>

        <Section title={`Sources (${c.sources.length})`} hint={deadCount ? `${deadCount} feed(s) need attention` : "Trust decides which outlet leads a story."}>
          <button onClick={() => setShowSources((s) => !s)} className="t-meta text-ink-2 mb-2">{showSources ? "Hide" : "Show"} sources</button>
          {showSources && (
            <>
              {c.sources.map((s) => (
                <div key={s.id} className="py-2 border-b border-divider/60">
                  <div className="flex items-center gap-2">
                    <input type="checkbox" checked={s.enabled} onChange={async (e) => { await api.patch(`/api/sources/${s.id}`, { enabled: e.target.checked }); load(); }} />
                    <span className="t-meta truncate flex-1">{s.name}</span>
                    {(s.dead || s.error) && <span className="t-chip text-less">{s.error || "no new items in 7 days"}</span>}
                    <span className="t-chip text-ink-2">{s.articles_3d}</span>
                    {["custom", "outlet", "google"].includes(s.kind) && (
                      <button onClick={async () => { await api.del(`/api/sources/${s.id}`); load(); }} className="t-chip text-ink-2 px-1">×</button>
                    )}
                  </div>
                  {["outlet", "custom", "city", "google"].includes(s.kind) && (
                    <Slider label="Trust" value={s.trust} onChange={async (v) => { await api.patch(`/api/sources/${s.id}`, { trust: v }); }} />
                  )}
                </div>
              ))}
              <form onSubmit={addFeed} className="flex gap-2 mt-3">
                <input value={feedUrl} onChange={(e) => setFeedUrl(e.target.value)} placeholder="Add any RSS feed URL"
                  className="flex-1 bg-raised rounded-full px-4 py-2 t-meta outline-none placeholder:text-ink-2" />
                <button className="px-4 py-2 rounded-full bg-raised t-meta">Add</button>
              </form>
            </>
          )}
        </Section>

        <Section title="Appearance">
          <div className="flex gap-2 mb-3">
            {["system", "dark", "light"].map((t) => (
              <button key={t} onClick={() => applyTheme(t)} className={`t-meta px-3 py-1.5 rounded-full capitalize ${theme === t ? "bg-accent text-black" : "bg-raised"}`}>{t}</button>
            ))}
          </div>
          <div className="flex gap-2">
            {[["s", "Small text"], ["m", "Medium"], ["l", "Large"]].map(([k, l]) => (
              <button key={k} onClick={() => applySize(k)} className={`t-meta px-3 py-1.5 rounded-full ${size === k ? "bg-accent text-black" : "bg-raised"}`}>{l}</button>
            ))}
          </div>
        </Section>

        <Section title="Account">
          <p className="t-meta">Signed in as <span className="font-semibold">{c.username}</span></p>
          <p className="t-chip text-ink-2 mt-1">
            {c.last_ingest ? `News last fetched ${new Date(c.last_ingest.at).toLocaleString()} · ${c.last_ingest.new} new articles` : "Waiting for the first news fetch."}
          </p>
          {pwOpen ? (
            <form onSubmit={changePw} className="space-y-2 mt-3">
              <input type="password" value={pwCur} onChange={(e) => setPwCur(e.target.value)} placeholder="Current password" autoComplete="current-password"
                className="w-full bg-raised rounded-full px-4 py-2 t-meta outline-none placeholder:text-ink-2" />
              <input type="password" value={pwNew} onChange={(e) => setPwNew(e.target.value)} placeholder="New password (6+ characters)" autoComplete="new-password"
                className="w-full bg-raised rounded-full px-4 py-2 t-meta outline-none placeholder:text-ink-2" />
              <div className="flex gap-2">
                <button className="lg-button lg-button--small lg-button--prominent">Save password</button>
                <button type="button" onClick={() => setPwOpen(false)} className="lg-button lg-button--small">Cancel</button>
              </div>
            </form>
          ) : null}
          <div className="flex flex-wrap gap-2 mt-3">
            {!pwOpen && <button onClick={() => setPwOpen(true)} className="lg-button lg-button--small">Change password</button>}
            <button onClick={onOnboarding} className="lg-button lg-button--small">Redo setup</button>
            <button onClick={async () => { await api.post("/api/logout"); location.reload(); }} className="lg-button lg-button--small">Log out</button>
          </div>
        </Section>

        {appUrl && (
          <Section title="Android app" hint="The native app has the same account, offline reading and a morning brief. It updates itself.">
            <a href={appUrl} target="_blank" rel="noreferrer noopener" className="lg-button lg-button--small lg-button--prominent inline-flex">Download Pulse for Android</a>
          </Section>
        )}
      </div>
    </div>
  );
}
