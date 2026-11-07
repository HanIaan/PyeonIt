import type { Schemas } from "./universities-api";

export const regions: Record<Schemas["Region"], string> = {
  SEOUL: "서울",
  GYEONGGI: "경기",
  INCHEON: "인천",
};
export const scheduleLabels: Record<Schemas["ScheduleType"], string> = {
  APPLICATION: "원서접수",
  DOCUMENT_SUBMISSION: "서류제출",
  EXAM: "필기고사",
  INTERVIEW: "면접",
  RESULT_FIRST_STAGE: "1단계 발표",
  RESULT_FINAL: "최종 발표",
  REGISTRATION: "등록",
  ADDITIONAL: "추가모집",
  OTHER: "기타",
};
export const scheduleColors: Record<Schemas["ScheduleType"], string> = {
  APPLICATION: "bg-blue-100 text-blue-900",
  DOCUMENT_SUBMISSION: "bg-cyan-100 text-cyan-900",
  EXAM: "bg-violet-100 text-violet-900",
  INTERVIEW: "bg-amber-100 text-amber-900",
  RESULT_FIRST_STAGE: "bg-pink-100 text-pink-900",
  RESULT_FINAL: "bg-rose-100 text-rose-900",
  REGISTRATION: "bg-emerald-100 text-emerald-900",
  ADDITIONAL: "bg-orange-100 text-orange-900",
  OTHER: "bg-slate-100 text-slate-900",
};
export function koreanDate(value: string, allDay = false): string {
  return new Intl.DateTimeFormat("ko-KR", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "short",
    day: "numeric",
    ...(!allDay
      ? ({ hour: "2-digit", minute: "2-digit", hour12: false } as const)
      : {}),
  }).format(new Date(value));
}
export function koreanDay(value: string | Date): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: "Asia/Seoul",
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
  }).formatToParts(new Date(value));
  return ["year", "month", "day"]
    .map((key) => parts.find((p) => p.type === key)!.value)
    .join("-");
}
export function scheduleTime(s: Schemas["Schedule"]): string {
  return `${koreanDate(s.startsAt, s.allDay)}${s.endsAt ? ` ~ ${koreanDate(s.endsAt, s.allDay)}` : ""}${s.allDay ? " · 종일" : " (한국 시간)"}`;
}
export function eventOnDay(s: Schemas["Schedule"], day: string): boolean {
  return (
    koreanDay(s.startsAt) <= day && koreanDay(s.endsAt ?? s.startsAt) >= day
  );
}
export function quotaColumn(q: Schemas["Quota"]): string {
  return `${q.track?.id ?? "raw"}:${q.rawTrackName}`;
}
