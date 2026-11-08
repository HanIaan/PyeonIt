"use client";

import Image from "next/image";
// 공식 리소스: https://developers.kakao.com/tool/resource/login (원본 색상·심볼 유지)
import kakaoLogin from "@/components/auth/kakao-login.svg";
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
  const [suspended, setSuspended] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (state === "authenticated") router.replace("/me");
  }, [state, router]);
  return (
    <section className="mx-auto max-w-md space-y-8 py-8 sm:py-16">
      <div className="space-y-2">
        <h1 className="text-display font-semibold tracking-tight">로그인</h1>
      </div>
      <QueryMessage />
      <Message>{error}</Message>
      <button
        aria-label="카카오로 로그인"
        className="flex min-h-11 w-full justify-center rounded-control transition-transform active:scale-[0.98]"
        onClick={() => startLogin("KAKAO")}
      >
        <Image src={kakaoLogin} alt="" width={224} height={46} className="h-auto w-full" priority />
      </button>
      {AUTH_MOCK && (
        <div className="space-y-2 rounded-card border border-line p-4">
          <p className="text-label text-ink-2">
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
          className="space-y-4 rounded-card border border-line bg-surface p-5"
          onSubmit={async (e) => {
            e.preventDefault();
            setBusy(true);
            setError("");
            try {
              await devLogin({ nickname, role, suspended });
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
          <label className="flex min-h-11 items-center gap-3">
            <input
              type="checkbox"
              name="suspended"
              checked={suspended}
              onChange={(e) => setSuspended(e.target.checked)}
              className="h-5 w-5 accent-ink"
            />
            <span>정지 회원으로 로그인</span>
          </label>
          <button disabled={busy} className={primaryClass}>
            {busy ? "처리 중…" : "테스트 로그인"}
          </button>
        </form>
      )}
    </section>
  );
}
