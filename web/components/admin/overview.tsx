"use client";
import Link from "next/link";
import { listDocuments, listReports, listSanctions } from "@/lib/admin-api";
import { ApiError } from "@/lib/api";
import {
  ResourceState,
  useAdminResource,
} from "@/components/admin/admin-shell";
const cards = [
  {
    href: "/admin/documents",
    label: "승인 대기 요강",
    description: "원본 PDF · 추출 결과 대조",
  },
  {
    href: "/admin/reports",
    label: "미처리 신고",
    description: "신고 확인 · 처리 · 기각",
  },
  {
    href: "/admin/sanctions",
    label: "활성 제재",
    description: "제재 등록 · 해제",
  },
];
async function summary() {
  return Promise.all(
    [listDocuments(), listReports(), listSanctions(true)].map(
      async (promise) => {
        try {
          return (await promise).totalElements;
        } catch (error) {
          if (error instanceof ApiError && [401, 403].includes(error.status))
            throw error;
          return undefined;
        }
      },
    ),
  );
}
export default function AdminHome() {
  const resource = useAdminResource(summary);
  return (
    <div>
      <h2 className="mb-4 text-lg font-bold">운영 현황</h2>
      <ResourceState {...resource} />
      <div className="grid gap-4 md:grid-cols-3">
        {cards.map((card, i) => (
          <Link
            key={card.href}
            href={card.href}
            className="rounded-card border border-line bg-surface p-5 hover:border-ink"
          >
            <h3 className="font-semibold">{card.label}</h3>
            <div className="my-4 text-display font-semibold tracking-tight text-ink">
              {resource.loading ? "…" : (resource.data?.[i] ?? "목록 보기")}
            </div>
            <p className="text-sm leading-6 text-ink-2">
              {card.description}
            </p>
            <p className="mt-4 text-sm font-semibold text-ink">
              관리하기 →
            </p>
          </Link>
        ))}
      </div>
    </div>
  );
}
