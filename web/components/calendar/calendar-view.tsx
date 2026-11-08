"use client";

import Link from "next/link";
import { useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import {
  isMock,
  listCalendarEvents,
  listUniversities,
} from "@/lib/universities-api";
import {
  eventOnDay,
  koreanDay,
  scheduleLabels,
  scheduleTime,
} from "@/lib/universities-display";
import { useUniversityResource } from "@/components/universities/shared";
import { Button, buttonClass } from "@/components/ui/button";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import { EmptyState } from "@/components/ui/empty-state";
import { ApiError } from "@/lib/api";

function ResourceMessage({
  done,
  error,
  retry,
}: {
  done: boolean;
  error?: unknown;
  retry: () => void;
}) {
  if (!done)
    return (
      <p role="status" className="py-4 text-label text-ink-2">불러오는 중…</p>
    );
  if (!error) return null;
  return (
    <div role="alert" className="my-4 rounded-card border border-danger/30 p-4">
      <p className="text-label text-danger">
        {error instanceof Error ? error.message : "정보를 불러오지 못했습니다."}
      </p>
      {error instanceof ApiError && error.status === 401 ? (
        <Link href="/login" className={buttonClass("secondary", "mt-3")}>
          로그인하기
        </Link>
      ) : (
        <Button variant="secondary" className="mt-3" onClick={retry}>
          다시 시도
        </Button>
      )}
    </div>
  );
}

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
      <h1 className="mb-6 text-heading font-bold tracking-tight">편입 일정</h1>
      {isMock && (
        <p className="mb-4 text-label text-warning">
          샘플 데이터 · 실제 모집 정보는 학교 원문을 확인하세요.
        </p>
      )}
      <div className="mb-6 max-w-sm">
        <div className="grid gap-2 text-label font-medium">
          <label htmlFor="calendar-school">학교</label>
          <select
            id="calendar-school"
            className="min-h-11 w-full rounded-control border border-line-strong bg-surface px-3 text-body text-ink"
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
        </div>
        <ResourceMessage {...schools} />
      </div>
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <Button
          variant="secondary"
          aria-label="이전 달"
          disabled={previousMonth < "2000-01"}
          onClick={() => change("month", previousMonth)}
        >
          ← 이전
        </Button>
        <h2 className="text-title font-semibold tabular-nums">
          {year}년 {monthNumber}월
        </h2>
        <Button
          variant="secondary"
          aria-label="다음 달"
          disabled={nextMonth > "2099-12"}
          onClick={() => change("month", nextMonth)}
        >
          다음 →
        </Button>
      </div>
      <div className="mb-4 flex flex-wrap items-center gap-3 text-sm">
        <Button
          variant="secondary"
          onClick={() => change("month", today.slice(0, 7))}
        >
          이번 달
        </Button>
        {isMock && (
          <Button
            variant="ghost"
            onClick={() => change("month", "2026-01")}
          >
            2026년 1월 샘플 보기
          </Button>
        )}
      </div>
      <ResourceMessage {...events} />
      <div className="grid items-start gap-6 lg:grid-cols-[minmax(0,1fr)_18rem]">
        <Card
          className="min-w-0"
          aria-label={`${year}년 ${monthNumber}월 달력`}
          aria-busy={!events.done}
        >
          <div className="grid grid-cols-7 text-center text-caption font-medium text-ink-2">
            {["일", "월", "화", "수", "목", "금", "토"].map((day) => (
              <div key={day} className="py-3">
                {day}
              </div>
            ))}
          </div>
          <div className="grid grid-cols-7 border-t border-line">
            {cells.map((day, index) => {
              if (!day)
                return (
                  <div
                    key={`empty-${index}`}
                    className="min-h-20 bg-paper/50 sm:min-h-28"
                  />
                );
              const date = dayString(month, day);
              const items =
                events.data?.filter((event) => eventOnDay(event, date)) ?? [];
              return (
                <button
                  key={date}
                  aria-label={`${monthNumber}월 ${day}일, 일정 ${items.length}개`}
                  aria-pressed={selectedDay === date}
                  onClick={() => setSelected(date)}
                  className={`flex min-h-20 min-w-0 flex-col items-start rounded-control p-1 text-left text-ink transition-colors hover:bg-ink/5 focus-visible:z-10 focus-visible:outline-offset-[-2px] active:bg-ink/10 sm:min-h-28 sm:p-2 ${selectedDay === date ? "bg-lime" : ""}`}
                >
                  <span
                    aria-current={today === date ? "date" : undefined}
                    className={`inline-flex h-7 min-w-7 items-center justify-center rounded-pill text-label font-medium tabular-nums ${today === date ? "border border-ink font-bold" : ""}`}
                  >
                    {day}
                  </span>
                  {items.length > 0 && (
                    <span className="mt-2 text-caption font-medium sm:hidden">{items.length}건</span>
                  )}
                  <div className="mt-1 hidden w-full space-y-1 sm:block">
                    {items.slice(0, 2).map((event) => (
                      <span
                        key={event.id}
                        className="block truncate rounded bg-ink/5 px-1 py-0.5 text-caption text-ink"
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
        </Card>
        <section aria-live="polite" aria-labelledby="selected-day-heading" className="min-w-0">
          <Card>
            <div className="flex flex-wrap items-center justify-between gap-2">
              <h2 id="selected-day-heading" className="text-title font-semibold">{selectedDay} 일정</h2>
              <Badge>{dayEvents.length}건</Badge>
            </div>
            {events.done && !events.error && !dayEvents.length && (
              <EmptyState>이날의 일정이 없습니다.</EmptyState>
            )}
            <ul className="divide-y divide-line">
              {dayEvents.map((event) => (
                <li key={event.id} className="break-words py-5">
                  <Badge>{scheduleLabels[event.type]}</Badge>
                  <h3 className="mt-2 font-semibold">
                    <Link
                      href={`/universities/${event.university.id}?academicYear=${event.academicYear}`}
                      className="inline-flex min-h-11 items-center rounded-control hover:underline"
                    >
                      {event.university.name} · {event.rawLabel}
                    </Link>
                  </h3>
                  <p className="mt-1 text-label text-ink-2">
                    {scheduleTime(event)}
                  </p>
                  {event.note && <p className="mt-1 text-sm">{event.note}</p>}
                </li>
              ))}
            </ul>
          </Card>
        </section>
      </div>
    </>
  );
}
