import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import {fileURLToPath} from 'node:url';
const file=name=>fileURLToPath(new URL(name,import.meta.url));
// Explicit local-only entry. Production main.jsx and vite.config.mjs are unchanged.
export default defineConfig({
  plugins:[{name:'isolated-fortune-preview',enforce:'pre',resolveId(source){
    if(/^\.{1,2}\/transport(?:\.js)?$/.test(source))return file('./src/fortune-preview/mockTransport.js');
    if(/^\.{1,2}\/telemetry(?:\.js)?$/.test(source))return file('./src/fortune-preview/mockTelemetry.js');
    if(/^\.{1,2}\/Platform(?:\.jsx)?$/.test(source))return file('./src/fortune-preview/mockPlatform.js');
    if(/^\.\/PlayerAvatar(?:\.jsx)?$/.test(source))return file('./src/fortune-preview/Avatar.jsx');
  }},react()],
  server:{host:'127.0.0.1',port:4174,strictPort:true,open:false},
  build:{outDir:'dist/fortune-preview',rollupOptions:{input:file('./fortune-preview.html')}},
});
