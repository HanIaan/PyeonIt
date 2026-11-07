"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  isMock,
  listCalendarEvents,
  listUniversities,
  type Schemas,
} from "@/lib/universities-api";
import {
  eventOnDay,
  koreanDay,
  scheduleColors,
  scheduleLabels,
  scheduleTime,
} from "@/lib/universities-display";
import {
  buttonClass,
  cardClass,
  inputClass,
  PageIntro,
  ResourceMessage,
  useUniversityResource,
} from "@/components/universities/shared";

function monthString(date: Date) {
  return `${date.getUTCFullYear()}-${String(date.getUTCMonth() + 1).padStart(2, "0")}`;
}
function dayString(month: string, day: number) {
  return `${month}-${String(day).padStart(2, "0")}`;
}

export default function CalendarView() {
  const params = useSearchParams();
  const router = useRouter();
  const rawMonth = params.get("month") ?? "";
  const month = /^(20\d{2})-(0[1-9]|1[0-2])$/.test(rawMonth)
    ? rawMonth
    : koreanDay(new Date()).slice(0, 7);
  const [year, monthNumber] = month.split("-").map(Number);
  const first = new Date(Date.UTC(year, monthNumber - 1, 1));
  const nextMonth = monthString(new Date(Date.UTC(year, monthNumber, 1)));
  const previousMonth = monthString(
    new Date(Date.UTC(year, monthNumber - 2, 1)),
  );
  const days = new Date(Date.UTC(year, monthNumber, 0)).getUTCDate();
  const rawSchool = params.get("universityId");
  const school =
    rawSchool &&
    /^\d+$/.test(rawSchool) &&
    Number.isSafeInteger(Number(rawSchool)) &&
    Number(rawSchool) > 0
      ? Number(rawSchool)
      : undefined;
  const schools = useUniversityResource("calendar-schools", () =>
    listUniversities(),
  );
  // 한국 시간 월초~다음 월초 [from, to): 항상 28~31일로 API의 최대 100일 제한을 지킨다.
  const events = useUniversityResource(`calendar:${month}:${school}`, () =>
    listCalendarEvents(
      `${month}-01T00:00:00+09:00`,
      `${nextMonth}-01T00:00:00+09:00`,
      school,
    ),
  );
  const [selected, setSelected] = useState("");
  const today = koreanDay(new Date());
  const selectedDay = selected.startsWith(`${month}-`)
    ? selected
    : today.startsWith(`${month}-`)
      ? today
      : `${month}-01`;
  const dayEvents =
    events.data?.filter((event) => eventOnDay(event, selectedDay)) ?? [];
  function change(key: string, value: string) {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    router.push(`/calendar?${next}`);
  }
  const cells = Array.from(
    { length: Math.ceil((first.getUTCDay() + days) / 7) * 7 },
    (_, index) => {
      const day = index - first.getUTCDay() + 1;
      return day > 0 && day <= days ? day : null;
    },
  );
  return (
    <>
      <PageIntro
        title="편입 일정"
        description="원서접수부터 최종 발표까지, 월별 일정을 확인하세요. 모든 시각은 한국 시간입니다."
      />
      <div className="mb-5 max-w-sm">
        <label className="grid gap-1.5 text-sm">
          학교
          <select
            className={inputClass}
            value={school ?? ""}
            onChange={(e) => change("universityId", e.target.value)}
          >
            <option value="">전체 학교</option>
            {school && !schools.data?.some((u) => u.id === school) && (
              <option value={school}>학교 #{school}</option>
            )}
            {schools.data?.map((u) => (
              <option key={u.id} value={u.id}>
                {u.name} {u.campus}
              </option>
            ))}
          </select>
        </label>
        <ResourceMessage {...schools} />
      </div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <button
          className={buttonClass}
          aria-label="이전 달"
          disabled={previousMonth < "2000-01"}
          onClick={() => change("month", previousMonth)}
        >
          ← 이전
        </button>
        <h2 className="text-xl font-bold">
          {year}년 {monthNumber}월
        </h2>
        <button
          className={buttonClass}
          aria-label="다음 달"
          disabled={nextMonth > "2099-12"}
          onClick={() => change("month", nextMonth)}
        >
          다음 →
        </button>
      </div>
      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
        <button
          className={buttonClass}
          onClick={() => change("month", today.slice(0, 7))}
        >
          이번 달
        </button>
        {isMock && (
          <button
            className={buttonClass}
            onClick={() => change("month", "2026-01")}
          >
            2026년 1월 샘플 보기
          </button>
        )}
      </div>
      <ResourceMessage {...events} />
      <div
        className="overflow-hidden rounded-xl border border-slate-200 dark:border-slate-700"
        aria-label={`${year}년 ${monthNumber}월 달력`}
        aria-busy={!events.done}
      >
        <div className="grid grid-cols-7 bg-slate-100 text-center text-xs font-semibold dark:bg-slate-800">
          {["일", "월", "화", "수", "목", "금", "토"].map((day) => (
            <div key={day} className="py-3">
              {day}
            </div>
          ))}
        </div>
        <div className="grid grid-cols-7">
          {cells.map((day, index) => {
            if (!day)
              return (
                <div
                  key={`empty-${index}`}
                  className="min-h-20 border-t border-slate-200 bg-slate-50/50 dark:border-slate-700 dark:bg-slate-900/50 sm:min-h-28"
                />
              );
            const date = dayString(month, day);
            const items =
              events.data?.filter((event) => eventOnDay(event, date)) ?? [];
            const types = [...new Set(items.map((event) => event.type))];
            return (
              <button
                key={date}
                aria-label={`${monthNumber}월 ${day}일, 일정 ${items.length}개`}
                aria-pressed={selectedDay === date}
                onClick={() => setSelected(date)}
                className={`min-h-20 min-w-0 border-t border-slate-200 p-1 text-left align-top hover:bg-blue-50 focus-visible:z-10 focus-visible:outline-2 focus-visible:outline-blue-600 dark:border-slate-700 dark:hover:bg-slate-800 sm:min-h-28 sm:p-2 ${selectedDay === date ? "bg-blue-50 ring-2 ring-inset ring-blue-600 dark:bg-slate-800" : ""}`}
              >
                <span
                  className={`inline-flex h-6 min-w-6 items-center justify-center rounded-full text-sm ${today === date ? "bg-blue-600 text-white" : ""}`}
                >
                  {day}
                </span>
                <div className="mt-1 flex flex-wrap gap-1 sm:hidden">
                  {types.map((type) => (
                    <span
                      key={type}
                      title={scheduleLabels[type]}
                      className={`h-2 w-2 rounded-full ${scheduleColors[type]}`}
                    />
                  ))}
                  {items.length > 0 && (
                    <span className="w-full text-[10px]">{items.length}건</span>
                  )}
                </div>
                <div className="mt-1 hidden space-y-1 sm:block">
                  {items.slice(0, 2).map((event) => (
                    <span
                      key={event.id}
                      className={`block truncate rounded px-1 py-0.5 text-[10px] ${scheduleColors[event.type]}`}
                    >
                      {scheduleLabels[event.type]} · {event.university.name}
                    </span>
                  ))}
                  {items.length > 2 && (
                    <span className="block text-xs">+{items.length - 2}건</span>
                  )}
                </div>
              </button>
            );
          })}
        </div>
      </div>
      <div className="my-4 flex flex-wrap gap-2" aria-label="일정 종류 범례">
        {(
          Object.entries(scheduleLabels) as [Schemas["ScheduleType"], string][]
        ).map(([type, label]) => (
          <span
            key={type}
            className={`rounded-md px-2 py-1 text-xs ${scheduleColors[type]}`}
          >
            {label}
          </span>
        ))}
      </div>
      <section className={`${cardClass} mt-6`} aria-live="polite">
        <h2 className="text-lg font-semibold">{selectedDay} 일정</h2>
        {events.done && !events.error && !dayEvents.length && (
          <p className="mt-4 text-sm text-slate-500">이날의 일정이 없습니다.</p>
        )}
        <ul className="divide-y divide-slate-200 dark:divide-slate-700">
          {dayEvents.map((event) => (
            <li key={event.id} className="py-4">
              <span
                className={`inline-block rounded-md px-2 py-1 text-xs font-semibold ${scheduleColors[event.type]}`}
              >
                {scheduleLabels[event.type]}
              </span>
              <h3 className="mt-2 font-semibold">
                <Link
                  href={`/universities/${event.university.id}?academicYear=${event.academicYear}`}
                  className="hover:underline"
                >
                  {event.university.name} · {event.rawLabel}
                </Link>
              </h3>
              <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
                {scheduleTime(event)}
              </p>
              {event.note && <p className="mt-1 text-sm">{event.note}</p>}
            </li>
          ))}
        </ul>
      </section>
    </>
  );
}
