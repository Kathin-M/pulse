import { useState } from "react";
import { api } from "../api.js";

/** Sign in or create an account. Each account gets its own feed, history and learned interests. */
export default function Login({ onDone }) {
  const [mode, setMode] = useState("signin");
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState(null);
  const [busy, setBusy] = useState(false);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setErr(null);
    try {
      await api.post(mode === "signin" ? "/api/login" : "/api/signup", { username, password });
      onDone();
    } catch (e2) {
      setErr(e2.message);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="h-full flex items-center justify-center bg-bg px-6 login-bg">
      <form onSubmit={submit} className="w-full max-w-sm space-y-5 lg rounded-[28px] p-6" data-liquid-glass="regular">
        <div className="flex items-center gap-3">
          <img src="/favicon.svg" alt="" className="w-11 h-11" />
          <h1 className="font-serif text-[34px] font-semibold">Pulse</h1>
        </div>
        <p className="t-summary text-ink-2">Your news, one story at a time. It learns what you like.</p>
        <div className="lg-segmented w-full" data-liquid-glass="regular">
          <button type="button" aria-pressed={mode === "signin"} onClick={() => setMode("signin")}>Sign in</button>
          <button type="button" aria-pressed={mode === "signup"} onClick={() => setMode("signup")}>Create account</button>
        </div>
        <input
          autoFocus value={username} onChange={(e) => setUsername(e.target.value)} placeholder="Username"
          autoCapitalize="none" autoCorrect="off" autoComplete="username"
          className="w-full bg-raised rounded-full px-5 py-3 t-summary outline-none placeholder:text-ink-2"
        />
        <input
          type="password" value={password} onChange={(e) => setPassword(e.target.value)} placeholder="Password"
          autoComplete={mode === "signin" ? "current-password" : "new-password"}
          className="w-full bg-raised rounded-full px-5 py-3 t-summary outline-none placeholder:text-ink-2"
        />
        {mode === "signup" && <p className="t-chip text-ink-2">3–24 letters, numbers, dots or underscores. Password at least 6 characters.</p>}
        {err && <p className="t-meta text-less">{err}</p>}
        <button disabled={busy || !username || !password} className="lg-button lg-button--prominent w-full !h-12 t-summary font-semibold disabled:opacity-50">
          {busy ? "One moment…" : mode === "signin" ? "Sign in" : "Create account"}
        </button>
      </form>
    </div>
  );
}
