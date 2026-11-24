import { useCallback, useEffect, useRef, useState } from "react";
import { AnimatePresence, motion } from "motion/react";
import { api, interact, haptic, shareStory } from "../api.js";
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

/** Faint markers on the card edges showing what each swipe does. Turn off in Controls → Look & feel. */
function SwipeHints({ dragging }) {
  // markers sit astride the card edges (half outside), so they never cover the story
  return (
    <div className={`pointer-events-none absolute inset-x-3 top-4 bottom-4 z-20 transition-opacity duration-300 ${dragging ? "opacity-0" : "opacity-100"}`}>
      <span className="hint absolute left-1/2 top-0 -translate-x-1/2 -translate-y-1/2">↑ Fine</span>
      <span className="hint absolute left-1/2 bottom-0 -translate-x-1/2 translate-y-1/2">↓ Less</span>
      <span className="hint absolute left-0 top-[46%] -translate-x-1/4">‹ Seen</span>
      <span className="hint absolute right-0 top-[18%] translate-x-1/4">More ›</span>
    </div>
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
  const hints = localStorage.getItem("pulse.hints") !== "off";
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

  const share = useCallback(async (card) => {
    const r = await shareStory(card);
    if (r === "copied") notify("Link copied");
  }, [notify]);

  // Desktop: arrow keys work like swipes.
  useEffect(() => {
    const onKey = (e) => {
      if (thread || e.target.tagName === "INPUT") return;
      if (e.key === "ArrowRight") stackRef.current?.fly("right");
      else if (e.key === "ArrowLeft") stackRef.current?.fly("left");
      else if (e.key === "ArrowDown") stackRef.current?.fly("down");
      else if (e.key === "ArrowUp") stackRef.current?.fly("up");
      else if (e.key === "s" && queue?.[0]) share(queue[0]);
      else if (e.key === "Enter" && queue?.[0]) onOpen(queue[0].id > 0 ? queue[0].id : queue[0].story_ids[0]);
      else if (e.key === "u" || e.key === "Backspace") undo();
      else return;
      e.preventDefault();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [queue, undo, onOpen, thread, share]);

  const total = done + (queue?.length || 0);
  const progress = total ? done / total : 0;
  const segRef = useRef(null);
  useEffect(() => { segRef.current?.lgSelect?.(MODES.findIndex(([k]) => k === mode)); }, [mode]);

  return (
    <div className="h-full flex flex-col safe-top">
      {/* top bar: liquid-glass segmented control + undo, with a thin progress line */}
      <header className={`px-3 pt-2 pb-2 transition-opacity duration-200 ${dragging ? "opacity-0" : ""}`}>
        <div className="flex items-center gap-2">
          <div ref={segRef} className="lg-segmented flex-1" data-liquid-glass="regular">
            {MODES.map(([key, label]) => (
              <button key={key} aria-pressed={mode === key} onClick={() => setMode(key)}>{label}</button>
            ))}
          </div>
          <button
            onClick={undo} disabled={!undoStack.length} aria-label="Undo last swipe"
            className="lg-button lg-button--icon !h-[44px] !w-[44px] disabled:opacity-40" data-liquid-glass="regular"
          >
            <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round"><path d="M9 14 4 9l5-5" /><path d="M4 9h10.5a5.5 5.5 0 0 1 0 11H11" /></svg>
          </button>
        </div>
        <div className="flex items-center gap-3 mt-2 px-1">
          <div className="flex-1 h-[3px] rounded-full bg-divider/70 overflow-hidden">
            <motion.div className="h-full bg-accent rounded-full" animate={{ width: `${progress * 100}%` }} transition={{ duration: 0.25 }} />
          </div>
          {total > 0 && <span className="t-chip text-ink-2 tabular-nums">{Math.min(done + 1, total)} / {total}</span>}
        </div>
      </header>

      <div className={`flex-1 min-h-0 px-3 relative ${hints ? "pt-4 pb-4" : "pt-1 pb-2"}`}>
        {hints && !!queue?.length && <SwipeHints dragging={dragging} />}
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
            onAction={(c, action) => {
              if (action === "share") share(c);
              else if (action === "sources") onOpen(c.id > 0 ? c.id : c.story_ids[0]);
              else if (c.id > 0) interact(c.id, action).then(() => notify(action === "save" ? "Saved for later" : "Removed from saved")).catch(() => {});
            }}
            empty={<CaughtUp since={sessionStart.current} mode={mode} ingest={ingest} empty={done === 0} onSeen={onGoSeen} onRefresh={() => load(mode)} />}
          />
        )}
      </div>

      <AnimatePresence>{chip && <ChipMenu chip={chip} onClose={() => setChip(null)} notify={notify} />}</AnimatePresence>
      <AnimatePresence>
        {thread && <ThreadView thread={thread} onClose={() => setThread(null)} onOpen={onOpen} notify={notify} />}
      </AnimatePresence>
    </div>
  );
}
