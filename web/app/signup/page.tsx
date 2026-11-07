"use client";

import { useRouter } from "next/navigation";
import { AuthGate, NicknameForm } from "@/components/auth/auth-ui";
import { useMe } from "@/components/auth/auth-provider";
import { signup } from "@/lib/auth-api";
import { redirectIfAuthRequired } from "@/lib/api";

export default function SignupPage() {
  const { refresh } = useMe();
  const router = useRouter();
  return (
    <section className="mx-auto max-w-md space-y-6 py-6 sm:py-12">
      <h1 className="text-3xl font-bold">가입 마무리</h1>
      <p className="leading-7 opacity-70">
        소셜 인증이 완료되었어요. 커뮤니티에서 사용할 닉네임을 정해 주세요.
      </p>
      <AuthGate signup>
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
      </AuthGate>
    </section>
  );
}
