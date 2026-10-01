/** Public configuration, inlined at build time (see .env.example and infra/11-deploy-web.sh). */
export const config = {
  apiUrl: (process.env.NEXT_PUBLIC_ESCUDO_API_URL ?? "http://127.0.0.1:8080").replace(/\/$/, ""),
  firebase: {
    apiKey: process.env.NEXT_PUBLIC_FIREBASE_API_KEY ?? "",
    authDomain: process.env.NEXT_PUBLIC_FIREBASE_AUTH_DOMAIN ?? "ai-experiments-487722.firebaseapp.com",
    projectId: process.env.NEXT_PUBLIC_FIREBASE_PROJECT_ID ?? "ai-experiments-487722",
  },
};
