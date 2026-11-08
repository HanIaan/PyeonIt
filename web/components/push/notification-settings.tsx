"use client";

import { Card } from "@/components/ui/card";
import { useCallback, useEffect, useState } from "react";
import { buttonClass, Message } from "@/components/auth/auth-ui";
import { ApiError, apiErrorMessage, redirectIfAuthRequired } from "@/lib/api";
import {
  getNotificationSettings,
  saveNotificationSettings,
  savePushSubscription,
  PUSH_MOCK,
  type NotificationSettings,
} from "@/lib/push-api";
import {
  isPushSubscribed,
  pushUnavailableReason,
  subscribePush,
  unsubscribePush,
  subscriptionRequest,
} from "@/lib/push";

export function NotificationSettingsPanel() {
  const [settings, setSettings] = useState<NotificationSettings | null>(null);
  const [subscribed, setSubscribed] = useState<boolean | null>(null);
  const [permission, setPermission] = useState<string>("확인 중");
  const [unavailable, setUnavailable] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const report = useCallback((value: unknown) => {
    if (!redirectIfAuthRequired(value))
      setError(
        value instanceof ApiError
          ? apiErrorMessage(value)
          : value instanceof Error
            ? value.message
            : apiErrorMessage(value),
      );
  }, []);
  const refreshBrowser = useCallback(async () => {
    const currentSubscription = await isPushSubscribed();
    setUnavailable(pushUnavailableReason());
    setPermission(
      "Notification" in window ? Notification.permission : "unsupported",
    );
    setSubscribed(currentSubscription);
  }, []);
  useEffect(() => {
    let active = true;
    getNotificationSettings().then(
      (value) => {
        if (active) setSettings(value);
      },
      (value) => {
        if (active) report(value);
      },
    );
    void isPushSubscribed()
      .then((current) => {
        if (!active) return;
        setSubscribed(current);
        setUnavailable(pushUnavailableReason());
        setPermission(
          "Notification" in window ? Notification.permission : "unsupported",
        );
      })
      .catch(report);
    const onChange = () => {
      void refreshBrowser().catch(report);
    };
    const onMessage = (event: MessageEvent) => {
      if (event.data?.type !== "PYEONIT_PUSH_CHANGED" || PUSH_MOCK) return;
      void (async () => {
        const worker = await navigator.serviceWorker.getRegistration("/");
        const subscription = await worker?.pushManager.getSubscription();
        if (subscription)
          await savePushSubscription(subscriptionRequest(subscription));
        await refreshBrowser();
      })().catch(report);
    };
    window.addEventListener("focus", onChange);
    navigator.serviceWorker?.addEventListener("message", onMessage);
    return () => {
      active = false;
      window.removeEventListener("focus", onChange);
      navigator.serviceWorker?.removeEventListener("message", onMessage);
    };
  }, [refreshBrowser, report]);
  async function action(work: () => Promise<void>, message: string) {
    setBusy(true);
    setError("");
    setNotice("");
    try {
      await work();
      setNotice(message);
    } catch (value) {
      report(value);
    } finally {
      await refreshBrowser().catch(report);
      setBusy(false);
    }
  }
  return (
    <div className="space-y-6" aria-busy={busy}>
      <Message>{error}</Message>
      {notice && (
        <p role="status" className="rounded-card bg-success/5 text-success p-3 text-sm">
          {notice}
        </p>
      )}
      {PUSH_MOCK && (
        <p className="rounded-card bg-paper text-ink-2 p-3 text-sm leading-6">
          목 모드: 권한 요청과 서비스 워커 등록은 실제로 동작하지만 구독·설정은
          메모리에만 저장됩니다. 실제 푸시는 발송되지 않으며 새로고침하면 목
          데이터가 초기화됩니다.
        </p>
      )}
      <Card className="space-y-4 p-5">
        <h2 className="text-xl font-semibold">현재 브라우저 푸시</h2>
        <p role="status">
          구독 상태:{" "}
          {subscribed === null ? "확인 중" : subscribed ? "켜짐" : "꺼짐"}
        </p>
        <p className="text-sm">
          브라우저 권한:{" "}
          {(
            {
              granted: "허용",
              denied: "차단",
              default: "미선택",
              unsupported: "미지원",
            } as Record<string, string>
          )[permission] ?? permission}
        </p>
        <p className="text-sm leading-6 text-ink-2">
          현재 브라우저에 적용됩니다.
        </p>
        {unavailable && <Message>{unavailable}</Message>}
        {permission === "denied" && (
          <Message>
            알림 권한이 차단되었습니다. 브라우저 사이트 설정에서 알림을 허용해
            주세요.
          </Message>
        )}
        <div className="flex flex-wrap gap-3">
          <button
            className={buttonClass}
            disabled={
              busy ||
              subscribed === null ||
              !!unavailable ||
              permission === "denied"
            }
            onClick={() =>
              void action(subscribePush, "현재 브라우저의 알림을 켰습니다.")
            }
          >
            {subscribed ? "구독 다시 연결" : "알림 켜기"}
          </button>
          <button
            className={buttonClass}
            disabled={busy || !subscribed}
            onClick={() =>
              void action(unsubscribePush, "현재 브라우저의 알림을 껐습니다.")
            }
          >
            알림 끄기
          </button>
        </div>
      </Card>
      <Card className="space-y-4 p-5">
        <h2 className="text-xl font-semibold">알림 종류</h2>
        {settings ? (
          <div className="divide-y divide-line">
            {(
              [
                [
                  "newNoticeEnabled",
                  "새 공고",
                  "관심학교 새 공고",
                ],
                [
                  "scheduleReminderEnabled",
                  "일정 D-1",
                  "관심학교 일정 전날 · 한국 시간",
                ],
              ] as const
            ).map(([key, label, description]) => (
              <label
                key={key}
                className="flex min-h-16 cursor-pointer items-center justify-between gap-4 py-3"
              >
                <span>
                  <span className="block font-semibold">{label}</span>
                  <span className="mt-1 block text-sm leading-6 text-ink-2">
                    {description}
                  </span>
                </span>
                <input
                  type="checkbox"
                  role="switch"
                  className="h-6 w-6 shrink-0 accent-ink"
                  checked={settings[key]}
                  disabled={busy}
                  onChange={(event) => {
                    const next = { ...settings, [key]: event.target.checked };
                    void action(async () => {
                      setSettings(await saveNotificationSettings(next));
                    }, "알림 종류를 저장했습니다.");
                  }}
                />
              </label>
            ))}
          </div>
        ) : (
          <div className="space-y-3">
            <p role="status">알림 설정을 불러오는 중입니다.</p>
            <button
              className={buttonClass}
              disabled={busy}
              onClick={() =>
                void action(async () => {
                  setSettings(await getNotificationSettings());
                }, "설정을 불러왔습니다.")
              }
            >
              다시 불러오기
            </button>
          </div>
        )}
      </Card>
      <p className="text-sm leading-6 text-ink-2">
        iOS Safari는 iOS 16.4 이상에서 공유 → 홈 화면에 추가 후 PWA로 실행해
        주세요. 알림은 HTTPS에서 지원하며 localhost는 예외입니다.
      </p>
    </div>
  );
}
