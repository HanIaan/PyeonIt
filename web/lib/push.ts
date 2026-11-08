import { ApiError } from "./api";
import {
  deletePushSubscription,
  getVapidPublicKey,
  PUSH_MOCK,
  savePushSubscription,
  type SubscriptionRequest,
} from "./push-api";

let mockSubscription: SubscriptionRequest | null = null;
export function pushUnavailableReason(): string {
  if (!window.isSecureContext)
    return "알림은 HTTPS 연결에서만 사용할 수 있습니다. 로컬 개발은 localhost를 이용해 주세요.";
  const ios =
    /iPad|iPhone|iPod/.test(navigator.userAgent) ||
    (navigator.platform === "MacIntel" && navigator.maxTouchPoints > 1);
  if (
    ios &&
    !window.matchMedia("(display-mode: standalone)").matches &&
    !(navigator as Navigator & { standalone?: boolean }).standalone
  )
    return "iOS Safari에서는 iOS 16.4 이상에서 홈 화면에 추가한 PWA로 실행해야 알림을 사용할 수 있습니다.";
  if (
    !("Notification" in window) ||
    !("serviceWorker" in navigator) ||
    !("PushManager" in window)
  )
    return "이 브라우저는 웹 푸시를 지원하지 않습니다. 지원 브라우저를 이용해 주세요.";
  return "";
}
async function registration() {
  if (!("serviceWorker" in navigator)) return undefined;
  const value = await navigator.serviceWorker.getRegistration("/");
  return value &&
    new URL(
      value.active?.scriptURL ??
        value.installing?.scriptURL ??
        value.waiting?.scriptURL ??
        "",
      location.href,
    ).pathname === "/sw.js"
    ? value
    : undefined;
}
export async function isPushSubscribed(): Promise<boolean> {
  if (PUSH_MOCK) return !!mockSubscription;
  return !!(await (await registration())?.pushManager.getSubscription());
}
function applicationKey(key: string): Uint8Array<ArrayBuffer> {
  const raw = atob(
    key.replace(/-/g, "+").replace(/_/g, "/") +
      "=".repeat((4 - (key.length % 4)) % 4),
  );
  return Uint8Array.from(raw, (char) => char.charCodeAt(0));
}
export function subscriptionRequest(
  subscription: PushSubscription,
): SubscriptionRequest {
  const value = subscription.toJSON();
  if (!value.endpoint || !value.keys?.p256dh || !value.keys?.auth)
    throw new Error("브라우저 구독 정보를 읽지 못했습니다.");
  return {
    endpoint: value.endpoint,
    keys: { p256dh: value.keys.p256dh, auth: value.keys.auth },
    userAgent: navigator.userAgent.slice(0, 500),
  };
}
export async function subscribePush(): Promise<void> {
  const reason = pushUnavailableReason();
  if (reason) throw new Error(reason);
  const permission = await Notification.requestPermission();
  if (permission !== "granted")
    throw new Error(
      permission === "denied"
        ? "알림 권한이 차단되었습니다. 브라우저 사이트 설정에서 알림을 허용해 주세요."
        : "알림 권한을 허용해야 알림을 켤 수 있습니다.",
    );
  const worker = await navigator.serviceWorker.register("/sw.js", {
    scope: "/",
    updateViaCache: "none",
  });
  await navigator.serviceWorker.ready;
  let publicKey: string;
  try {
    ({ publicKey } = await getVapidPublicKey());
  } catch (error) {
    if (error instanceof ApiError && error.status === 404)
      throw new Error("아직 알림을 쓸 수 없습니다. 나중에 다시 시도해 주세요.");
    throw error;
  }
  if (PUSH_MOCK) {
    const value = mockSubscription ?? {
      endpoint: `https://push.example.invalid/${crypto.randomUUID()}`,
      keys: { p256dh: publicKey, auth: "mock-only" },
      userAgent: navigator.userAgent.slice(0, 500),
    };
    await savePushSubscription(value);
    mockSubscription = value;
    return;
  }
  const existing = await worker.pushManager.getSubscription();
  const subscription =
    existing ??
    (await worker.pushManager.subscribe({
      userVisibleOnly: true,
      applicationServerKey: applicationKey(publicKey),
    }));
  try {
    await savePushSubscription(subscriptionRequest(subscription));
  } catch (error) {
    // 신규 구독 저장 실패 시 서버에 등록되지 않은 브라우저 구독을 남기지 않는다.
    if (!existing) await subscription.unsubscribe().catch(() => undefined);
    throw error;
  }
}
export async function unsubscribePush(): Promise<void> {
  if (PUSH_MOCK) {
    if (mockSubscription)
      await deletePushSubscription(mockSubscription.endpoint);
    mockSubscription = null;
    return;
  }
  const subscription = await (
    await registration()
  )?.pushManager.getSubscription();
  if (!subscription) return;
  // 서버 삭제가 실패하더라도 브라우저 구독 해제는 시도한다.
  try {
    await deletePushSubscription(subscription.endpoint);
  } finally {
    if (!(await subscription.unsubscribe()))
      throw new Error(
        "브라우저 구독을 해제하지 못했습니다. 다시 시도해 주세요.",
      );
  }
}
export async function unsubscribePushBeforeLogout(): Promise<void> {
  try {
    await unsubscribePush();
  } catch {
    console.warn("푸시 구독 정리에 실패했습니다. 로그아웃은 계속합니다.");
  }
}
