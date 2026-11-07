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
    description: "원본 PDF와 추출 결과를 대조하고 게시를 승인합니다.",
  },
  {
    href: "/admin/reports",
    label: "미처리 신고",
    description: "신고 내용을 확인하고 처리 또는 기각합니다.",
  },
  {
    href: "/admin/sanctions",
    label: "활성 제재",
    description: "회원 제재를 등록하고 활성 제재를 해제합니다.",
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
            className="rounded-xl border border-slate-200 bg-white p-5 hover:border-teal-600"
          >
            <h3 className="font-semibold">{card.label}</h3>
            <div className="my-4 text-3xl font-bold text-teal-800">
              {resource.loading ? "…" : (resource.data?.[i] ?? "목록 보기")}
            </div>
            <p className="text-sm leading-6 text-slate-600">
              {card.description}
            </p>
            <p className="mt-4 text-sm font-semibold text-teal-800">
              관리하기 →
            </p>
          </Link>
        ))}
      </div>
    </div>
  );
}
