import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// 后端地址（Tomcat 默认 8080，context-path 为 /chorus）
// docker-compose 里用 VITE_BACKEND_URL 指向 compose 网络内的 server 容器；
// 本地开发不设置时默认 http://localhost:8080。
const BACKEND_PORT = process.env.VITE_BACKEND_PORT || '8080';
const BACKEND = process.env.VITE_BACKEND_URL || `http://localhost:${BACKEND_PORT}`;
const BACKEND_WS = BACKEND.replace(/^http/, 'ws');

export default defineConfig({
  plugins: [react()],
  server: {
    host: true, // 允许容器外访问（docker 端口映射需要）
    port: 3000,
    open: process.env.VITE_NO_OPEN !== '1',
    proxy: {
      // REST API: /api/*  →  /chorus/api/*
      '/api': {
        target: BACKEND,
        changeOrigin: true,
        rewrite: (path) => `/chorus${path}`,
      },
      // WebSocket Gateway: /ws → wss://.../chorus/ws
      '/ws': {
        target: BACKEND_WS,
        ws: true,
        changeOrigin: true,
        rewrite: (path) => `/chorus${path}`,
      },
      // 附件静态文件: /uploads/* → /chorus/uploads/*
      '/uploads': {
        target: BACKEND,
        changeOrigin: true,
        rewrite: (path) => `/chorus${path}`,
      },
    },
  },
});
