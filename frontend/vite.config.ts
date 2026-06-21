import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  base: "/admin/",
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/knowledge-base": "http://127.0.0.1:18080",
      "/admin/overview": "http://127.0.0.1:18080",
      "/intent-tree": "http://127.0.0.1:18080",
      "/users": "http://127.0.0.1:18080",
      "/user": "http://127.0.0.1:18080",
      "/ingestion": "http://127.0.0.1:18080",
      "/mappings": "http://127.0.0.1:18080",
      "/sample-questions": "http://127.0.0.1:18080",
      "/rag": "http://127.0.0.1:18080",
      "/conversations": "http://127.0.0.1:18080",
      "/test": "http://127.0.0.1:18080"
    }
  },
  build: {
    outDir: "../bootstrap/src/main/resources/static/admin",
    emptyOutDir: true,
    sourcemap: false,
    rollupOptions: {
      output: {
        entryFileNames: "admin-knowledge.js",
        chunkFileNames: "admin-[name].js",
        assetFileNames: (assetInfo) =>
          assetInfo.name?.endsWith(".css") ? "admin-knowledge.css" : "admin-[name][extname]"
      }
    }
  }
});
