import { defineConfig, loadEnv } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// A production page that silently points at http://localhost:3000 shows a login box that can never
// work, so the build says out loud which address it compiled in instead of failing quietly later.
export default defineConfig(({ mode }) => {
  const target = loadEnv(mode, process.cwd(), "VITE_").VITE_SERVER_URL;
  if (mode === "production") {
    if (!target)
      console.warn("[dashboard] VITE_SERVER_URL is not set. This build falls back to http://localhost:3000 and cannot reach a hosted backend.");
    else if (target.startsWith("http://") && !/^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(:|\/)/.test(target))
      console.warn(`[dashboard] VITE_SERVER_URL is plain HTTP (${target}). A dashboard served over HTTPS will have its requests blocked by the browser.`);
  }
  return {
    plugins: [react(), tailwindcss()],
    server: { port: 5173, strictPort: true },
  };
});
