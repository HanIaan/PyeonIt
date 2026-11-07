"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  getPost,
  deletePost,
  likePost,
  reportPost,
  listComments,
  writeComment,
  changeComment,
  type S,
} from "@/lib/community-api";
import {
  Frame,
  Message,
  Tags,
  Pager,
  authorName,
  dateLabel,
  button,
  primary,
  input,
  handleError,
  useCommunityAuth,
} from "./shared";
const reasons: [S["ReportReason"], string][] = [
  ["SPAM", "도배·스팸"],
  ["ABUSE", "욕설·비방"],
  ["ADVERTISEMENT", "광고"],
  ["FALSE_INFO", "허위 정보"],
  ["OTHER", "기타"],
];
function CommentRow({
  comment: c,
  reply,
  act,
  busy,
}: {
  comment: S["Comment"];
  reply: (c: S["Comment"]) => void;
  act: (id: number, body?: string) => Promise<boolean>;
  busy: boolean;
}) {
  const [editing, setEditing] = useState(false),
    [body, setBody] = useState(c.body ?? "");
  return (
    <li
      className={`space-y-3 border-b border-slate-200 py-4 ${c.parentId ? "ml-5 border-l pl-4" : ""}`}
    >
      <div className="flex flex-wrap gap-3 text-xs text-slate-500">
        <span>
          {c.status === "DELETED" ? "삭제된 댓글" : authorName(c.author)}
        </span>
        <time dateTime={c.createdAt}>{dateLabel(c.createdAt)}</time>
      </div>
      {editing ? (
        <form
          className="space-y-2"
          onSubmit={async (e) => {
            e.preventDefault();
            if (await act(c.id, body.trim())) setEditing(false);
          }}
        >
          <label className="sr-only" htmlFor={`edit-${c.id}`}>
            댓글 수정
          </label>
          <textarea
            id={`edit-${c.id}`}
            required
            maxLength={2000}
            className={input}
            value={body}
            onChange={(e) => setBody(e.target.value)}
          />
          <div className="flex gap-2">
            <button disabled={busy || !body.trim()} className={primary}>
              저장
            </button>
            <button
              type="button"
              className={button}
              onClick={() => setEditing(false)}
            >
              취소
            </button>
          </div>
        </form>
      ) : (
        <p className="whitespace-pre-wrap break-words text-sm">
          {c.body ?? "삭제된 댓글입니다."}
        </p>
      )}
      <div className="flex flex-wrap gap-2">
        {c.parentId === null && c.status === "PUBLISHED" && (
          <button className={button} onClick={() => reply(c)}>
            답글
          </button>
        )}
        {c.mine && c.status === "PUBLISHED" && (
          <>
            <button
              className={button}
              disabled={busy}
              onClick={() => {
                setBody(c.body ?? "");
                setEditing(true);
              }}
            >
              수정
            </button>
            <button
              className={button}
              disabled={busy}
              onClick={() => {
                if (window.confirm("댓글을 삭제하시겠습니까?")) void act(c.id);
              }}
            >
              삭제
            </button>
          </>
        )}
      </div>
      {c.replies.length > 0 && (
        <ul>
          {c.replies.map((r) => (
            <CommentRow
              key={r.id}
              comment={r}
              reply={reply}
              act={act}
              busy={busy}
            />
          ))}
        </ul>
      )}
    </li>
  );
}
export default function PostDetail({ id }: { id: number }) {
  const auth = useCommunityAuth();
  const router = useRouter();
  const [post, setPost] = useState<S["PostDetail"] | null>(null),
    [comments, setComments] = useState<S["CommentPage"] | null>(null),
    [page, setPage] = useState(0),
    [error, setError] = useState(""),
    [notice, setNotice] = useState(""),
    [busy, setBusy] = useState(false),
    [liking, setLiking] = useState(false),
    [body, setBody] = useState(""),
    [parent, setParent] = useState<S["Comment"] | null>(null),
    [reporting, setReporting] = useState(false),
    [reason, setReason] = useState<S["ReportReason"]>("SPAM"),
    [detail, setDetail] = useState("");
  useEffect(() => {
    let live = true;
    Promise.all([getPost(id), listComments(id, page)]).then(
      ([p, c]) => {
        if (live) {
          setPost(p);
          setComments(c);
          setError("");
        }
      },
      (e) => {
        if (live) handleError(e, setError);
      },
    );
    return () => {
      live = false;
    };
  }, [id, page, auth.me?.id]);
  async function run(action: () => Promise<void>) {
    setError("");
    try {
      if (!auth.check()) return false;
      setBusy(true);
      await action();
      return true;
    } catch (e) {
      handleError(e, setError);
      return false;
    } finally {
      setBusy(false);
    }
  }
  async function refresh() {
    const [p, c] = await Promise.all([getPost(id), listComments(id, page)]);
    setPost(p);
    setComments(c);
  }
  async function toggleLike() {
    if (!post || liking) return;
    const old = post;
    try {
      if (!auth.check()) return;
      setError("");
      setLiking(true);
      setPost({
        ...old,
        likedByMe: !old.likedByMe,
        likeCount: old.likeCount + (old.likedByMe ? -1 : 1),
      });
      const result = await likePost(id, !old.likedByMe);
      setPost(
        (p) =>
          p && { ...p, likedByMe: result.liked, likeCount: result.likeCount },
      );
    } catch (e) {
      setPost(old);
      handleError(e, setError);
    } finally {
      setLiking(false);
    }
  }
  return (
    <Frame title={post?.title ?? "글 상세"}>
      <Message>{error}</Message>
      <Message>{notice}</Message>
      {!post && !error && <p role="status">글을 불러오는 중입니다…</p>}
      {post && (
        <>
          <div className="flex flex-wrap gap-3 text-sm text-slate-500">
            <span>{post.board.name}</span>
            <span>{authorName(post.author)}</span>
            <time dateTime={post.createdAt}>{dateLabel(post.createdAt)}</time>
            <span>조회 {post.viewCount}</span>
          </div>
          <Tags schools={post.universities} />
          <article className="space-y-5 border-y border-slate-200 py-6">
            <p className="whitespace-pre-wrap break-words leading-8">
              {post.body}
            </p>
            {post.images.map((img, i) => (
              <a key={img.id} href={img.url} target="_blank" rel="noreferrer">
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img
                  src={img.url}
                  alt={`첨부 이미지 ${i + 1}`}
                  className="my-4 max-h-[36rem] w-auto max-w-full rounded-xl"
                />
              </a>
            ))}
          </article>
          <div className="flex flex-wrap gap-2">
            <button
              aria-pressed={post.likedByMe}
              disabled={liking}
              onClick={() => void toggleLike()}
              className={post.likedByMe ? primary : button}
            >
              추천 {post.likeCount}
            </button>
            <button
              className={button}
              onClick={() => {
                try {
                  if (auth.check()) setReporting((v) => !v);
                } catch (e) {
                  handleError(e, setError);
                }
              }}
            >
              신고
            </button>
            {post.mine && (
              <>
                <Link className={button} href={`/community/${id}/edit`}>
                  수정
                </Link>
                <button
                  disabled={busy}
                  className={button}
                  onClick={() => {
                    if (
                      window.confirm(
                        "글을 삭제하시겠습니까? 삭제 후 되돌릴 수 없습니다.",
                      )
                    )
                      void run(async () => {
                        await deletePost(id);
                        router.push("/community");
                      });
                  }}
                >
                  삭제
                </button>
              </>
            )}
          </div>
          {reporting && (
            <form
              className="space-y-3 rounded-xl border border-slate-300 p-4"
              onSubmit={(e) => {
                e.preventDefault();
                void run(async () => {
                  await reportPost(id, reason, detail.trim());
                  setReporting(false);
                  setNotice("신고가 접수되었습니다.");
                });
              }}
            >
              <h2 className="font-semibold">글 신고</h2>
              <label className="block text-sm">
                신고 사유
                <select
                  className={`${input} mt-1`}
                  value={reason}
                  onChange={(e) =>
                    setReason(e.target.value as S["ReportReason"])
                  }
                >
                  {reasons.map(([v, label]) => (
                    <option key={v} value={v}>
                      {label}
                    </option>
                  ))}
                </select>
              </label>
              <label className="block text-sm">
                상세 내용 (선택, 최대 500자)
                <textarea
                  maxLength={500}
                  value={detail}
                  onChange={(e) => setDetail(e.target.value)}
                  className={`${input} mt-1`}
                />
              </label>
              <div className="flex gap-2">
                <button disabled={busy} className={primary}>
                  신고 접수
                </button>
                <button
                  type="button"
                  className={button}
                  onClick={() => setReporting(false)}
                >
                  취소
                </button>
              </div>
            </form>
          )}
          <h2 className="pt-5 text-lg font-bold">댓글 {post.commentCount}</h2>
          {comments && (
            <>
              <ul>
                {comments.content.map((c) => (
                  <CommentRow
                    key={c.id}
                    comment={c}
                    busy={busy}
                    reply={(c) => {
                      try {
                        if (auth.check()) {
                          setParent(c);
                          document.getElementById("comment-body")?.focus();
                        }
                      } catch (e) {
                        handleError(e, setError);
                      }
                    }}
                    act={(cid, text) =>
                      run(async () => {
                        await changeComment(cid, text);
                        await refresh();
                      })
                    }
                  />
                ))}
              </ul>
              {!comments.content.length && (
                <p className="text-sm text-slate-500">첫 댓글을 남겨 보세요.</p>
              )}
              <Pager
                page={page}
                total={comments.totalPages}
                onChange={setPage}
              />
            </>
          )}
          <form
            className="space-y-3"
            onSubmit={(e) => {
              e.preventDefault();
              void run(async () => {
                await writeComment(id, body.trim(), parent?.id ?? null);
                setBody("");
                setParent(null);
                await refresh();
              });
            }}
          >
            {parent && (
              <div className="flex items-center justify-between rounded-lg bg-indigo-50 p-3 text-sm text-indigo-800">
                <span>
                  {authorName(parent.author)}님에게 답글 · 답글은 한 단계만
                  가능합니다.
                </span>
                <button
                  type="button"
                  className={button}
                  onClick={() => setParent(null)}
                >
                  취소
                </button>
              </div>
            )}
            <label htmlFor="comment-body" className="block text-sm">
              {parent ? "답글" : "댓글"} 작성
            </label>
            <textarea
              id="comment-body"
              className={input}
              required
              maxLength={2000}
              value={body}
              onChange={(e) => setBody(e.target.value)}
              placeholder="서로를 존중하는 댓글을 남겨 주세요. (최대 2,000자)"
            />
            <button className={primary} disabled={busy || !body.trim()}>
              등록
            </button>
            {auth.state === "anonymous" && (
              <p className="text-sm">
                <Link href="/login" className="text-indigo-600 underline">
                  로그인
                </Link>{" "}
                후 댓글을 작성할 수 있습니다.
              </p>
            )}
          </form>
        </>
      )}
    </Frame>
  );
}
