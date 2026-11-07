"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { useMe } from "@/components/auth/auth-provider";
import {
  buttonClass,
  inputClass,
  Message,
  primaryClass,
  QueryMessage,
} from "@/components/auth/auth-ui";
import { apiErrorMessage, type Schemas } from "@/lib/api";
import {
  AUTH_MOCK,
  ENABLE_DEV_LOGIN,
  devLogin,
  startLogin,
  startMockSignup,
} from "@/lib/auth-api";

export default function LoginPage() {
  const { state, refresh } = useMe();
  const router = useRouter();
  const [error, setError] = useState("");
  const [nickname, setNickname] = useState("테스트회원");
  const [role, setRole] = useState<Schemas["DevLoginRequest"]["role"]>("USER");
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (state === "authenticated") router.replace("/me");
  }, [state, router]);
  return (
    <section className="mx-auto max-w-md space-y-6 py-6 sm:py-12">
      <div className="space-y-2">
        <h1 className="text-3xl font-bold">로그인</h1>
        <p className="leading-7 opacity-70">
          편입 준비의 다음 단계를 PyeonIt과 함께하세요.
          <br />
          처음이라면 소셜 로그인 후 닉네임을 설정합니다.
        </p>
      </div>
      <QueryMessage />
      <Message>{error}</Message>
      <div className="grid gap-3">
        <button
          className={`${buttonClass} border-yellow-400 bg-yellow-300 text-black`}
          onClick={() => startLogin("KAKAO")}
        >
          카카오로 로그인
        </button>
        <button className={buttonClass} onClick={() => startLogin("GOOGLE")}>
          구글로 로그인
        </button>
      </div>
      {AUTH_MOCK && (
        <div className="space-y-2 rounded-2xl border border-dashed border-current/25 p-4">
          <p className="text-sm opacity-70">
            목 모드 · 새로고침하면 테스트 세션이 초기화됩니다.
          </p>
          <button
            className={buttonClass}
            onClick={async () => {
              startMockSignup("KAKAO");
              await refresh();
              router.push("/signup");
            }}
          >
            목 가입 대기 세션 시작
          </button>
        </div>
      )}
      {ENABLE_DEV_LOGIN && (
        <form
          className="space-y-3 rounded-2xl border border-current/20 p-4"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError("");
            try {
              await devLogin({ nickname, role });
              await refresh();
              router.push("/");
            } catch (err) {
              setError(apiErrorMessage(err));
            } finally {
              setBusy(false);
            }
          }}
        >
          <h2 className="font-semibold">개발 전용 테스트 로그인</h2>
          <label className="block space-y-1">
            <span>닉네임</span>
            <input
              className={inputClass}
              value={nickname}
              onChange={(e) => setNickname(e.target.value)}
              minLength={2}
              maxLength={12}
              required
            />
          </label>
          <label className="block space-y-1">
            <span>권한</span>
            <select
              className={inputClass}
              value={role}
              onChange={(e) => setRole(e.target.value as Schemas["Me"]["role"])}
            >
              <option value="USER">일반 회원 (USER)</option>
              <option value="ADMIN">관리자 (ADMIN)</option>
            </select>
          </label>
          <button disabled={busy} className={primaryClass}>
            {busy ? "처리 중…" : "테스트 로그인"}
          </button>
        </form>
      )}
    </section>
  );
}
