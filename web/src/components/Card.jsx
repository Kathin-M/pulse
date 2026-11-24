import { useRef, useState } from "react";
import { ago } from "../api.js";

function Img({ card, className }) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  if (failed) return <div className={`${className} bg-raised`} />;
  return (
    <div
      className={`${className} bg-raised bg-cover bg-center overflow-hidden`}
      style={card.lqip ? { backgroundImage: `url(${card.lqip})` } : undefined}
    >
      {card.lqip && <div className="absolute inset-0 backdrop-blur-xl" />}
      <img
        src={card.image}
        alt=""
        referrerPolicy="no-referrer"
        draggable={false}
        onLoad={() => setLoaded(true)}
        onError={() => setFailed(true)}
        className={`absolute inset-0 w-full h-full object-cover transition-opacity duration-300 ${loaded ? "opacity-100" : "opacity-0"}`}
      />
    </div>
  );
}

function Badge({ card, light }) {
  if (!card.badge) return null;
  const isNew = card.badge === "Something new";
  return (
    <span
      className={`t-chip px-2.5 py-1 rounded-full ${
        isNew ? "border border-accent text-accent" : "bg-accent text-black"
      } ${light && isNew ? "bg-black/30" : ""}`}
    >
      {card.badge === "Thread" ? `Thread · ${card.thread_count} stories` : card.badge}
    </span>
  );
}

function Chips({ card, onChip, light }) {
  const chips = [];
  const place = card.places?.find((p) => p !== "India");
  if (place) chips.push({ label: place, kind: "place" });
  (card.topics || []).slice(0, 2).forEach((t) => chips.push({ label: t, kind: "topic" }));
  const timer = useRef(null);
  return (
    <div className="flex flex-wrap gap-2">
      {chips.map((c) => (
        <button
          key={c.kind + c.label}
          onPointerDown={(e) => {
            e.stopPropagation();
            timer.current = setTimeout(() => onChip?.(c), 450);
          }}
          onPointerUp={() => clearTimeout(timer.current)}
          onPointerLeave={() => clearTimeout(timer.current)}
          onClick={(e) => e.stopPropagation()}
          className={`t-chip px-3 py-1.5 rounded-full ${light ? "bg-white/15 text-white" : "bg-raised text-ink"}`}
        >
          {c.label}
        </button>
      ))}
    </div>
  );
}

function SourceRow({ card, light }) {
  return (
    <div className={`flex items-center gap-2 t-meta ${light ? "text-white/80" : "text-ink-2"}`}>
      <span className={light ? "text-white font-semibold" : "text-ink font-semibold"}>{card.publisher}</span>
      {card.source_count > 1 && (
        <span className={`t-chip px-2 py-0.5 rounded-full ${light ? "bg-white/15 text-white" : "bg-raised text-ink"}`}>
          +{card.source_count - 1} {card.source_count - 1 === 1 ? "source" : "sources"}
        </span>
      )}
      <span className="ml-auto">{ago(card.first_seen)}</span>
    </div>
  );
}

function Why({ card, light, onWhy }) {
  return (
    <button
      onClick={(e) => { e.stopPropagation(); onWhy?.(); }}
      className={`t-meta text-left ${light ? "text-white/70" : "text-ink-2"}`}
    >
      Why: {card.why}
    </button>
  );
}

function UpdateLine({ card, light }) {
  if (card.type !== "update" || !card.update_title) return null;
  return (
    <div className={`t-meta ${light ? "text-white" : "text-ink"}`}>
      <span className="text-accent">Since you saw this:</span> {card.update_title}
    </div>
  );
}

function ThreadStack({ card }) {
  if (card.type !== "thread") return null;
  return (
    <ul className="space-y-1.5">
      {(card.headlines || []).slice(1, 4).map((h, i) => (
        <li key={i} className="t-meta text-ink-2 clamp-2">· {h}</li>
      ))}
    </ul>
  );
}

