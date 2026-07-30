// Nuxt 3 SPA/SSG 설정.
// - ssr:false + nuxi generate → 정적 산출물(.output/public)을 nginx가 서빙. Node 서버 불필요.
// - runtimeConfig.public.apiBase: 빈값(운영) → 상대경로 /api. 빌드 인자 NUXT_PUBLIC_API_BASE로 주입.
// - nitro.devProxy: 로컬 dev에서 /api → localhost:8082(bootRun/Vite 대체).
export default defineNuxtConfig({
  ssr: false,
  modules: ["@pinia/nuxt"],
  devServer: {
    port: 5174
  },
  runtimeConfig: {
    public: {
      apiBase: ""
    }
  },
  nitro: {
    devProxy: {
      "/api": {
        target: "http://localhost:8082",
        changeOrigin: true
      }
    }
  },
  css: ["~/assets/css/main.css"],
  app: {
    head: {
      title: "LLM Starter",
      htmlAttrs: { lang: "ko" },
      meta: [
        { charset: "utf-8" },
        { name: "viewport", content: "width=device-width, initial-scale=1.0" }
      ]
    }
  },
  typescript: {
    strict: true
  },
  compatibilityDate: "2025-01-01"
});
