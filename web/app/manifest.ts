import type { MetadataRoute } from "next";

export default function manifest(): MetadataRoute.Manifest {
  return {
    name: "PyeonIt(편입 커뮤니티)",
    short_name: "PyeonIt(편입 커뮤니티)",
    lang: "ko",
    start_url: "/",
    scope: "/",
    display: "standalone",
    theme_color: "#2563eb",
    background_color: "#ffffff",
    icons: [192, 512].flatMap((size) => [
      {
        src: `/icons/pyeonit-${size}.png`,
        sizes: `${size}x${size}`,
        type: "image/png",
        purpose: "any" as const,
      },
      {
        src: `/icons/pyeonit-${size}.png`,
        sizes: `${size}x${size}`,
        type: "image/png",
        purpose: "maskable" as const,
      },
    ]),
  };
}
