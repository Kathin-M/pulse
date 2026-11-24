import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { api, interact, haptic } from "../api.js";
import Stack from "./Stack.jsx";

const MODES = [
  ["foryou", "For you"],
  ["local", "Local"],
  ["following", "Following"],
];

function timeLabel(d) {
  return d.toLocaleTimeString([], { hour: "numeric", minute: "2-digit" });
}

function CaughtUp({ since, mode, onSeen, onRefresh, ingest, empty }) {
  const [note, setNote] = useState(null);
  useEffect(() => {
    api.get(`/api/session-summary?since=${encodeURIComponent(since.toISOString())}`).then((r) => setNote(r.note)).catch(() => {});
  }, [since]);
  return (
    <div className="h-full w-full rounded-[20px] bg-surface p-7 flex flex-col justify-center gap-5">
      <div className="h-[2px] w-14 bg-accent" />
      <h2 className="t-headline-xl">{empty ? "Nothing here yet" : "You're all caught up"}</h2>
      <p className="t-summary text-ink-2">
        {empty
          ? mode === "following"
            ? "Follow topics, keywords or stories and they'll show up here."
            : ingest
              ? "No new stories since the last update. Fresh news is fetched every hour."
              : "Pulse is fetching your first stories. The first batch lands within the hour."
          : `You've seen everything important since ${timeLabel(since)}.`}
      </p>
      {note && <p className="t-meta text-ink">{note}</p>}
      <div className="flex gap-3 pt-2">
        <button onClick={onSeen} className="px-4 py-2.5 rounded-full bg-raised t-meta">Open Seen</button>
        <button onClick={onRefresh} className="px-4 py-2.5 rounded-full bg-raised t-meta">Check again</button>
      </div>
      {ingest?.at && <p className="t-chip text-ink-2">Last update {new Date(ingest.at).toLocaleString([], { hour: "numeric", minute: "2-digit", weekday: "short" })}</p>}
    </div>
  );
}

function ChipMenu({ chip, onClose, notify }) {
  if (!chip) return null;
  const act = async (mode) => {
    await api.post("/api/rules", { label: chip.label, kind: chip.kind, mode });
    notify(mode === "mute" ? `Muted ${chip.label}` : mode === "always" ? `Always showing ${chip.label}` : `Following ${chip.label}`);
    onClose();
  };
  return (
    <motion.div className="fixed inset-0 z-40 bg-black/50 flex items-end" initial={{ opacity: 0 }} animate={{ opacity: 1 }} exit={{ opacity: 0 }} onClick={onClose}>
      <motion.div
        initial={{ y: 200 }} animate={{ y: 0 }} exit={{ y: 200 }}
        className="w-full bg-raised rounded-t-[20px] p-5 pb-8 safe-bottom space-y-2"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="t-meta text-ink-2 pb-2">{chip.label}</div>
        <button onClick={() => act("follow")} className="w-full text-left py-3 t-summary">Follow {chip.label}</button>
        <button onClick={() => act("always")} className="w-full text-left py-3 t-summary">Always show {chip.label}</button>
        <button onClick={() => act("mute")} className="w-full text-left py-3 t-summary text-less">Mute {chip.label}</button>
      </motion.div>
    </motion.div>
  );
}

function ThreadView({ thread, onClose, onOpen, notify }) {
  const [cards, setCards] = useState(null);
  const ref = useRef(null);
  useEffect(() => {
    api.get(`/api/thread/${thread.thread_id}`).then((r) => setCards(r.cards)).catch(() => setCards([]));
  }, [thread.thread_id]);
  const swiped = (card, dir, dwell) => {
    setCards((c) => c.slice(1));
    interact(card.id, dir, { dwell_ms: dwell, article_id: card.article_id }).catch(() => {});
  };
  return (
    <motion.div className="fixed inset-0 z-30 bg-bg flex flex-col safe-top" initial={{ y: "100%" }} animate={{ y: 0 }} exit={{ y: "100%" }} transition={{ type: "spring", stiffness: 380, damping: 38 }}>
      <div className="flex items-center px-5 py-3">
        <button onClick={onClose} className="t-meta text-ink-2">‹ Back to feed</button>
        <div className="ml-auto t-meta text-ink">{thread.thread_title}{cards ? ` · ${cards.length} left` : ""}</div>
      </div>
      <div className="flex-1 min-h-0 px-3 pb-3">
        {cards && (
          <Stack
            cards={cards}
            stackRef={ref}
            onSwiped={swiped}
            onTap={(c) => { interact(c.id, "tap").catch(() => {}); onOpen(c.id); }}
            onDoubleTap={(c) => { interact(c.id, "save").then(() => notify("Saved for later")); }}
            empty={
              <div className="h-full rounded-[20px] bg-surface p-7 flex flex-col justify-center gap-4">
                <h2 className="t-headline">Thread done</h2>
                <button onClick={onClose} className="self-start px-4 py-2.5 rounded-full bg-raised t-meta">Back to feed</button>
              </div>
            }
          />
        )}
      </div>
    </motion.div>
  );
}

