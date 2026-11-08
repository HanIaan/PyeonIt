"use client";

import { Card } from "@/components/ui/card";
import { useRouter } from "next/navigation";
import { AuthGate, NicknameForm } from "@/components/auth/auth-ui";
import { useMe } from "@/components/auth/auth-provider";
import { signup } from "@/lib/auth-api";
import { redirectIfAuthRequired } from "@/lib/api";

export default function SignupPage() {
  const { refresh } = useMe();
  const router = useRouter();
  return (
    <section className="mx-auto max-w-md space-y-8 py-8 sm:py-16">
      <h1 className="text-display font-semibold tracking-tight">가입 마무리</h1>
      <AuthGate signup>
        <Card>
          <NicknameForm
            label="가입 완료"
            onSave={async (nickname) => {
              try {
                await signup(nickname);
                await refresh();
                router.push("/");
              } catch (e) {
                if (!redirectIfAuthRequired(e)) throw e;
              }
            }}
          />
        </Card>
      </AuthGate>
    </section>
  );
}