export default function Card({ card, onChip, onWhy, rail }) {
  const pr = rail ? "pr-16" : "";
  const breaking = card.badge === "Breaking";
  const edge = breaking ? "ring-2 ring-accent" : "";
  const title = card.type === "thread" ? card.thread_title : card.title;
  // Whole headline, always: long ones step down a size instead of being cut off.
  const big = (title || "").length > 110 ? "t-headline" : "t-headline-xl";

  if (card.layout === "full" && card.image) {
    return (
      <div className={`relative h-full w-full rounded-[20px] overflow-hidden bg-surface ${edge}`}>
        <Img card={card} className="absolute inset-0" />
        <div
          className="absolute inset-x-0 bottom-0 h-[70%]"
          style={{ background: "linear-gradient(to bottom, rgba(0,0,0,0) 0%, rgba(0,0,0,0.6) 35%, rgba(0,0,0,0.85) 100%)" }}
        />
        <div className="absolute top-4 left-4"><Badge card={card} light /></div>
        <div className={`absolute inset-x-0 bottom-0 top-14 p-5 ${pr} flex flex-col justify-end gap-3 text-white overflow-hidden`}>
          <SourceRow card={card} light />
          <h2 className={`${big} shrink-0`}>{title}</h2>
          <UpdateLine card={card} light />
          {card.type !== "update" && card.summary && <p className="t-summary text-white/85 clamp-2 min-h-0 overflow-hidden">{card.summary}</p>}
          <Chips card={card} onChip={onChip} light />
          <Why card={card} onWhy={onWhy} light />
        </div>
      </div>
    );
  }

  if (card.layout === "photo" && card.image) {
    return (
      <div className={`h-full w-full rounded-[20px] overflow-hidden bg-surface flex flex-col ${edge}`}>
        <div className={`relative ${(title || "").length > 90 ? "h-[28%]" : "h-[36%]"} shrink-0`}>
          <Img card={card} className="absolute inset-0" />
          <div className="absolute top-4 left-4"><Badge card={card} light /></div>
        </div>
        <div className={`flex-1 min-h-0 p-5 ${pr} flex flex-col gap-3`}>
          <SourceRow card={card} />
          <h2 className="t-headline shrink-0">{title}</h2>
          <UpdateLine card={card} />
          {card.type !== "update" && card.summary && <p className="t-summary text-ink/85 clamp-3 min-h-0 overflow-hidden">{card.summary}</p>}
          <ThreadStack card={card} />
          <div className="mt-auto space-y-3">
            <Chips card={card} onChip={onChip} />
            <Why card={card} onWhy={onWhy} />
            <div className="border-t border-divider pt-3 t-meta text-ink-2 text-center">
              Tap for summary and all {card.source_count} {card.source_count === 1 ? "source" : "sources"}
            </div>
          </div>
        </div>
      </div>
    );
  }

  // Text-only editorial card: the outlet name set large is the visual.
  return (
    <div className={`h-full w-full rounded-[20px] overflow-hidden bg-surface p-6 ${pr} flex flex-col gap-4 ${edge}`}>
      <div className="flex items-center gap-3">
        <Badge card={card} />
      </div>
      <div className="font-serif text-[40px] leading-[44px] font-semibold text-ink-2/60 clamp-2 shrink-0" style={{ fontSize: `calc(${(title || "").length > 90 ? 28 : 40}px * var(--scale))`, lineHeight: 1.1 }}>
        {card.publisher}
      </div>
      <div className="h-[2px] w-14 bg-accent" />
      <h2 className={`${big} shrink-0`}>{title}</h2>
      <UpdateLine card={card} />
      {card.type !== "update" && card.summary && <p className="t-summary text-ink/85 clamp-4 min-h-0 overflow-hidden">{card.summary}</p>}
      <ThreadStack card={card} />
      <div className="mt-auto space-y-3">
        <SourceRow card={card} />
        <Chips card={card} onChip={onChip} />
        <Why card={card} onWhy={onWhy} />
      </div>
    </div>
  );
}
