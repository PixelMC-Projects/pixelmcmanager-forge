import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';
const file=name=>fileURLToPath(new URL(name,import.meta.url));
// Explicit, loopback-only Web prototype. No production service or credentials.
const wenzhouAdmin=file('../../../../WenZhou-Agent/admin-web/src');
const hubRoot=file('../../../../PixelMCPlayerHub');
export default defineConfig({
  base:'/manager/',
  define:{'import.meta.env.VITE_PREVIEW_SKIN':JSON.stringify(loadEnv('development',hubRoot,'VITE_PREVIEW_SKIN').VITE_PREVIEW_SKIN || '')},
  resolve:{alias:{'@wenzhou-admin':wenzhouAdmin,'@manager-console':file('./src'),'@wenzhou-preview':hubRoot+'/src/wenzhou','@hub-prototype':hubRoot+'/src/changelog','@hub-community':hubRoot+'/src/community','@hub-community-art':hubRoot+'/src/community','@hub-leaderboards-contract':hubRoot+'/src/leaderboards/contract.js','@hub-leaderboards-demo':hubRoot+'/src/leaderboards/demo.js','lucide-react':hubRoot+'/node_modules/lucide-react'},dedupe:['react','react-dom']},
  plugins:[{name:'isolated-playerhub-preview',enforce:'pre',resolveId(source){
    if(/^\.{1,2}\/transport(?:\.js)?$/.test(source))return file('./src/fortune-preview/mockTransport.js');
    if(/^\.{1,2}\/telemetry(?:\.js)?$/.test(source))return file('./src/fortune-preview/mockTelemetry.js');
    if(/^\.{1,2}\/Platform(?:\.jsx)?$/.test(source))return file('./src/fortune-preview/mockPlatform.js');
    if(/^\.{1,2}\/PlayerAvatar(?:\.jsx)?$/.test(source))return file('./src/fortune-preview/Avatar.jsx');
  },configureServer(server){server.middlewares.use((req,res,next)=>{if(req.url==='/manager/'||req.url==='/manager')req.url='/manager/hub-preview.html';next();});}},react()],
  server:{host:'127.0.0.1',port:4178,strictPort:true,open:false,fs:{allow:[file('.'),hubRoot,wenzhouAdmin]},hmr:{clientPort:4176}},
  build:{outDir:'dist/hub-preview',rollupOptions:{input:file('./hub-preview.html')}},
});
