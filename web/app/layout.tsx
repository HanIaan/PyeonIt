import type { Metadata } from "next";
import { AuthProvider } from "@/components/auth/auth-provider";
import { Navigation } from "@/components/layout/navigation";
import "./globals.css";

export const metadata: Metadata = {
  title: "PyeonIt",
  description: "편입 준비생 커뮤니티",
};

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html lang="ko" className="h-full antialiased">
      <body className="min-h-full flex flex-col">
        <AuthProvider>
          <Navigation />
          <main className="mx-auto w-full max-w-5xl flex-1 px-4 py-6">
            {children}
          </main>
        </AuthProvider>
      </body>
    </html>
  );
}
