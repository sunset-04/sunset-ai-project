import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
const proxy = { '/api': { target: process.env.VITE_BACKEND_URL || 'http://localhost:8080', changeOrigin: true, rewrite: path => path.replace(/^\/api/, '') } }
export default defineConfig({ plugins: [vue()], server: { proxy }, preview: { proxy } })
