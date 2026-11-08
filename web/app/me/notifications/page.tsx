"use client";

import Link from "next/link";
import { useMe } from "@/components/auth/auth-provider";
import { Message, buttonClass } from "@/components/auth/auth-ui";
import { NotificationSettingsPanel } from "@/components/push/notification-settings";

export default function NotificationsPage() {
  const { state, error, refresh } = useMe();
  return (
    <section className="mx-auto max-w-xl space-y-6">
      <Link href="/me" className="text-sm underline underline-offset-4">
        내 정보
      </Link>
      <h1 className="text-3xl font-bold">알림 설정</h1>
      {state === "loading" && (
        <p role="status">로그인 상태를 확인하고 있습니다.</p>
      )}
      {state === "anonymous" && (
        <p>
          로그인이 필요합니다.{" "}
          <Link href="/login" className="underline">
            로그인하기
          </Link>
        </p>
      )}
      {state === "pending" && (
        <p>
          가입을 완료해 주세요.{" "}
          <Link href="/signup" className="underline">
            가입하기
          </Link>
        </p>
      )}
      {state === "error" && (
        <div className="space-y-3">
          <Message>{error}</Message>
          <button className={buttonClass} onClick={() => void refresh()}>
            다시 시도
          </button>
        </div>
      )}
      {state === "authenticated" && <NotificationSettingsPanel />}
    </section>
  );
}
