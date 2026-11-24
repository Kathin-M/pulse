import { useEffect, useState } from "react";
import { api } from "../api.js";
import { StoryRow } from "./Seen.jsx";

export default function Liked({ onOpen }) {
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState("all");
  const [cards, setCards] = useState(null);

  useEffect(() => {
    const t = setTimeout(() => {
      api.get(`/api/liked?q=${encodeURIComponent(q)}`).then((r) => setCards(r.cards)).catch(() => setCards([]));
    }, 250);
    return () => clearTimeout(t);
  }, [q]);

  const shown = (cards || []).filter((c) => filter === "all" || (filter === "saved" ? c.saved : c.liked));

  return (
    <div className="h-full overflow-y-auto no-scrollbar safe-top">
      <div className="sticky top-0 bg-bg z-10 px-5 pt-4 pb-3 space-y-3">
        <h1 className="font-serif text-[28px] font-semibold">Liked</h1>
        <input
          value={q} onChange={(e) => setQ(e.target.value)} placeholder="Search your library"
          className="w-full bg-raised rounded-full px-4 py-2.5 t-summary outline-none placeholder:text-ink-2"
        />
        <div className="flex gap-2">
          {[["all", "All"], ["liked", "Liked"], ["saved", "Saved for later"]].map(([k, l]) => (
            <button key={k} onClick={() => setFilter(k)} className={`t-chip px-3 py-1.5 rounded-full ${filter === k ? "bg-accent text-black" : "bg-raised"}`}>{l}</button>
          ))}
        </div>
      </div>
      <div className="px-5 pb-8">
        {cards && !shown.length && <p className="t-summary text-ink-2 pt-6">Swipe right on a story to keep it here. Double-tap saves without liking.</p>}
        {shown.map((c) => (
          <StoryRow key={c.id} c={c} onOpen={onOpen}
            right={c.saved ? <span className="self-center t-chip px-2 py-1 rounded-full bg-raised">Saved</span> : null} />
        ))}
      </div>
    </div>
  );
}
