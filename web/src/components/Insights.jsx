import { useEffect, useState } from "react";
import { api } from "../api.js";

/** What Pulse thinks you like, as a ranked list you can correct. */
export default function Insights({ onBack, notify }) {
  const [d, setD] = useState(null);
  const load = () => api.get("/api/insights").then(setD);
  useEffect(() => { load(); }, []);

  const forget = async (id) => { await api.del(`/api/insights/${id}`); load(); };
  const togglePause = async (t) => {
    const paused = d.paused_topics.includes(t) ? d.paused_topics.filter((x) => x !== t) : [...d.paused_topics, t];
    await api.put("/api/prefs", { paused_topics: paused });
    setD({ ...d, paused_topics: paused });
  };
  const reset = async (t) => {
    const r = await api.post("/api/insights/reset-topic", { topic: t });
    notify(`Forgot ${r.removed} learned interest(s) for ${t}`);
    load();
  };
  const rebuild = async () => {
    const r = await api.post("/api/insights/rebuild");
    notify(`Rebuilt from ${r.replayed} interactions`);
    load();
  };

  if (!d) return <div className="h-full" />;
  const pos = d.clusters.filter((c) => c.polarity > 0);
  const neg = d.clusters.filter((c) => c.polarity < 0);
  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="px-5 pt-4 pb-10">
        <button onClick={onBack} className="t-meta text-ink-2">‹ Controls</button>
        <h1 className="font-serif text-[28px] font-semibold mt-2">Insights</h1>
        <p className="t-meta text-ink-2 mt-1">Learned from {d.interactions} interactions. Interests fade over about a month unless you keep engaging.</p>

        <h2 className="t-meta text-ink mt-6 mb-2">You like</h2>
        {!pos.length && <p className="t-meta text-ink-2">Nothing learned yet. Swipe right on stories you want more of.</p>}
        {pos.map((c) => (
          <div key={c.id} className="py-3 border-b border-divider flex items-center gap-3">
            <div className="flex-1 min-w-0">
              <div className="t-meta truncate">{c.label}</div>
              <div className="h-1.5 rounded-full bg-raised mt-2 overflow-hidden"><div className="h-full bg-accent" style={{ width: `${c.strength * 100}%` }} /></div>
            </div>
            <span className="t-chip text-ink-2 w-14 text-right">{c.signals} signals</span>
            <button onClick={() => forget(c.id)} className="t-chip px-2.5 py-1 rounded-full bg-raised">Forget</button>
          </div>
        ))}

        {neg.length > 0 && <h2 className="t-meta text-ink mt-6 mb-2">You'd rather see less of</h2>}
        {neg.map((c) => (
          <div key={c.id} className="py-3 border-b border-divider flex items-center gap-3">
            <div className="flex-1 min-w-0">
              <div className="t-meta truncate">{c.label}</div>
              <div className="h-1.5 rounded-full bg-raised mt-2 overflow-hidden"><div className="h-full bg-less" style={{ width: `${c.strength * 100}%` }} /></div>
            </div>
            <button onClick={() => forget(c.id)} className="t-chip px-2.5 py-1 rounded-full bg-raised">Forget</button>
          </div>
        ))}

        <h2 className="t-meta text-ink mt-8 mb-1">Per-topic learning</h2>
        <p className="t-chip text-ink-2 mb-3">Pause learning if Pulse over-learned something you liked once. Reset forgets it.</p>
        <div className="space-y-1">
          {d.topics.map((t) => (
            <div key={t} className="flex items-center gap-2 py-1">
              <span className="t-meta flex-1">{t}</span>
              <button onClick={() => togglePause(t)} className={`t-chip px-2.5 py-1 rounded-full ${d.paused_topics.includes(t) ? "bg-accent text-black" : "bg-raised"}`}>
                {d.paused_topics.includes(t) ? "Paused" : "Pause"}
              </button>
              <button onClick={() => reset(t)} className="t-chip px-2.5 py-1 rounded-full bg-raised">Reset</button>
            </div>
          ))}
        </div>
        <button onClick={rebuild} className="mt-8 t-meta px-4 py-2 rounded-full bg-raised">Rebuild profile from history</button>
      </div>
    </div>
  );
}
