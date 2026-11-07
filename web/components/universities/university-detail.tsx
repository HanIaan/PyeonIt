"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { getUniversity, type Schemas } from "@/lib/universities-api";
import { quotaColumn, regions } from "@/lib/universities-display";
import {
  cardClass,
  FavoriteButton,
  inputClass,
  PageIntro,
  ResourceMessage,
  ScheduleList,
  SourceLinks,
  useUniversityResource,
  useUniversitySession,
} from "./shared";

function QuotaTable({
  departments,
}: {
  departments: Schemas["DepartmentNode"][];
}) {
  const rows: { node: Schemas["DepartmentNode"]; depth: number }[] = [];
  const visit = (nodes: Schemas["DepartmentNode"][], depth: number) =>
    nodes.forEach((node) => {
      rows.push({ node, depth });
      visit(node.children, depth + 1);
    });
  visit(departments, 0);
  const columns = new Map<string, string>();
  rows.forEach(({ node }) =>
    node.quotas.forEach((q) =>
      columns.set(
        quotaColumn(q),
        q.track
          ? q.rawTrackName !== q.track.name
            ? `${q.track.name} (${q.rawTrackName})`
            : q.track.name
          : q.rawTrackName,
      ),
    ),
  );
  if (!rows.length)
    return <p className="py-4 text-sm">등록된 모집인원이 없습니다.</p>;
  return (
    <div
      className="mt-4 overflow-x-auto rounded-xl border border-slate-200 dark:border-slate-700"
      role="region"
      aria-label="모집인원 표 (가로 스크롤 가능)"
      tabIndex={0}
    >
      <table className="w-full min-w-[36rem] border-collapse text-left text-sm">
        <caption className="sr-only">단과대 및 학과별 전형별 모집인원</caption>
        <thead className="bg-slate-100 dark:bg-slate-800">
          <tr>
            <th scope="col" className="min-w-48 p-3">
              단과대 / 학과
            </th>
            {[...columns].map(([key, label]) => (
              <th key={key} scope="col" className="min-w-32 p-3">
                {label}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map(({ node, depth }) => (
            <tr
              key={node.id}
              className={`border-t border-slate-200 dark:border-slate-700 ${node.level === "COLLEGE" ? "bg-slate-50 font-semibold dark:bg-slate-900" : ""}`}
            >
              <th
                scope="row"
                className="p-3"
                style={{ paddingLeft: `${12 + depth * 20}px` }}
              >
                {node.name}
              </th>
              {[...columns.keys()].map((key) => {
                const quotas = node.quotas.filter(
                  (q) => quotaColumn(q) === key,
                );
                return (
                  <td key={key} className="p-3">
                    {quotas.length
                      ? quotas
                          .map((q) =>
                            q.kind === "EXACT"
                              ? q.count
                              : q.kind === "NOT_RECRUITING"
                                ? "미선발"
                                : q.rawText,
                          )
                          .join(" / ")
                      : "—"}
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function UniversityDetail({ id }: { id: number }) {
  const params = useSearchParams();
  const router = useRouter();
  const rawYear = params.get("academicYear");
  const year =
    rawYear &&
    /^\d{4}$/.test(rawYear) &&
    Number(rawYear) >= 2000 &&
    Number(rawYear) <= 2100
      ? Number(rawYear)
      : undefined;
  const session = useUniversitySession();
  const result = useUniversityResource(
    `university:${id}:${year}:${session.identity}`,
    () => getUniversity(id, year),
  );
  const u = result.data;
  return (
    <>
      <Link
        href="/universities"
        className="mb-5 inline-block text-sm text-blue-600 underline dark:text-blue-400"
      >
        ← 학교 목록
      </Link>
      <ResourceMessage {...result} />
      {u && (
        <>
          <PageIntro
            title={u.name}
            description={`${regions[u.region]} ${u.campus} · 학년도별 모집요강`}
          />
          <ResourceMessage {...session} />
          <FavoriteButton
            key={`${u.id}:${u.favorite}`}
            id={u.id}
            initialFavorite={u.favorite}
            session={session}
          />
          <label className="mb-6 mt-6 grid max-w-xs gap-1.5 text-sm">
            모집 학년도
            <select
              className={inputClass}
              value={year ?? u.guide?.academicYear ?? ""}
              onChange={(e) => {
                const next = new URLSearchParams(params);
                next.set("academicYear", e.target.value);
                router.push(`/universities/${id}?${next}`);
              }}
            >
              <option value="" disabled>
                학년도를 선택하세요
              </option>
              {year && !u.academicYears.includes(year) && (
                <option value={year}>{year}학년도 (요강 없음)</option>
              )}
              {u.academicYears.map((y) => (
                <option key={y} value={y}>
                  {y}학년도
                </option>
              ))}
            </select>
          </label>
          {u.guide ? (
            <>
              <section className={cardClass}>
                <h2 className="mb-4 text-xl font-semibold">{u.guide.title}</h2>
                <SourceLinks {...u.guide} />
                <h3 className="mt-6 text-lg font-semibold">모집인원 (TO)</h3>
                <p className="mt-2 text-xs text-slate-500">
                  단위: 명 · —는 해당 전형의 등록된 수치가 없음을 뜻합니다. 표를
                  좌우로 스크롤할 수 있습니다.
                </p>
                <QuotaTable departments={u.guide.departments} />
              </section>
              <section className={`${cardClass} mt-6`}>
                <h2 className="text-xl font-semibold">전형 일정</h2>
                <p className="mt-2 text-xs text-slate-500">
                  모든 날짜와 시각은 한국 시간 기준입니다.
                </p>
                <ScheduleList
                  schedules={[...u.guide.schedules].sort(
                    (a, b) => Date.parse(a.startsAt) - Date.parse(b.startsAt),
                  )}
                />
              </section>
            </>
          ) : (
            <p className={cardClass}>
              선택한 학년도의 게시된 모집요강이 없습니다.
            </p>
          )}
        </>
      )}
    </>
  );
}
