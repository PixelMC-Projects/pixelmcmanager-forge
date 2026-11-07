import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";

const hubRoot = process.env.PIXELMC_HUB_SOURCE_ROOT || fileURLToPath(new URL("../../../../PixelMCPlayerHub", import.meta.url));

export default defineConfig({
  resolve: { dedupe: ["react", "react-dom"], alias: { "@hub-community-art": resolve(hubRoot, "src/community"), "@hub-leaderboards-contract": resolve(hubRoot, "src/leaderboards/contract.js") } },
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
    if(/fortune-preview|hub-preview|@hub-prototype|community[\\/](?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-community\/(?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-leaderboards-demo|leaderboards[\\/](?:demo|store)|changelog\/(?:store|model)|prototypeTelemetry|titles\/previewStore/.test(source))throw new Error('Production must not import preview data: '+source);
  },generateBundle(){
    const forbidden=[...this.getModuleIds()].filter(id=>/fortune-preview|hub-preview|@hub-prototype|community[\\/](?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-community\/(?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-leaderboards-demo|leaderboards[\\/](?:demo|store)|PixelMCPlayerHub[\\/]src[\\/]changelog[\\/](?:model|store)|prototypeTelemetry|titles[\\/]previewStore/.test(id));
    if(forbidden.length)throw new Error('Preview modules entered production graph: '+forbidden.join(', '));
  }},react()],
});