export default function Feed({ onOpen, onNeedsOnboarding, onDragging, onGoSeen, onGoControls, notify }) {
  const [mode, setMode] = useState("foryou");
  const [queue, setQueue] = useState(null);
  const [done, setDone] = useState(0);
  const [undoStack, setUndoStack] = useState([]);
  const [enterFrom, setEnterFrom] = useState(null);
  const [dragging, setDragging] = useState(false);
  const [chip, setChip] = useState(null);
  const [thread, setThread] = useState(null);
  const [ingest, setIngest] = useState(null);
  const [error, setError] = useState(null);
  const sessionStart = useRef(new Date());
  const swipedIds = useRef(new Set());
  const sinceRefresh = useRef(0);
  const stackRef = useRef(null);

  const load = useCallback(async (m = mode, keep = []) => {
    try {
      const exclude = [...swipedIds.current, ...keep.map((c) => c.id)].join(",");
      const r = await api.get(`/api/feed?mode=${m}&exclude=${exclude}`);
      setError(null);
      setIngest(r.last_ingest);
      if (!r.onboarded) onNeedsOnboarding();
      const fresh = r.cards.filter((c) => !swipedIds.current.has(c.id) && !keep.some((k) => k.id === c.id));
      setQueue([...keep, ...fresh]);
    } catch (e) {
      if (e.message !== "login required") setError(e.message);
      setQueue((q) => q || []);
    }
  }, [mode, onNeedsOnboarding]);

  useEffect(() => {
    setQueue(null);
    setDone(0);
    load(mode);
  }, [mode]); // eslint-disable-line react-hooks/exhaustive-deps

  const setDrag = (v) => { setDragging(v); onDragging(v); };

  const swiped = useCallback((card, dir, dwell) => {
    setEnterFrom(null);
    if (card.type === "thread" && dir === "right") {
      setQueue((q) => q.slice(1));
      setDone((d) => d + 1);
      setThread(card);
      interact(card.id, "right", { story_ids: card.story_ids, dwell_ms: dwell }).catch(() => {});
      return;
    }
    swipedIds.current.add(card.id);
    setQueue((q) => q.slice(1));
    setDone((d) => d + 1);
    const extra = { dwell_ms: dwell, article_id: card.article_id };
    if (card.type === "thread") extra.story_ids = card.story_ids;
    interact(card.id, dir, extra)
      .then((r) => setUndoStack((s) => [...s.slice(-4), { card, dir, id: r.interaction_id }]))
      .catch(() => {});
    // Re-rank every 10 swipes so the feed adapts within the session.
    sinceRefresh.current += 1;
    if (sinceRefresh.current >= 10) {
      sinceRefresh.current = 0;
      setQueue((q) => {
        const keep = q.slice(0, 2);
        load(mode, keep);
        return q;
      });
    }
  }, [load, mode, notify]);

  const undo = useCallback(async () => {
    const last = undoStack[undoStack.length - 1];
    if (!last) return;
    setUndoStack((s) => s.slice(0, -1));
    try { await api.post("/api/undo", { interaction_id: last.id }); } catch {}
    swipedIds.current.delete(last.card.id);
    setEnterFrom(last.dir);
    setQueue((q) => [last.card, ...(q || [])]);
    setDone((d) => Math.max(0, d - 1));
    haptic(8);
  }, [undoStack]);

  // Desktop: arrow keys work like swipes.
  useEffect(() => {
    const onKey = (e) => {
      if (thread || e.target.tagName === "INPUT") return;
      if (e.key === "ArrowRight") stackRef.current?.fly("right");
      else if (e.key === "ArrowLeft") stackRef.current?.fly("left");
      else if (e.key === "ArrowDown") stackRef.current?.fly("down");
      else if (e.key === "Enter" && queue?.[0]) onOpen(queue[0].id > 0 ? queue[0].id : queue[0].story_ids[0]);
      else if (e.key === "u" || e.key === "Backspace") undo();
      else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [queue, undo, onOpen, thread]);

  const total = done + (queue?.length || 0);
  const progress = total ? done / total : 0;

  return (
    <div className="h-full flex flex-col safe-top">
      {/* progress */}
      <div className="h-[3px] bg-divider/60">
        <motion.div className="h-full bg-accent" animate={{ width: `${progress * 100}%` }} transition={{ duration: 0.25 }} />
      </div>
      <header className={`flex items-center px-5 pt-3 pb-2 gap-5 transition-opacity duration-200 ${dragging ? "opacity-0" : ""}`}>
        {MODES.map(([key, label]) => (
          <button key={key} onClick={() => setMode(key)} className={`t-meta pb-1 ${mode === key ? "text-ink border-b-2 border-accent" : "text-ink-2"}`}>
            {label}
          </button>
        ))}
        <div className="ml-auto flex items-center gap-4">
          {total > 0 && <span className="t-chip text-ink-2">{Math.min(done + 1, total)} of {total}</span>}
          <button onClick={undo} disabled={!undoStack.length} className={`t-meta ${undoStack.length ? "text-ink" : "text-ink-2/40"}`}>↶ Undo</button>
        </div>
      </header>

      <div className="flex-1 min-h-0 px-3 pt-1 pb-2">
        {queue === null ? (
          <div className="h-full rounded-[20px] bg-surface animate-pulse" />
        ) : error && !queue.length ? (
          <div className="h-full rounded-[20px] bg-surface p-7 flex flex-col justify-center gap-4">
            <h2 className="t-headline">Can't reach Pulse</h2>
            <p className="t-summary text-ink-2">{error}</p>
            <button onClick={() => load(mode)} className="self-start px-4 py-2.5 rounded-full bg-raised t-meta">Try again</button>
          </div>
        ) : (
          <Stack
            cards={queue}
            stackRef={stackRef}
            enterFrom={enterFrom}
            onSwiped={swiped}
            onTap={(c) => {
              const sid = c.id > 0 ? c.id : c.story_ids[0];
              interact(sid, "tap").catch(() => {});
              onOpen(sid);
            }}
            onDoubleTap={(c) => {
              if (c.id < 0) return;
              interact(c.id, "save").then(() => notify("Saved for later")).catch(() => {});
              haptic(8);
            }}
            onDragState={setDrag}
            onChip={(c) => setChip(c)}
            onWhy={onGoControls}
            empty={<CaughtUp since={sessionStart.current} mode={mode} ingest={ingest} empty={done === 0} onSeen={onGoSeen} onRefresh={() => load(mode)} />}
          />
        )}
      </div>

      {/* thumb-reach actions (also what the gestures do) */}
      <div className={`grid grid-cols-3 px-5 pb-2 transition-opacity duration-200 ${dragging || !queue?.length ? "opacity-0 pointer-events-none" : ""}`}>
        <button onClick={() => stackRef.current?.fly("left")} className="py-2 t-meta text-ink-2 text-left">← Seen</button>
        <button onClick={() => stackRef.current?.fly("down")} className="py-2 t-meta text-ink-2 text-center">↓ Less</button>
        <button onClick={() => stackRef.current?.fly("right")} className="py-2 t-meta text-ink-2 text-right">More →</button>
      </div>

      <AnimatePresence>{chip && <ChipMenu chip={chip} onClose={() => setChip(null)} notify={notify} />}</AnimatePresence>
      <AnimatePresence>
        {thread && <ThreadView thread={thread} onClose={() => setThread(null)} onOpen={onOpen} notify={notify} />}
      </AnimatePresence>
    </div>
  );
}
