import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";
import { VitePWA } from "vite-plugin-pwa";

export default defineConfig({
  plugins: [
    react(),
    tailwindcss(),
    VitePWA({
      // New deploys are picked up automatically: the service worker updates itself
      // and the app reloads to the new version the next time it is opened.
      registerType: "autoUpdate",
      includeAssets: ["favicon.svg", "apple-touch-icon.png"],
      manifest: {
        name: "Pulse",
        short_name: "Pulse",
        description: "Your private news swiper",
        theme_color: "#0B0B0C",
        background_color: "#0B0B0C",
        display: "standalone",
        orientation: "portrait",
        start_url: "/",
        icons: [
          { src: "/icon-192.png", sizes: "192x192", type: "image/png" },
          { src: "/icon-512.png", sizes: "512x512", type: "image/png" },
          { src: "/icon-512.png", sizes: "512x512", type: "image/png", purpose: "maskable" },
        ],
      },
      workbox: {
        globPatterns: ["**/*.{js,css,html,woff2,svg,png}"],
        navigateFallback: "/index.html",
        navigateFallbackDenylist: [/^\/api\//],
        cleanupOutdatedCaches: true,
        clientsClaim: true,
        skipWaiting: true,
        runtimeCaching: [
          {
            urlPattern: ({ url }) => url.pathname.startsWith("/api/feed") || url.pathname.startsWith("/api/liked"),
            handler: "NetworkFirst",
            options: { cacheName: "pulse-api", networkTimeoutSeconds: 6, expiration: { maxEntries: 20, maxAgeSeconds: 86400 * 3 } },
          },
          {
            urlPattern: ({ url }) => url.pathname.startsWith("/api/article/") || url.pathname.startsWith("/api/story/"),
            handler: "NetworkFirst",
            options: { cacheName: "pulse-detail", networkTimeoutSeconds: 6, expiration: { maxEntries: 150, maxAgeSeconds: 86400 * 7 } },
          },
          {
            urlPattern: ({ request, url }) => request.destination === "image" && url.origin !== self.location.origin,
            handler: "CacheFirst",
            options: { cacheName: "pulse-images", expiration: { maxEntries: 300, maxAgeSeconds: 86400 * 7 }, cacheableResponse: { statuses: [0, 200] } },
          },
        ],
      },
    }),
  ],
  server: { proxy: { "/api": "http://127.0.0.1:8000" } },
});
