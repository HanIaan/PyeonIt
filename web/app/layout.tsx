import type { Metadata, Viewport } from "next";
import { AuthProvider } from "@/components/auth/auth-provider";
import { Navigation } from "@/components/layout/navigation";
import "./globals.css";

export const metadata: Metadata = {
  title: "PyeonIt",
  description: "편입 준비생 커뮤니티",
};

// safe-area 변수(env(safe-area-inset-*))가 동작하려면 viewport-fit=cover 필요
export const viewport: Viewport = { viewportFit: "cover" };

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="min-h-full flex flex-col">
        <AuthProvider>
          <Navigation />
          <main className="mx-auto w-full max-w-5xl flex-1 px-4 pt-6 pb-[calc(var(--above-bottom-nav)+1.5rem)] md:pb-6">
            {children}
          </main>
        </AuthProvider>
      </body>
    </html>
  );
}
