import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { fileURLToPath } from "node:url";
import { resolve } from "node:path";
import { existsSync } from "node:fs";

const hubRoot = process.env.PIXELMC_HUB_SOURCE_ROOT || fileURLToPath(new URL("../../../../PixelMCPlayerHub", import.meta.url));

const wenzhouAdmin = process.env.WENZHOU_ADMIN_DIR
  ? resolve(process.env.WENZHOU_ADMIN_DIR, "src")
  : ["../../WenZhou-Agent/admin-web/src/", "../../../../WenZhou-Agent/admin-web/src/"]
    .map(relative => fileURLToPath(new URL(relative, import.meta.url))).find(existsSync);
if (!wenzhouAdmin || !existsSync(wenzhouAdmin)) {
  throw new Error("WenZhou-Agent admin-web source is required. See BUILDING.md.");
}

export default defineConfig({
  resolve: {
    dedupe: ["react", "react-dom", "@phosphor-icons/react", "@tiptap/react", "@tiptap/starter-kit"],
    alias: {
      "@manager-console": fileURLToPath(new URL("./src/", import.meta.url)),
      "@wenzhou-admin": wenzhouAdmin,
      "@hub-community-art": resolve(hubRoot, "src/community"),
      "@hub-leaderboards-contract": resolve(hubRoot, "src/leaderboards/contract.js"),
    },
  },
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
    if(/wenzhou-preview|@wenzhou-preview|fortune-preview|hub-preview|@hub-prototype|community[\\/](?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-community\/(?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-leaderboards-demo|leaderboards[\\/](?:demo|store)|changelog\/(?:store|model)|prototypeTelemetry|titles\/previewStore/.test(source))throw new Error('Production must not import preview data: '+source);
  },generateBundle(){
    const forbidden=[...this.getModuleIds()].filter(id=>/wenzhou-preview|@wenzhou-preview|fortune-preview|hub-preview|@hub-prototype|community[\\/](?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-community\/(?:demo|adminPreview|memoryStore|memoryCollections|profileStore)|@hub-leaderboards-demo|leaderboards[\\/](?:demo|store)|PixelMCPlayerHub[\\/]src[\\/]changelog[\\/](?:model|store)|prototypeTelemetry|titles[\\/]previewStore/.test(id));
    if(forbidden.length)throw new Error('Preview modules entered production graph: '+forbidden.join(', '));
  }},react()],
});
