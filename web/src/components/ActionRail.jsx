import { useState } from "react";

const icon = { width: 24, height: 24, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 2, strokeLinecap: "round", strokeLinejoin: "round" };

/**
 * Reels-style action rail on the right of the card: Like (= swipe right), Save, Share, Sources.
 * Pointer events stop here so pressing a button never starts a swipe or opens the card.
 */
export default function ActionRail({ card, light, onLike, onSave, onShare, onSources }) {
  const [saved, setSaved] = useState(!!card.saved);
  const stop = (e) => e.stopPropagation();
  const cls = `rail-btn ${light ? "rail-light" : "rail-dark"}`;
  return (
    <div className="absolute right-2.5 bottom-[24%] z-10 flex flex-col gap-4" onPointerDown={stop} onClick={stop}>
      <button className={cls} aria-label="More like this" onClick={onLike}>
        <span className="rail-icon"><svg {...icon}><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" /></svg></span>
        Like
      </button>
      <button className={cls} aria-label="Save for later" onClick={() => { setSaved((s) => !s); onSave(!saved); }}>
        <span className="rail-icon" style={saved ? { color: "var(--accent)" } : undefined}>
          <svg {...icon} fill={saved ? "currentColor" : "none"}><path d="M6 3.5h12v17l-6-4-6 4z" /></svg>
        </span>
        {saved ? "Saved" : "Save"}
      </button>
      <button className={cls} aria-label="Share" onClick={onShare}>
        <span className="rail-icon"><svg {...icon}><path d="M21 3 10 14" /><path d="m21 3-7 18-4-7-7-4z" /></svg></span>
        Share
      </button>
      {card.source_count > 1 && (
        <button className={cls} aria-label="All sources" onClick={onSources}>
          <span className="rail-icon"><svg {...icon}><rect x="4" y="4" width="16" height="16" rx="3" /><path d="M8 9h8M8 13h8M8 17h5" /></svg></span>
          {card.source_count}
        </button>
      )}
    </div>
  );
}
