import { useEffect, useRef, useState } from "react";
import { motion } from "motion/react";
import { api, interact, ago } from "../api.js";

/** Clean reader view: extracted text only, no ads. Reading time feeds the ranking. */
export default function Reader({ articleId, storyId, onClose }) {
  const [a, setA] = useState(null);
  const [err, setErr] = useState(null);
  const opened = useRef(Date.now());

  useEffect(() => {
    api.get(`/api/article/${articleId}/reader`).then(setA).catch((e) => setErr(e.message));
    const start = opened.current;
    return () => {
      const dwell = Date.now() - start;
      if (dwell > 3000) interact(storyId, "read", { article_id: articleId, dwell_ms: dwell }).catch(() => {});
    };
  }, [articleId, storyId]);

  return (
    <motion.div
      className="fixed inset-0 z-30 bg-bg flex flex-col safe-top"
      initial={{ y: "100%" }} animate={{ y: 0 }} exit={{ y: "100%" }}
      transition={{ type: "spring", stiffness: 360, damping: 40 }}
    >
      <div className="flex items-center px-5 py-3">
        <button onClick={onClose} className="t-meta text-ink-2">‹ Close</button>
        {a && <a href={a.url} target="_blank" rel="noreferrer noopener" className="ml-auto t-meta text-ink-2">Original site ↗</a>}
      </div>
      <article className="flex-1 overflow-y-auto no-scrollbar px-5 pb-16 max-w-2xl w-full mx-auto select-text">
        {err && <p className="t-summary text-ink-2 pt-6">{err}</p>}
        {!a && !err && <div className="space-y-3 pt-4">{[1, 2, 3, 4].map((i) => <div key={i} className="h-5 rounded bg-surface animate-pulse" />)}</div>}
        {a && (
          <>
            <div className="t-meta text-ink-2 mb-2">{a.publisher} · {ago(a.published_at)}</div>
            <h1 className="t-headline-xl mb-5">{a.title}</h1>
            {a.image && <img src={a.image} referrerPolicy="no-referrer" alt="" className="w-full rounded-[16px] mb-6 bg-raised" />}
            <div className="reader">
              {a.paragraphs.length ? a.paragraphs.map((p, i) => <p key={i}>{p}</p>) : (
                <>
                  {a.summary && <p>{a.summary}</p>}
                  <p className="!font-sans !text-base text-ink-2">The full text isn't available here (it may be paywalled). </p>
                </>
              )}
            </div>
            <a href={a.url} target="_blank" rel="noreferrer noopener" className="inline-block mt-4 px-4 py-2.5 rounded-full bg-raised t-meta">
              Read on {a.publisher} ↗
            </a>
          </>
        )}
      </article>
    </motion.div>
  );
}
