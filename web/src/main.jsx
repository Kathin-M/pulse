import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import { registerSW } from "virtual:pwa-register";
import App from "./App.jsx";
import "./styles.css";

// Apply saved appearance before first paint.
const theme = localStorage.getItem("pulse.theme");
if (theme && theme !== "system") document.documentElement.dataset.theme = theme;
const size = localStorage.getItem("pulse.size");
if (size) document.documentElement.dataset.size = size;

// Auto-update: check for a new deploy on launch and every 30 minutes; reload into it.
registerSW({
  immediate: true,
  onRegisteredSW(_url, reg) {
    if (reg) setInterval(() => reg.update(), 30 * 60 * 1000);
  },
});

createRoot(document.getElementById("root")).render(
  <StrictMode>
    <App />
  </StrictMode>
);
