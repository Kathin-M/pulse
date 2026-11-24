import { useState } from "react";
import { api } from "../api.js";

export default function Login({ onDone, error }) {
  const [pw, setPw] = useState("");
  const [err, setErr] = useState(error);
  const [busy, setBusy] = useState(false);
  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    try {
      await api.post("/api/login", { password: pw });
      onDone();
    } catch (e2) {
      setErr(e2.message);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div className="h-full flex items-center justify-center bg-bg px-6">
      <form onSubmit={submit} className="w-full max-w-sm space-y-5">
        <div className="flex items-center gap-3">
          <img src="/favicon.svg" alt="" className="w-10 h-10" />
          <h1 className="font-serif text-[34px] font-semibold">Pulse</h1>
        </div>
        <p className="t-summary text-ink-2">Your private news. One story at a time.</p>
        <input
          type="password" autoFocus value={pw} onChange={(e) => setPw(e.target.value)} placeholder="Password"
          className="w-full bg-raised rounded-full px-5 py-3 t-summary outline-none placeholder:text-ink-2"
        />
        {err && <p className="t-meta text-less">{err}</p>}
        <button disabled={busy || !pw} className="w-full py-3 rounded-full bg-accent text-black t-summary font-semibold disabled:opacity-50">
          {busy ? "Checking…" : "Open Pulse"}
        </button>
      </form>
    </div>
  );
}
