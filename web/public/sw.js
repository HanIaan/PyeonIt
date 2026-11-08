/* 알림 전용 service worker. 오프라인 응답/캐시를 만들지 않는다. */
function safePath(value) {
  if (
    typeof value !== "string" ||
    !value.startsWith("/") ||
    value.startsWith("//")
  )
    return "/";
  try {
    const url = new URL(value, self.location.origin);
    return url.origin === self.location.origin
      ? url.pathname + url.search + url.hash
      : "/";
  } catch {
    return "/";
  }
}
self.addEventListener("push", (event) => {
  let payload = {};
  try {
    payload = event.data?.json() ?? {};
  } catch {
    /* 기본 알림 */
  }
  if (!payload || typeof payload !== "object") payload = {};
  event.waitUntil(
    self.registration.showNotification(
      typeof payload.title === "string" && payload.title
        ? payload.title
        : "PyeonIt 알림",
      {
        body:
          typeof payload.body === "string" && payload.body
            ? payload.body
            : "새로운 소식이 있습니다. PyeonIt에서 확인해 주세요.",
        icon: "/icons/pyeonit-192.png",
        data: { url: safePath(payload.url) },
      },
    ),
  );
});
self.addEventListener("notificationclick", (event) => {
  event.notification.close();
  const url = new URL(
    safePath(event.notification.data?.url),
    self.location.origin,
  ).href;
  event.waitUntil(
    (async () => {
      const windows = await self.clients.matchAll({
        type: "window",
        includeUncontrolled: true,
      });
      const target =
        windows.find((client) => client.url === url) ??
        windows.find(
          (client) => new URL(client.url).origin === self.location.origin,
        );
      if (target) {
        try {
          await target.focus();
          if (await target.navigate(url)) return;
        } catch {
          /* 창이 닫혔으면 새 창 */
        }
      }
      await self.clients.openWindow(url);
    })(),
  );
});
self.addEventListener("pushsubscriptionchange", (event) => {
  event.waitUntil(
    (async () => {
      try {
        const options = event.oldSubscription?.options;
        const subscription =
          event.newSubscription ??
          (options?.applicationServerKey
            ? await self.registration.pushManager.subscribe({
                userVisibleOnly: true,
                applicationServerKey: options.applicationServerKey,
              })
            : null);
        if (!subscription) return;
        // API 오리진과 CSRF 처리는 로그인한 설정 화면의 공통 API 모듈에 맡긴다.
        const windows = await self.clients.matchAll({
          type: "window",
          includeUncontrolled: true,
        });
        for (const client of windows)
          client.postMessage({ type: "PYEONIT_PUSH_CHANGED" });
      } catch {
        /* 지원 여부가 브라우저마다 다르며 설정 화면에서 다시 켤 수 있다. */
      }
    })(),
  );
});
