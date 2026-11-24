import { useEffect, useState, useCallback } from "react";
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

export default function App() {
  const [authed, setAuthed] = useState(null);
  const [authError, setAuthError] = useState(null);
  const [tab, setTab] = useState("feed");
  const [detail, setDetail] = useState(null); // story id
  const [reader, setReader] = useState(null); // {articleId, storyId}
  const [onboarding, setOnboarding] = useState(false);
  const [chromeHidden, setChromeHidden] = useState(false);
  const [toast, setToast] = useState(null);

  const notify = useCallback((msg) => {
    setToast({ msg, key: Date.now() });
  }, []);

  useEffect(() => {
    api.get("/api/me")
      .then((r) => { setAuthed(r.authed); if (!r.authed && r.detail !== "login required") setAuthError(r.detail); })
      .catch(() => setAuthed(false));
    return onAuthLost(() => setAuthed(false));
  }, []);

  if (authed === null) return <div className="h-full bg-bg" />;
  if (!authed) return <Login error={authError} onDone={() => setAuthed(true)} />;
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

      <nav
        className={`safe-bottom border-t border-divider bg-bg transition-opacity duration-200 ${chromeHidden ? "opacity-0" : "opacity-100"}`}
      >
        <div className="flex">
          {TABS.map(([key, label]) => {
            const active = tab === key || (key === "controls" && tab === "insights");
            return (
              <button
                key={key}
                onClick={() => setTab(key)}
                className={`flex-1 py-3.5 t-meta ${active ? "text-ink" : "text-ink-2"}`}
              >
                <span className={`pb-1 ${active ? "border-b-2 border-accent" : ""}`}>{label}</span>
              </button>
            );
          })}
        </div>
      </nav>

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
