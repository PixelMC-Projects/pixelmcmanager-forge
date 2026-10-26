import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  build: {
    outDir: "dist/client",
  },
  optimizeDeps: {
    entries: ["index.html"],
    include: ["react", "react-dom/client"],
  },
  server: {
    host: "127.0.0.1",
    allowedHosts: ["terminal.local"],
    warmup: {
      clientFiles: ["./src/main.jsx"],
    },
  },
  plugins: [{name:'production-data-boundary',enforce:'pre',resolveId(source){
    if(/fortune-preview|prototypeTelemetry|titles\/previewStore/.test(source))throw new Error('Production must not import preview data: '+source);
  }},react()],
});
