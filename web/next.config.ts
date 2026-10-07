import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone", // minimal server bundle for the Cloud Run image
  poweredByHeader: false,
  // The projector view is /presentador; old links to /presenter keep working (query string included).
  async redirects() {
    return [{ source: "/presenter", destination: "/presentador", permanent: true }];
  },
};

export default nextConfig;
