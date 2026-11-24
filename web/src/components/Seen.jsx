import { useEffect, useMemo, useState } from "react";
import { motion } from "motion/react";
import { api, interact, ago, dayBucket } from "../api.js";

export function StoryRow({ c, onOpen, onLike, right }) {
  return (
    <motion.div
      drag={onLike ? "x" : false}
      dragConstraints={{ left: 0, right: 0 }}
      dragElastic={{ left: 0, right: 0.5 }}
      onDragEnd={(_, i) => { if (onLike && i.offset.x > 90) onLike(c); }}
      className="flex gap-3 py-3 border-b border-divider bg-bg"
    >
      <button onClick={() => onOpen(c.id)} className="flex-1 min-w-0 text-left">
        <div className="t-summary clamp-2 font-medium">{c.title}</div>
        <div className="t-meta text-ink-2 mt-1">
          {c.publisher}{c.source_count > 1 ? ` +${c.source_count - 1}` : ""} · {ago(c.when || c.first_seen)}
          {c.topics?.[0] ? ` · ${c.topics[0]}` : ""}
        </div>
      </button>
      {c.image && <img src={c.image} referrerPolicy="no-referrer" alt="" className="w-16 h-16 rounded-xl object-cover bg-raised shrink-0" />}
      {right}
    </motion.div>
  );
}

export default function Seen({ onOpen, notify }) {
  const [q, setQ] = useState("");
  const [topic, setTopic] = useState("");
  const [source, setSource] = useState("");
  const [data, setData] = useState(null);

  useEffect(() => {
    const t = setTimeout(() => {
      const p = new URLSearchParams({ q, topic, source });
      api.get(`/api/seen?${p}`).then(setData).catch(() => setData({ cards: [], topics: [], sources: [] }));
    }, 250);
    return () => clearTimeout(t);
  }, [q, topic, source]);

  const groups = useMemo(() => {
    const out = [];
    for (const c of data?.cards || []) {
      const b = dayBucket(c.when);
      if (!out.length || out[out.length - 1][0] !== b) out.push([b, []]);
      out[out.length - 1][1].push(c);
    }
    return out;
  }, [data]);

  const like = async (c) => {
    await interact(c.id, c.status === "liked" ? "unlike" : "like");
    setData((d) => ({ ...d, cards: d.cards.map((x) => (x.id === c.id ? { ...x, status: x.status === "liked" ? "seen" : "liked" } : x)) }));
    notify(c.status === "liked" ? "Removed from Liked" : "Liked");
  };

  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="sticky top-0 bg-bg z-10 px-5 pt-4 pb-3 space-y-3">
        <h1 className="font-serif text-[28px] font-semibold">Seen</h1>
        <input
          value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search everything you've swiped past"
          className="w-full bg-raised rounded-full px-4 py-2.5 t-summary outline-none placeholder:text-ink-2"
        />
        <div className="flex gap-2">
          <select value={topic} onChange={(e) => setTopic(e.target.value)} className="bg-raised rounded-full px-3 py-1.5 t-chip outline-none">
            <option value="">All topics</option>
            {data?.topics.map((t) => <option key={t}>{t}</option>)}
          </select>
          <select value={source} onChange={(e) => setSource(e.target.value)} className="bg-raised rounded-full px-3 py-1.5 t-chip outline-none max-w-[50%]">
            <option value="">All sources</option>
            {data?.sources.map((t) => <option key={t}>{t}</option>)}
          </select>
        </div>
      </div>
      <div className="px-5 pb-8">
        {data && !data.cards.length && <p className="t-summary text-ink-2 pt-6">Nothing here yet. Stories you swipe past land here.</p>}
        {groups.map(([label, cards]) => (
          <section key={label}>
            <h2 className="t-meta text-ink-2 pt-5 pb-1">{label}</h2>
            {cards.map((c) => (
              <StoryRow key={c.id} c={c} onOpen={onOpen} onLike={like}
                right={
                  <button onClick={() => like(c)} className={`self-center t-meta px-2 ${c.status === "liked" ? "text-more" : "text-ink-2"}`} aria-label="Like">
                    {c.status === "liked" ? "♥" : "♡"}
                  </button>
                }
              />
            ))}
          </section>
        ))}
      </div>
    </div>
  );
}
