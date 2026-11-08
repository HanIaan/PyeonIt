import { api, type Schemas } from "./api";
import { getMe } from "./auth-api";

export const PUSH_MOCK = process.env.NEXT_PUBLIC_API_MOCK === "true";
export type NotificationSettings = Schemas["NotificationSettings"];
export type SubscriptionRequest = Schemas["PushSubscriptionRequest"];
const settings = new Map<number, NotificationSettings>();
const subscriptions = new Map<string, number>();

export async function getVapidPublicKey(): Promise<Schemas["VapidPublicKey"]> {
  if (PUSH_MOCK) return { publicKey: "B" + "A".repeat(86) };
  return api("/api/push/vapid-public-key", { cache: "no-store" });
}
export async function savePushSubscription(
  value: SubscriptionRequest,
): Promise<void> {
  if (PUSH_MOCK) {
    subscriptions.set(value.endpoint, (await getMe()).id);
    return;
  }
  return api("/api/me/push-subscriptions", { method: "PUT", json: value });
}
export async function deletePushSubscription(endpoint: string): Promise<void> {
  if (PUSH_MOCK) {
    if (subscriptions.get(endpoint) === (await getMe()).id)
      subscriptions.delete(endpoint);
    return;
  }
  return api(
    `/api/me/push-subscriptions?${new URLSearchParams({ endpoint })}`,
    { method: "DELETE" },
  );
}
export async function getNotificationSettings(): Promise<NotificationSettings> {
  if (PUSH_MOCK)
    return {
      ...(settings.get((await getMe()).id) ?? {
        newNoticeEnabled: true,
        scheduleReminderEnabled: true,
      }),
    };
  return api("/api/me/notification-settings", { cache: "no-store" });
}
export async function saveNotificationSettings(
  value: NotificationSettings,
): Promise<NotificationSettings> {
  if (PUSH_MOCK) {
    settings.set((await getMe()).id, { ...value });
    return { ...value };
  }
  return api("/api/me/notification-settings", { method: "PUT", json: value });
}
