"use client";
import { useCallback, useState } from "react";
import { handleReport, listReports, type AdminReport } from "@/lib/admin-api";
import type { Schemas } from "@/lib/api";
import {
  button,
  secondary,
  input,
  table,
  formatDate,
  ResourceState,
  Pagination,
  useAdminResource,
  useAdminFailure,
  errorMessage,
} from "@/components/admin/admin-shell";
import { Confirm } from "@/components/admin/confirm";
const statuses = {
  PENDING: "미처리",
  RESOLVED: "처리 완료",
  DISMISSED: "기각",
};
const reasons = {
  SPAM: "도배",
  ABUSE: "욕설·비방",
  ADVERTISEMENT: "광고",
  FALSE_INFO: "허위 정보",
  OTHER: "기타",
};
export default function ReportsPage() {
  const [status, setStatus] = useState<Schemas["ReportStatus"]>("PENDING");
  const [page, setPage] = useState(0);
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-bold">신고 처리</h2>
        <label className="flex max-w-full flex-wrap items-center gap-2 text-sm">
          상태
          <select
            className={input}
            value={status}
            onChange={(e) => {
              setStatus(e.target.value as Schemas["ReportStatus"]);
              setPage(0);
            }}
          >
            {Object.entries(statuses).map(([value, label]) => (
              <option key={value} value={value}>
                {label} ({value})
              </option>
            ))}
          </select>
        </label>
      </div>
      <Reports
        key={`${status}-${page}`}
        status={status}
        page={page}
        setPage={setPage}
      />
    </div>
  );
}
function Reports({
  status,
  page,
  setPage,
}: {
  status: Schemas["ReportStatus"];
  page: number;
  setPage: (page: number) => void;
}) {
  const load = useCallback(() => listReports(status, page), [status, page]);
  const resource = useAdminResource(load);
  const fail = useAdminFailure();
  const [selected, setSelected] = useState<AdminReport | null>(null);
  const [action, setAction] =
    useState<Schemas["ReportHandleRequest"]["action"]>("RESOLVE");
  const [hideTarget, setHideTarget] = useState(false);
  const [confirm, setConfirm] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [message, setMessage] = useState("");
  async function submit() {
    if (!selected || busy) return;
    setBusy(true);
    setError("");
    try {
      await handleReport(selected.id, {
        action,
        hideTarget: selected.targetType !== "USER" && hideTarget,
      });
      setMessage(
        `신고 #${selected.id}을 ${action === "RESOLVE" ? "처리 완료" : "기각"}했습니다.`,
      );
      setSelected(null);
      resource.reload();
    } catch (e) {
      fail(e);
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      setConfirm(false);
    }
  }
  return (
    <div className="space-y-4">
      {message && (
        <p role="status" className="rounded-lg bg-teal-50 p-3 text-sm">
          {message}
        </p>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-lg bg-red-50 p-3 text-sm text-red-800"
        >
          {error}
        </p>
      )}
      <button
        className={secondary}
        disabled={busy}
        onClick={() => {
          setSelected(null);
          resource.reload();
        }}
      >
        새로고침
      </button>
      <ResourceState {...resource} />
      {resource.data && (
        <>
          <div className="overflow-x-auto rounded-xl border border-slate-200 bg-white">
            <table className={table}>
              <caption className="p-3 text-left">
                {statuses[status]} 신고 {resource.data.totalElements}건
              </caption>
              <thead>
                <tr>
                  <th>대상 유형 / ID</th>
                  <th>신고 사유</th>
                  <th>신고자</th>
                  <th>접수일 (한국시간)</th>
                  <th>상태</th>
                  <th>검토</th>
                </tr>
              </thead>
              <tbody>
                {resource.data.content.map((report) => (
                  <tr key={report.id}>
                    <td>
                      {
                        { POST: "게시글", COMMENT: "댓글", USER: "회원" }[
                          report.targetType
                        ]
                      }{" "}
                      #{report.targetId}
                      <p className="max-w-48 truncate text-slate-500">
                        {report.target.title ??
                          report.target.excerpt ??
                          report.target.author?.nickname}
                      </p>
                    </td>
                    <td>{reasons[report.reason]}</td>
                    <td>
                      {report.reporter.nickname ?? "탈퇴 회원"} (#
                      {report.reporter.id})
                    </td>
                    <td>{formatDate(report.createdAt)}</td>
                    <td>{statuses[report.status]}</td>
                    <td>
                      <button
                        className={secondary}
                        disabled={busy}
                        onClick={() => {
                          setSelected(report);
                          setAction("RESOLVE");
                          setHideTarget(false);
                          setError("");
                        }}
                      >
                        상세
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!resource.data.content.length && (
              <p className="p-6 text-sm">해당 상태의 신고가 없습니다.</p>
            )}
          </div>
          <Pagination
            page={page}
            totalPages={resource.data.totalPages}
            onChange={setPage}
          />
        </>
      )}
      {selected && (
        <section className="space-y-4 rounded-xl border border-slate-200 bg-white p-5">
          <div className="flex justify-between gap-3">
            <h3 className="font-bold">신고 #{selected.id} 상세</h3>
            <button
              className={secondary}
              disabled={busy}
              onClick={() => setSelected(null)}
            >
              닫기
            </button>
          </div>
          <dl className="grid gap-2 text-sm sm:grid-cols-[7rem_1fr]">
            <dt className="text-slate-500">신고 대상</dt>
            <dd>
              {selected.targetType} #{selected.targetId} ·{" "}
              {selected.target.author?.nickname ?? "탈퇴 회원"} (#
              {selected.target.author?.id ?? "—"})
            </dd>
            <dt className="text-slate-500">대상 내용</dt>
            <dd className="whitespace-pre-wrap break-words">
              {selected.target.title}{" "}
              {selected.target.excerpt ?? "미리보기 없음"}
            </dd>
            <dt className="text-slate-500">사유</dt>
            <dd>
              {reasons[selected.reason]} · {selected.detail ?? "추가 설명 없음"}
            </dd>
            <dt className="text-slate-500">처리 이력</dt>
            <dd>
              {selected.handledBy
                ? `${selected.handledBy.nickname ?? "관리자"} · ${formatDate(selected.handledAt)}`
                : "미처리"}
            </dd>
          </dl>
          {selected.status === "PENDING" && (
            <div className="space-y-3 border-t border-slate-200 pt-4">
              <label className="block text-sm">
                처리 결과
                <select
                  className={`${input} mt-1`}
                  value={action}
                  onChange={(e) => setAction(e.target.value as typeof action)}
                >
                  <option value="RESOLVE">처리 완료 (RESOLVED)</option>
                  <option value="DISMISS">기각 (DISMISSED)</option>
                </select>
              </label>
              {selected.targetType !== "USER" && (
                <label className="flex items-center gap-2 text-sm">
                  <input
                    type="checkbox"
                    checked={hideTarget}
                    onChange={(e) => setHideTarget(e.target.checked)}
                  />
                  대상 글·댓글 숨기기
                </label>
              )}
              <p className="text-xs text-slate-500">
                회원 제재는 회원 제재 메뉴에서 별도로 등록합니다.
              </p>
              <button
                className={button}
                disabled={busy}
                onClick={() => setConfirm(true)}
              >
                처리 검토
              </button>
            </div>
          )}
        </section>
      )}
      {confirm && selected && (
        <Confirm
          title="신고 처리 확인"
          busy={busy}
          onCancel={() => setConfirm(false)}
          onConfirm={() => void submit()}
        >
          <p>
            신고 #{selected.id}을 {action === "RESOLVE" ? "처리 완료" : "기각"}{" "}
            상태로 변경합니다.
          </p>
          {hideTarget && selected.targetType !== "USER" && (
            <p>대상 글·댓글이 숨김 처리됩니다.</p>
          )}
        </Confirm>
      )}
    </div>
  );
}
