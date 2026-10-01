import type { NextConfig } from "next";

const nextConfig: NextConfig = {
  output: "standalone", // minimal server bundle for the Cloud Run image
  poweredByHeader: false,
};

export default nextConfig;
