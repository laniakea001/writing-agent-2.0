import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
export default defineConfig({base:'/workbench/',plugins:[react()],server:{proxy:{'/api':{target:process.env.WRITING_API_URL||'http://127.0.0.1:8088',changeOrigin:true}}},build:{outDir:'dist'}});
