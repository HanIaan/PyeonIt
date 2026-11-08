"use client";

import Link from "next/link";
import { useMe } from "@/lib/auth";
import { useEffect, useRef, useState } from "react";
import { ApiError } from "@/lib/api";
import { isMock, setFavoriteUniversity } from "@/lib/universities-api";
import {
  regions,
  scheduleLabels,
  scheduleTime,
} from "@/lib/universities-display";
import type { Schemas } from "@/lib/universities-api";
import * as ui from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

export const inputClass =
  "min-h-11 w-full rounded-control border border-line-strong bg-surface px-3 text-body text-ink";
export const buttonClass = ui.buttonClass("secondary");
export const cardClass =
  "rounded-card border border-line bg-surface p-4 sm:p-6";
export const linkClass =
  "rounded-control text-label font-semibold text-ink underline underline-offset-4 hover:text-ink-2";

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
  description?: string;
}) {
  return (
    <div className="mb-6">
      <h1 className="text-heading font-bold md:text-display">{title}</h1>
      {description && (
        <p className="mt-1 text-label text-ink-2">{description}</p>
      )}
      {isMock && (
        <p className="mt-3 text-label text-warning">
          샘플 데이터 · 실제 모집 정보는 학교 원문을 확인하세요.
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
      <p role="status" className="py-8 text-label text-ink-2">
        불러오는 중…
      </p>
    );
  if (error)
    return (
      <div
        role="alert"
        className="my-4 rounded-card border border-danger/30 bg-surface p-4"
      >
        <p className="text-danger">
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
    <p className="my-3 text-label text-ink-2">
      관심학교 기능은 로그인이 필요합니다.{" "}
      <Link href="/login" className={linkClass}>
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
    <label className="grid gap-1.5 text-label font-medium">
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
    <div className="flex flex-wrap gap-4">
      {fileUrl && (
        <a
          href={fileUrl}
          target="_blank"
          rel="noopener noreferrer"
          className={linkClass}
        >
          원본 PDF ↗
        </a>
      )}
      {pageUrl && (
        <a
          href={pageUrl}
          target="_blank"
          rel="noopener noreferrer"
          className={linkClass}
        >
          학교 원문 ↗
        </a>
      )}
      {!fileUrl && !pageUrl && (
        <span className="text-label text-ink-3">원본 링크 미등록</span>
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
    <ul className="divide-y divide-line">
      {schedules.map((s) => (
        <li key={s.id} className="py-4">
          <Badge tone={s.type === "APPLICATION" ? "lime" : "neutral"}>
            {scheduleLabels[s.type]}
          </Badge>
          <p className="mt-2 font-semibold">{s.rawLabel}</p>
          <p className="mt-0.5 text-label text-ink-2">{scheduleTime(s)}</p>
          {s.note && <p className="mt-1 text-label">{s.note}</p>}
        </li>
      ))}
      {!schedules.length && (
        <li className="py-4 text-label text-ink-3">등록된 일정이 없습니다.</li>
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
        className={ui.buttonClass(favorite ? "primary" : "secondary")}
        disabled={pending || !session.done || !!session.error}
        onClick={toggle}
        aria-pressed={favorite}
        aria-label={`${favorite ? "관심학교 해제" : "관심학교 추가"}`}
      >
        <span aria-hidden="true">{favorite ? "★" : "☆"}</span>
        {favorite ? "관심학교" : "관심학교 추가"}
        {pending ? " · 저장 중" : ""}
      </button>
      {message && (
        <p role="alert" className="mt-2 text-label text-danger">
          {message}{" "}
          <Link href="/login" className="underline underline-offset-4">
            로그인 확인
          </Link>
        </p>
      )}
    </div>
  );
}
