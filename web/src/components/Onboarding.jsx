import { useEffect, useState } from "react";
import { api } from "../api.js";

/** Pick a city and 5+ topics. This only seeds the profile; swipes take over from there. */
export default function Onboarding({ onDone }) {
  const [opts, setOpts] = useState(null);
  const [step, setStep] = useState(0);
  const [city, setCity] = useState("Bengaluru");
  const [topics, setTopics] = useState([]);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get("/api/controls").then((r) => {
      setOpts(r);
      setCity(r.prefs.city);
      setTopics(r.rules.filter((x) => x.kind === "topic" && x.mode === "follow").map((x) => x.label));
    });
  }, []);

  const toggle = (t) => setTopics((ts) => (ts.includes(t) ? ts.filter((x) => x !== t) : [...ts, t]));
  const finish = async () => {
    setBusy(true);
    await api.post("/api/onboard", { city, topics });
    onDone();
  };

  if (!opts) return <div className="h-full bg-bg" />;
  return (
    <div className="h-full flex flex-col bg-bg safe-top safe-bottom">
      <div className="h-[3px] bg-divider"><div className="h-full bg-accent transition-all" style={{ width: step === 0 ? "50%" : "100%" }} /></div>
      <div className="flex-1 overflow-y-auto no-scrollbar px-6 pt-8 pb-6">
        {step === 0 ? (
          <>
            <h1 className="t-headline-xl">Where are you?</h1>
            <p className="t-summary text-ink-2 mt-2 mb-6">Local news comes from your city and state. You can change this any time.</p>
            <div className="flex flex-wrap gap-2">
              {opts.cities.map((c) => (
                <button key={c.name} onClick={() => setCity(c.name)}
                  className={`t-meta px-3.5 py-2 rounded-full ${city === c.name ? "bg-accent text-black" : "bg-raised"}`}>
                  {c.name}
                </button>
              ))}
            </div>
          </>
        ) : (
          <>
            <h1 className="t-headline-xl">What do you follow?</h1>
            <p className="t-summary text-ink-2 mt-2 mb-6">Pick at least five. Pulse keeps learning from every swipe after this.</p>
            <div className="flex flex-wrap gap-2">
              {opts.topics.map((t) => (
                <button key={t} onClick={() => toggle(t)}
                  className={`t-meta px-3.5 py-2 rounded-full ${topics.includes(t) ? "bg-accent text-black" : "bg-raised"}`}>
                  {t}
                </button>
              ))}
            </div>
          </>
        )}
      </div>
      <div className="px-6 pb-6 flex gap-3">
        {step === 1 && <button onClick={() => setStep(0)} className="px-5 py-3 rounded-full bg-raised t-summary">Back</button>}
        <button
          disabled={busy || (step === 1 && topics.length < 5)}
          onClick={() => (step === 0 ? setStep(1) : finish())}
          className="flex-1 py-3 rounded-full bg-accent text-black t-summary font-semibold disabled:opacity-40"
        >
          {step === 0 ? "Next" : topics.length < 5 ? `Pick ${5 - topics.length} more` : busy ? "Setting up…" : "Start reading"}
        </button>
      </div>
    </div>
  );
}
