import { useEffect, useState } from "react";
import { motion } from "motion/react";
import { api, interact, ago, shareStory } from "../api.js";

function SourceRow({ s, onRead }) {
  return (
    <div className="py-3 border-b border-divider flex gap-3">
      <div className="flex-1 min-w-0">
        <div className="t-meta text-ink font-semibold">{s.publisher}</div>
        <a href={s.url} target="_blank" rel="noreferrer noopener" className="t-meta text-ink-2 clamp-2 block">{s.title}</a>
        {s.note && <div className="t-chip text-ink-2 mt-1">{s.note}</div>}
      </div>
      <div className="flex flex-col items-end gap-2 shrink-0">
        <span className="t-chip text-ink-2">{ago(s.time)}</span>
        {s.has_body && <button onClick={() => onRead(s.article_id)} className="t-chip px-2 py-1 rounded-full bg-raised">Read</button>}
      </div>
    </div>
  );
}

export default function StoryDetail({ storyId, onClose, onRead, notify }) {
  const [d, setD] = useState(null);
  const [err, setErr] = useState(null);
  const [showMore, setShowMore] = useState(false);
  const [tab, setTab] = useState("sources");
  const [followed, setFollowed] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    api.get(`/api/story/${storyId}`).then((r) => { setD(r); setFollowed(r.followed); setSaved(r.saved); }).catch((e) => setErr(e.message));
  }, [storyId]);

  const toggleFollow = async () => {
    const next = !followed;
    setFollowed(next);
    await interact(storyId, next ? "follow" : "unfollow");
    notify(next ? "You'll get an update card when something new happens" : "Stopped following");
  };
  const toggleSave = async () => {
    const next = !saved;
    setSaved(next);
    await interact(storyId, next ? "save" : "unsave");
    notify(next ? "Saved for later" : "Removed from saved");
  };
  const split = async () => {
    await api.post(`/api/story/${storyId}/split`);
    notify("Split into separate stories");
    onClose();
  };

  const card = d?.card;
  return (
    <motion.div
      className="fixed inset-0 z-20 bg-bg flex flex-col safe-top"
      initial={{ x: "100%" }} animate={{ x: 0 }} exit={{ x: "100%" }}
      transition={{ type: "spring", stiffness: 380, damping: 40 }}
      drag="x" dragConstraints={{ left: 0, right: 0 }} dragElastic={{ left: 0, right: 0.6 }}
      onDragEnd={(_, i) => { if (i.offset.x > 120 || i.velocity.x > 600) onClose(); }}
    >
      <div className="flex items-center px-5 py-3 gap-3">
        <button onClick={onClose} className="t-meta text-ink-2">‹ Back</button>
        <div className="ml-auto flex gap-2">
          {d?.card && (
            <button
              onClick={async () => { if ((await shareStory(d.card)) === "copied") notify("Link copied"); }}
              className="t-meta px-3 py-1.5 rounded-full bg-raised"
            >Share</button>
          )}
          <button onClick={toggleSave} className={`t-meta px-3 py-1.5 rounded-full ${saved ? "bg-accent text-black" : "bg-raised"}`}>{saved ? "Saved" : "Save"}</button>
          <button onClick={toggleFollow} className={`t-meta px-3 py-1.5 rounded-full ${followed ? "bg-accent text-black" : "bg-raised"}`}>
            {followed ? "Following story" : "Follow story"}
          </button>
        </div>
      </div>

      <div className="flex-1 min-h-0 overflow-y-auto no-scrollbar px-5 pb-28 select-text">
        {err && <p className="t-summary text-ink-2 pt-6">{err}</p>}
        {!d && !err && <div className="space-y-3 pt-4">{[1, 2, 3].map((i) => <div key={i} className="h-6 rounded bg-surface animate-pulse" />)}</div>}
        {card && (
          <>
            {card.image && (
              <img src={card.image} referrerPolicy="no-referrer" alt="" className="w-full aspect-[16/9] object-cover rounded-[16px] mb-4 bg-raised" />
            )}
            <h1 className="t-headline-xl">{card.title}</h1>
            <div className="t-meta text-ink-2 mt-2">
              {card.source_count} {card.source_count === 1 ? "outlet" : "outlets"} · first reported {ago(card.first_seen)}
            </div>
            {card.summary && <p className="t-summary mt-4 text-ink/90">{card.summary}</p>}

            {d.timeline.length > 1 && (
              <section className="mt-7">
                <h3 className="t-meta text-ink mb-3">Timeline</h3>
                <ol className="relative border-l border-divider ml-1.5 space-y-4">
                  {d.timeline.map((t, i) => (
                    <li key={i} className="pl-4 relative">
                      <span className={`absolute -left-[5px] top-1.5 w-2.5 h-2.5 rounded-full ${i === d.timeline.length - 1 ? "bg-accent" : "bg-ink-2"}`} />
                      <div className={`t-meta ${i === d.timeline.length - 1 ? "text-ink font-semibold" : "text-ink-2"}`}>
                        {ago(t.time)} · {t.title}
                      </div>
                    </li>
                  ))}
                </ol>
              </section>
            )}

            <div className="flex gap-5 mt-7 border-b border-divider">
              <button onClick={() => setTab("sources")} className={`t-meta pb-2 ${tab === "sources" ? "text-ink border-b-2 border-accent" : "text-ink-2"}`}>Sources</button>
              {d.explainers.length > 0 && (
                <button onClick={() => setTab("explainers")} className={`t-meta pb-2 ${tab === "explainers" ? "text-ink border-b-2 border-accent" : "text-ink-2"}`}>
                  Explainers & live ({d.explainers.length})
                </button>
              )}
            </div>

            {tab === "sources" && (
              <section>
                {d.sources.map((s) => <SourceRow key={s.article_id} s={s} onRead={onRead} />)}
                {d.more_sources.length > 0 && !showMore && (
                  <button onClick={() => setShowMore(true)} className="t-meta text-ink-2 py-3">+{d.more_sources.length} more sources</button>
                )}
                {showMore && d.more_sources.map((s) => <SourceRow key={s.article_id} s={s} onRead={onRead} />)}
              </section>
            )}
            {tab === "explainers" && (
              <section>
                {d.explainers.map((s) => (
                  <SourceRow key={s.article_id} s={{ ...s, note: s.kind === "live" ? "Live blog" : "Opinion / explainer", has_body: true }} onRead={onRead} />
                ))}
              </section>
            )}

            {d.thread && <div className="t-meta text-ink-2 mt-6">Part of the thread “{d.thread.title}” · {d.thread.count} stories</div>}
            {d.article_count > 1 && (
              <button onClick={split} className="t-meta text-ink-2 underline underline-offset-4 mt-6 block">These are different stories</button>
            )}
          </>
        )}
      </div>

      {card && (
        <div className="absolute bottom-0 inset-x-0 p-4 safe-bottom bg-gradient-to-t from-bg via-bg to-transparent">
          <button onClick={() => onRead(card.article_id)} className="w-full py-3.5 rounded-full bg-accent text-black t-summary font-semibold">
            Read full article (reader view)
          </button>
        </div>
      )}
    </motion.div>
  );
}
