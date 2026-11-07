"use client";

import Link from "next/link";
import { useMe } from "@/lib/auth";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api";
import { isMock, setFavoriteUniversity } from "@/lib/universities-api";
import {
  regions,
  scheduleColors,
  scheduleLabels,
  scheduleTime,
} from "@/lib/universities-display";
import type { Schemas } from "@/lib/universities-api";

export const inputClass =
  "w-full rounded-xl border border-slate-300 bg-white px-3 py-2.5 text-slate-900 dark:border-slate-600 dark:bg-slate-900 dark:text-slate-100";
export const buttonClass =
  "inline-flex min-h-11 items-center justify-center rounded-xl border border-slate-300 px-4 py-2 text-sm font-medium hover:bg-slate-100 disabled:cursor-not-allowed disabled:opacity-40 dark:border-slate-600 dark:hover:bg-slate-800";
export const cardClass =
  "rounded-2xl border border-slate-200 p-4 sm:p-6 dark:border-slate-700";

type Resource<T> = { key: string; data?: T; error?: unknown; done: boolean };
export function useUniversityResource<T>(key: string, load: () => Promise<T>) {
  const [state, setState] = useState<Resource<T>>({ key: "", done: false });
  const [attempt, setAttempt] = useState(0);
  const loader = useRef(load);
  useEffect(() => {
    loader.current = load;
  });
  const requestKey = `${key}:${attempt}`;
  useEffect(() => {
    let active = true;
    loader.current().then(
      (data) => {
        if (active) setState({ key: requestKey, data, done: true });
      },
      (error: unknown) => {
        if (active) setState({ key: requestKey, error, done: true });
      },
    );
    return () => {
      active = false;
    };
  }, [requestKey]);
  const current: Resource<T> =
    state.key === requestKey ? state : { key: requestKey, done: false };
  return {
    data: current.data,
    error: current.error,
    done: current.done,
    retry: () => setAttempt((a) => a + 1),
  };
}
export function useUniversitySession() {
  const { me, state, error, refresh } = useMe();
  return {
    data: me,
    done: state !== "loading",
    error: state === "error" ? new Error(error) : undefined,
    retry: () => {
      void refresh();
    },
    identity: state === "authenticated" ? String(me?.id) : state,
  };
}
export function PageIntro({
  title,
  description,
}: {
  title: string;
  description: string;
}) {
  return (
    <div className="mb-6">
      <p className="mb-2 text-sm font-semibold text-blue-600 dark:text-blue-400">
        PyeonIt · 편입 정보
      </p>
      <h1 className="text-3xl font-bold tracking-tight">{title}</h1>
      <p className="mt-3 text-sm text-slate-600 dark:text-slate-400">
        {description}
      </p>
      {isMock && (
        <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
          샘플 데이터 · 서강·성균관 2026학년도 예시입니다. 실제 모집 정보는 학교
          원문을 확인하세요.
        </p>
      )}
    </div>
  );
}
export function ResourceMessage({
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
      <p role="status" className="py-8 text-sm">
        불러오는 중…
      </p>
    );
  if (error)
    return (
      <div role="alert" className="my-4 rounded-xl border border-red-200 p-4">
        <p>
          {error instanceof Error
            ? error.message
            : "정보를 불러오지 못했습니다."}
        </p>
        {error instanceof ApiError && error.status === 401 ? (
          <LoginNotice />
        ) : (
          <button className={`${buttonClass} mt-3`} onClick={retry}>
            다시 시도
          </button>
        )}
      </div>
    );
  return null;
}
export function LoginNotice() {
  return (
    <p className="my-3 text-sm">
      관심학교 기능은 로그인이 필요합니다.{" "}
      <Link
        href="/login"
        className="font-semibold text-blue-600 underline dark:text-blue-400"
      >
        로그인하기
      </Link>
    </p>
  );
}
export function RegionSelect({
  value,
  onChange,
}: {
  value: string;
  onChange: (value: string) => void;
}) {
  return (
    <label className="grid gap-1.5 text-sm">
      지역
      <select
        className={inputClass}
        value={value}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">전체 지역</option>
        {Object.entries(regions).map(([key, name]) => (
          <option key={key} value={key}>
            {name}
          </option>
        ))}
      </select>
    </label>
  );
}
export function SourceLinks({
  fileUrl,
  pageUrl,
}: {
  fileUrl: string | null;
  pageUrl: string | null;
}) {
  return (
    <div className="flex flex-wrap gap-4 text-sm font-medium text-blue-600 dark:text-blue-400">
      {fileUrl && (
        <a
          href={fileUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="underline"
        >
          원본 PDF ↗
        </a>
      )}
      {pageUrl && (
        <a
          href={pageUrl}
          target="_blank"
          rel="noopener noreferrer"
          className="underline"
        >
          학교 원문 ↗
        </a>
      )}
      {!fileUrl && !pageUrl && (
        <span className="text-slate-500">원본 링크 미등록</span>
      )}
    </div>
  );
}
export function ScheduleList({
  schedules,
}: {
  schedules: Schemas["Schedule"][];
}) {
  return (
    <ul className="divide-y divide-slate-200 dark:divide-slate-700">
      {schedules.map((s) => (
        <li key={s.id} className="py-4">
          <span
            className={`inline-block rounded-md px-2 py-1 text-xs font-semibold ${scheduleColors[s.type]}`}
          >
            {scheduleLabels[s.type]}
          </span>
          <p className="mt-2 font-medium">{s.rawLabel}</p>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            {scheduleTime(s)}
          </p>
          {s.note && <p className="mt-1 text-sm">{s.note}</p>}
        </li>
      ))}
      {!schedules.length && (
        <li className="py-4 text-sm text-slate-500">등록된 일정이 없습니다.</li>
      )}
    </ul>
  );
}
export function FavoriteButton({
  id,
  initialFavorite,
  session,
}: {
  id: number;
  initialFavorite: boolean;
  session: ReturnType<typeof useUniversitySession>;
}) {
  const [favorite, setFavorite] = useState(initialFavorite);
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState("");
  if (session.done && !session.error && !session.data)
    return (
      <Link href="/login" className={buttonClass}>
        로그인하고 관심학교 추가
      </Link>
    );
  async function toggle() {
    const next = !favorite;
    setFavorite(next);
    setPending(true);
    setMessage("");
    try {
      await setFavoriteUniversity(id, next);
    } catch (error) {
      setFavorite(!next);
      setMessage(
        error instanceof Error
          ? error.message
          : "관심학교 변경에 실패했습니다.",
      );
    } finally {
      setPending(false);
    }
  }
  return (
    <div>
      <button
        className={buttonClass}
        disabled={pending || !session.done || !!session.error}
        onClick={toggle}
        aria-pressed={favorite}
        aria-label={`${favorite ? "관심학교 해제" : "관심학교 추가"}`}
      >
        {favorite ? "★ 관심학교" : "☆ 관심학교 추가"}
        {pending ? " · 저장 중" : ""}
      </button>
      {message && (
        <p role="alert" className="mt-2 text-sm text-red-600">
          {message}{" "}
          <Link href="/login" className="underline">
            로그인 확인
          </Link>
        </p>
      )}
    </div>
  );
}
