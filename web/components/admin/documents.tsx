"use client";
import { useCallback, useState } from "react";
import {
  listDocuments,
  reviewDocument,
  type AdminDocument,
} from "@/lib/admin-api";
import {
  button,
  secondary,
  table,
  formatDate,
  ResourceState,
  Pagination,
  useAdminResource,
  useAdminFailure,
  errorMessage,
} from "@/components/admin/admin-shell";
import { Confirm } from "@/components/admin/confirm";
import { Extraction, safeUrl } from "@/components/admin/extraction";
export default function DocumentsPage() {
  const [page, setPage] = useState(0);
  return <Documents key={page} page={page} setPage={setPage} />;
}
function Documents({
  page,
  setPage,
}: {
  page: number;
  setPage: (page: number) => void;
}) {
  const load = useCallback(() => listDocuments(page), [page]);
  const resource = useAdminResource(load);
  const fail = useAdminFailure();
  const [selected, setSelected] = useState<AdminDocument | null>(null);
  const [action, setAction] = useState<"approve" | "reject" | null>(null);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  async function submit() {
    if (!selected || !action || busy) return;
    setBusy(true);
    setError("");
    try {
      await reviewDocument(selected.id, action, selected.latestExtraction?.id);
      setMessage(
        `${selected.university.name} 요강을 ${action === "approve" ? "승인" : "반려"}했습니다.`,
      );
      setSelected(null);
      resource.reload();
    } catch (e) {
      fail(e);
      setError(errorMessage(e));
    } finally {
      setBusy(false);
      setAction(null);
    }
  }
  const pdf = safeUrl(selected?.fileUrl ?? null);
  const source = safeUrl(selected?.pageUrl ?? null);
  return (
    <div className="space-y-4">
      <div className="flex items-center justify-between gap-3">
        <div>
          <h2 className="text-lg font-bold">요강 승인 대기</h2>
          <p className="text-sm text-ink-2">
            오래된 감지 순 · 추출 완료 / 검토 필요
          </p>
        </div>
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
      </div>
      {message && (
        <p role="status" className="rounded-control bg-success/5 text-success p-3 text-sm">
          {message}
        </p>
      )}
      {error && (
        <p
          role="alert"
          className="rounded-control bg-danger/5 p-3 text-sm text-danger"
        >
          {error}
        </p>
      )}
      <ResourceState {...resource} />
      {resource.data && (
        <>
          <div className="overflow-x-auto rounded-card border border-line bg-surface">
            <table className={table}>
              <caption className="p-3 text-left text-sm">
                승인 대기 {resource.data.totalElements}건
              </caption>
              <thead>
                <tr>
                  <th>학교 / 학년도</th>
                  <th>상태</th>
                  <th>감지일 (한국시간)</th>
                  <th>검토</th>
                </tr>
              </thead>
              <tbody>
                {resource.data.content.map((doc) => (
                  <tr key={doc.id}>
                    <td>
                      <strong>{doc.university.name}</strong>
                      <p>{doc.academicYear}학년도</p>
                    </td>
                    <td>
                      {doc.status === "EXTRACTED" ? "추출 완료" : "검토 필요"}
                    </td>
                    <td>{formatDate(doc.detectedAt)}</td>
                    <td>
                      <button
                        className={secondary}
                        disabled={busy}
                        onClick={() => {
                          setSelected(doc);
                          setError("");
                        }}
                      >
                        상세 검토
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            {!resource.data.content.length && (
              <p className="p-6 text-sm">승인 대기 요강이 없습니다.</p>
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
        <section className="rounded-card border border-line bg-surface p-4">
          <div className="mb-4 flex items-start justify-between gap-3">
            <div>
              <h3 className="font-bold">{selected.title}</h3>
              <p className="mt-1 text-sm text-ink-3">
                원문과 추출 결과를 대조한 후 처리해 주세요.
              </p>
            </div>
            <button
              className={secondary}
              disabled={busy}
              onClick={() => setSelected(null)}
            >
              닫기
            </button>
          </div>
          <div className="grid gap-6 lg:grid-cols-2">
            <div>
              <h4 className="mb-3 font-bold">원본 PDF</h4>
              {pdf ? (
                <>
                  <a
                    href={pdf}
                    target="_blank"
                    rel="noopener noreferrer"
                    className="text-sm text-ink underline"
                  >
                    PDF 새 창으로 열기 ↗
                  </a>
                  <iframe
                    title={`${selected.university.name} 원본 PDF`}
                    src={pdf}
                    referrerPolicy="no-referrer"
                    sandbox="allow-scripts allow-same-origin allow-popups"
                    className="mt-3 h-[32rem] w-full rounded-control border border-line"
                  />
                  <p className="mt-2 text-xs text-ink-3">
                    임베드가 차단되거나 보이지 않으면 위 새 창 링크를 이용해
                    주세요.
                  </p>
                </>
              ) : (
                <p className="rounded-control bg-paper p-6 text-sm">
                  원본 PDF 링크가 없습니다.
                  {source && (
                    <a
                      href={source}
                      target="_blank"
                      rel="noopener noreferrer"
                      className="ml-2 underline"
                    >
                      학교 공고 열기
                    </a>
                  )}
                </p>
              )}
            </div>
            <Extraction document={selected} />
          </div>
          <div className="mt-6 flex justify-end gap-2 border-t border-line pt-4">
            <button
              className={secondary}
              disabled={busy}
              onClick={() => setAction("reject")}
            >
              반려
            </button>
            <button
              className={button}
              disabled={
                busy ||
                !selected.latestExtraction?.result ||
                !!selected.latestExtraction?.error
              }
              onClick={() => setAction("approve")}
            >
              검토 완료 · 승인
            </button>
          </div>
        </section>
      )}
      {action && selected && (
        <Confirm
          title={`요강 ${action === "approve" ? "승인" : "반려"} 확인`}
          busy={busy}
          onCancel={() => setAction(null)}
          onConfirm={() => void submit()}
        >
          <p>{selected.title}</p>
          <p>
            {action === "approve"
              ? "현재 추출 결과를 모집인원·일정에 반영하여 공개합니다. 원본과 검증 결과를 확인하셨습니까?"
              : "이 요강을 반려합니다. 반려 사유 입력은 현재 API에서 지원하지 않습니다."}
          </p>
        </Confirm>
      )}
    </div>
  );
}
