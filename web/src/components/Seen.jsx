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
        <div className="t-summary font-medium">{c.title}</div>
        <div className="t-meta text-ink-2 mt-1">
          {c.publisher}{c.source_count > 1 ? ` +${c.source_count - 1}` : ""} · {ago(c.when || c.first_seen)}
          {c.topics?.[0] ? ` · ${c.topics[0]}` : ""}
          {c.status && STATUS_LABEL[c.status] ? <span className="ml-1.5 t-chip px-1.5 py-0.5 rounded-full bg-raised">{STATUS_LABEL[c.status]}</span> : null}
        </div>
      </button>
      {c.image && <img src={c.image} referrerPolicy="no-referrer" alt="" className="w-16 h-16 rounded-xl object-cover bg-raised shrink-0" />}
      {right}
    </motion.div>
  );
}

const STATUS_LABEL = { liked: "Liked", fine: "Fine", seen: "Seen", hidden: "Less" };
const WHEN = [["0", "All time"], ["1", "Today"], ["2", "2 days"], ["7", "This week"], ["30", "This month"]];
const REACTIONS = [["", "All"], ["liked", "Liked"], ["fine", "Fine"], ["seen", "Seen"], ["less", "Less"]];

function Chip({ active, onClick, children }) {
  return (
    <button onClick={onClick} className={`t-chip px-3 py-1.5 rounded-full whitespace-nowrap ${active ? "bg-accent text-black" : "bg-raised"}`}>{children}</button>
  );
}

/** Everything you've swiped past: search, then narrow by when, how you reacted, topic, outlet or local. */
export default function Seen({ onOpen, notify }) {
  const [q, setQ] = useState("");
  const [days, setDays] = useState("0");
  const [status, setStatus] = useState("");
  const [topic, setTopic] = useState("");
  const [source, setSource] = useState("");
  const [local, setLocal] = useState(false);
  const [sort, setSort] = useState("new");
  const [data, setData] = useState(null);

  useEffect(() => {
    const t = setTimeout(() => {
      const p = new URLSearchParams({ q, topic, source, status, days, local: local ? "true" : "false", sort });
      api.get(`/api/seen?${p}`).then(setData).catch(() => setData({ cards: [], topics: [], sources: [], counts: {} }));
    }, 250);
    return () => clearTimeout(t);
  }, [q, topic, source, status, days, local, sort]);

  const groups = useMemo(() => {
    const out = [];
    for (const c of data?.cards || []) {
      const b = dayBucket(c.when);
      if (!out.length || out[out.length - 1][0] !== b) out.push([b, []]);
      out[out.length - 1][1].push(c);
    }
    return out;
  }, [data]);

  const filtered = q || days !== "0" || status || topic || source || local;
  const clear = () => { setQ(""); setDays("0"); setStatus(""); setTopic(""); setSource(""); setLocal(false); setSort("new"); };

  const like = async (c) => {
    await interact(c.id, c.status === "liked" ? "unlike" : "like");
    setData((d) => ({ ...d, cards: d.cards.map((x) => (x.id === c.id ? { ...x, status: x.status === "liked" ? "seen" : "liked" } : x)) }));
    notify(c.status === "liked" ? "Removed from Liked" : "Liked");
  };

  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="sticky top-0 bg-bg/95 backdrop-blur z-10 px-5 pt-4 pb-3 space-y-3">
        <div className="flex items-baseline">
          <h1 className="font-serif text-[28px] font-semibold">Seen</h1>
          <span className="ml-3 t-chip text-ink-2">{data ? `${data.cards.length} stories` : ""}</span>
          {filtered && <button onClick={clear} className="ml-auto t-meta text-accent">Clear filters</button>}
        </div>
        <input
          value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search titles, summaries and outlets"
          className="w-full bg-raised rounded-full px-4 py-2.5 t-summary outline-none placeholder:text-ink-2"
        />
        <div className="flex gap-2 overflow-x-auto no-scrollbar">
          {WHEN.map(([k, l]) => <Chip key={k} active={days === k} onClick={() => setDays(k)}>{l}</Chip>)}
        </div>
        <div className="flex gap-2 overflow-x-auto no-scrollbar">
          {REACTIONS.map(([k, l]) => (
            <Chip key={k} active={status === k} onClick={() => setStatus(k)}>
              {l}{k && data?.counts && !status ? ` ${data.counts[k] ?? 0}` : ""}
            </Chip>
          ))}
          <Chip active={local} onClick={() => setLocal((v) => !v)}>Local only</Chip>
        </div>
        <div className="flex gap-2">
          <select value={topic} onChange={(e) => setTopic(e.target.value)} className="bg-raised rounded-full px-3 py-1.5 t-chip outline-none">
            <option value="">All topics</option>
            {data?.topics.map((t) => <option key={t}>{t}</option>)}
          </select>
          <select value={source} onChange={(e) => setSource(e.target.value)} className="bg-raised rounded-full px-3 py-1.5 t-chip outline-none max-w-[45%]">
            <option value="">All outlets</option>
            {data?.sources.map((t) => <option key={t}>{t}</option>)}
          </select>
          <select value={sort} onChange={(e) => setSort(e.target.value)} className="ml-auto bg-raised rounded-full px-3 py-1.5 t-chip outline-none">
            <option value="new">Newest</option>
            <option value="old">Oldest</option>
          </select>
        </div>
      </div>
      <div className="px-5 pb-8">
        {data && !data.cards.length && (
          <p className="t-summary text-ink-2 pt-6">{filtered ? "Nothing matches these filters." : "Nothing here yet. Stories you swipe past land here."}</p>
        )}
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
