import { useEffect, useState, useCallback, useRef } from "react";
import { AnimatePresence } from "motion/react";
import { api, onAuthLost } from "./api.js";
import Login from "./components/Login.jsx";
import Feed from "./components/Feed.jsx";
import Seen from "./components/Seen.jsx";
import Liked from "./components/Liked.jsx";
import Controls from "./components/Controls.jsx";
import Insights from "./components/Insights.jsx";
import StoryDetail from "./components/StoryDetail.jsx";
import Reader from "./components/Reader.jsx";
import Onboarding from "./components/Onboarding.jsx";
import Toast from "./components/Toast.jsx";

const TABS = [
  ["feed", "Feed"],
  ["seen", "Seen"],
  ["liked", "Liked"],
  ["controls", "Controls"],
];

function TabIcon({ name }) {
  const common = { width: 22, height: 22, viewBox: "0 0 24 24", fill: "none", stroke: "currentColor", strokeWidth: 1.8, strokeLinecap: "round", strokeLinejoin: "round" };
  if (name === "feed") return <svg {...common}><rect x="5" y="3.5" width="14" height="17" rx="3" /><path d="M8.5 8h7M8.5 12h7M8.5 16h4" /></svg>;
  if (name === "seen") return <svg {...common}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></svg>;
  if (name === "liked") return <svg {...common}><path d="M12 20s-7-4.4-7-10a4 4 0 0 1 7-2.6A4 4 0 0 1 19 10c0 5.6-7 10-7 10z" /></svg>;
  return <svg {...common}><path d="M4 7h10M18 7h2M4 17h4M12 17h8" /><circle cx="16" cy="7" r="2" /><circle cx="10" cy="17" r="2" /></svg>;
}

export default function App() {
  const [authed, setAuthed] = useState(null);
  const [tab, setTab] = useState("feed");
  const [detail, setDetail] = useState(null); // story id
  const [reader, setReader] = useState(null); // {articleId, storyId}
  const [onboarding, setOnboarding] = useState(false);
  const [chromeHidden, setChromeHidden] = useState(false);
  const [toast, setToast] = useState(null);
  const tabbarRef = useRef(null);

  // keep the glass droplet in sync when the tab changes from code (e.g. "Open Seen")
  useEffect(() => {
    const i = TABS.findIndex(([k]) => k === (tab === "insights" ? "controls" : tab));
    tabbarRef.current?.lgSelect?.(i);
  }, [tab]);

  const notify = useCallback((msg) => {
    setToast({ msg, key: Date.now() });
  }, []);

  useEffect(() => {
    api.get("/api/me").then((r) => setAuthed(r.authed)).catch(() => setAuthed(false));
    return onAuthLost(() => setAuthed(false));
  }, []);

  if (authed === null) return <div className="h-full bg-bg" />;
  if (!authed) return <Login onDone={() => setAuthed(true)} />;
  if (onboarding) return <Onboarding onDone={() => { setOnboarding(false); setTab("feed"); }} />;

  return (
    <div className="h-full flex flex-col bg-bg text-ink overflow-hidden select-none">
      <main className="flex-1 min-h-0 relative">
        {tab === "feed" && (
          <Feed
            onOpen={(id) => setDetail(id)}
            onNeedsOnboarding={() => setOnboarding(true)}
            onDragging={setChromeHidden}
            onGoSeen={() => setTab("seen")}
            onGoControls={() => setTab("controls")}
            notify={notify}
          />
        )}
        {tab === "seen" && <Seen onOpen={(id) => setDetail(id)} notify={notify} />}
        {tab === "liked" && <Liked onOpen={(id) => setDetail(id)} />}
        {tab === "controls" && (
          <Controls onInsights={() => setTab("insights")} onOnboarding={() => setOnboarding(true)} notify={notify} />
        )}
        {tab === "insights" && <Insights onBack={() => setTab("controls")} notify={notify} />}
      </main>

      {/* floating liquid-glass tab bar, within thumb reach; fades while you swipe */}
      <div className={`safe-bottom px-4 pb-2 pt-1 transition-opacity duration-200 ${chromeHidden ? "opacity-0 pointer-events-none" : "opacity-100"}`}>
        <nav ref={tabbarRef} className="lg-tabbar w-full" data-liquid-glass="regular">
          {TABS.map(([key, label]) => {
            const active = tab === key || (key === "controls" && tab === "insights");
            return (
              <button key={key} className="lg-tabbar__item" aria-current={active ? "page" : undefined} onClick={() => setTab(key)}>
                <TabIcon name={key} />
                <span>{label}</span>
              </button>
            );
          })}
        </nav>
      </div>

      <AnimatePresence>
        {detail != null && (
          <StoryDetail
            key={`d${detail}`}
            storyId={detail}
            onClose={() => setDetail(null)}
            onRead={(articleId) => setReader({ articleId, storyId: detail })}
            notify={notify}
          />
        )}
      </AnimatePresence>
      <AnimatePresence>
        {reader && <Reader key={`r${reader.articleId}`} {...reader} onClose={() => setReader(null)} />}
      </AnimatePresence>
      <Toast toast={toast} />
    </div>
  );
}
