"use client";

import { Suspense, useEffect, useState } from "react";
import { useSearchParams, useRouter } from "next/navigation";
import { useMe } from "./auth-provider";
import { Button, buttonClass as uiButtonClass } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { nicknamePattern } from "@/lib/auth-api";
import { apiErrorMessage } from "@/lib/api";

export const buttonClass = uiButtonClass("secondary");
export const primaryClass = uiButtonClass("primary");
export const inputClass =
  "min-h-11 w-full rounded-control border border-line-strong bg-surface px-3 py-2 text-body text-ink disabled:opacity-45";
export function Message({ children }: { children: React.ReactNode }) {
  return children ? (
    <p
      role="alert"
      className="rounded-control border border-warning/30 bg-warning/5 p-3 text-label leading-6 text-warning"
    >
      {children}
    </p>
  ) : null;
}
export function AuthGate({
  children,
  signup = false,
}: {
  children: React.ReactNode;
  signup?: boolean;
}) {
  const { state, error, refresh } = useMe();
  const router = useRouter();
  useEffect(() => {
    if (state === "anonymous") router.replace("/login");
    if (signup && state === "authenticated") router.replace("/");
  }, [state, signup, router]);
  if (state === "error")
    return (
      <div className="space-y-3">
        <Message>{error}</Message>
        <button className={buttonClass} onClick={() => void refresh()}>
          다시 시도
        </button>
      </div>
    );
  if ((signup && state !== "pending") || (!signup && state !== "authenticated"))
    return <p role="status">로그인 상태를 확인하고 있습니다.</p>;
  return children;
}
export function NicknameForm({
  initial = "",
  label,
  disabled = false,
  onSave,
}: {
  initial?: string;
  label: string;
  disabled?: boolean;
  onSave: (nickname: string) => Promise<void>;
}) {
  const [nickname, setNickname] = useState(initial);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  return (
    <form
      className="space-y-3"
      onSubmit={async (event) => {
        event.preventDefault();
        if (!nicknamePattern.test(nickname)) {
          setError("한글·영문·숫자 2~12자만 사용할 수 있습니다.");
          return;
        }
        setBusy(true);
        setError("");
        try {
          await onSave(nickname);
        } catch (e) {
          setError(apiErrorMessage(e));
        } finally {
          setBusy(false);
        }
      }}
    >
      <Input
        label="닉네임"
        name="nickname"
        value={nickname}
        onChange={(e) => setNickname(e.target.value)}
        minLength={2}
        maxLength={12}
        required
        disabled={disabled || busy}
        aria-describedby="nickname-help"
        autoComplete="nickname"
      />
      <p id="nickname-help" className="text-caption text-ink-2">
        한글·영문·숫자 2~12자
      </p>
      <Message>{error}</Message>
      <Button type="submit" disabled={disabled || busy}>
        {busy ? "처리 중…" : label}
      </Button>
    </form>
  );
}

function QueryMessageContent() {
  const params = useSearchParams();
  const code = params.get("error") ?? params.get("linkError");
  if (code) return <Message>{apiErrorMessage(code)}</Message>;
  return params.get("linked") ? (
    <p role="status">소셜 계정이 연결되었습니다.</p>
  ) : null;
}
export function QueryMessage() {
  return (
    <Suspense>
      <QueryMessageContent />
    </Suspense>
  );
}
