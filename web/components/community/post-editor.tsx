"use client";
import Link from "next/link";
import { resolveApiUrl } from "@/lib/api";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import {
  listBoards,
  listSchools,
  getPost,
  savePost,
  uploadImage,
  validateImage,
  type S,
} from "@/lib/community-api";
import {
  Frame,
  Message,
  button,
  primary,
  input,
  fieldLabel,
  handleError,
  useCommunityAuth,
} from "./shared";
export default function PostEditor({ id }: { id?: number }) {
  const auth = useCommunityAuth();
  const router = useRouter();
  const [boards, setBoards] = useState<S["Board"][]>([]),
    [schools, setSchools] = useState<S["University"][]>([]),
    [boardId, setBoardId] = useState(0),
    [title, setTitle] = useState(""),
    [body, setBody] = useState(""),
    [selected, setSelected] = useState<number[]>([]),
    [images, setImages] = useState<S["PostImage"][]>([]),
    [busy, setBusy] = useState(false),
    [uploading, setUploading] = useState(false),
    [ready, setReady] = useState(false),
    [error, setError] = useState("");
  useEffect(() => {
    let live = true;
    Promise.all([
      listBoards(),
      listSchools(),
      id === undefined ? Promise.resolve(null) : getPost(id),
    ]).then(
      ([b, u, p]) => {
        if (!live) return;
        setBoards(b);
        setSchools(u);
        if (p && !p.mine) {
          setError("본인 글만 수정할 수 있습니다.");
          return;
        }
        setBoardId(p?.board.id ?? b[0]?.id ?? 0);
        if (p) {
          setTitle(p.title);
          setBody(p.body);
          setSelected(p.universities.map((u) => u.id));
          setImages(p.images);
        }
        setReady(true);
      },
      (e) => {
        if (live) handleError(e, setError);
      },
    );
    return () => {
      live = false;
    };
  }, [id, auth.me?.id]);
  async function upload(files: File[]) {
    setError("");
    try {
      if (!auth.check()) return;
      if (files.length + images.length > 10)
        throw new Error("이미지는 최대 10장까지 올릴 수 있습니다.");
      files.forEach(validateImage);
      setUploading(true);
      for (const file of files) {
        const result = await uploadImage(file);
        setImages((old) => [...old, result]);
      }
    } catch (e) {
      handleError(e, setError);
    } finally {
      setUploading(false);
    }
  }
  return (
    <Frame title={id === undefined ? "새 글 쓰기" : "글 수정"}>
      <Message>{error}</Message>
      {auth.state === "loading" && (
        <p role="status" className="text-label text-ink-3">
          로그인 상태를 확인 중…
        </p>
      )}
      {auth.state === "anonymous" && (
        <Message>
          글을 쓰려면{" "}
          <Link className="underline" href="/login">
            로그인
          </Link>
          해 주세요.
        </Message>
      )}
      {auth.state === "pending" && (
        <Message>
          <Link className="underline" href="/signup">
            가입을 완료
          </Link>
          해 주세요.
        </Message>
      )}
      {auth.me?.status === "SUSPENDED" && (
        <Message>정지 중이라 작성할 수 없습니다. 읽기는 가능합니다.</Message>
      )}
      {ready && (
        <form
          className="space-y-5"
          onSubmit={async (e) => {
            e.preventDefault();
            setError("");
            try {
              if (!auth.check()) return;
              setBusy(true);
              const result = await savePost(
                {
                  boardId,
                  title: title.trim(),
                  body: body.trim(),
                  universityIds: selected,
                  imageIds: images.map((i) => i.id),
                },
                id,
              );
              router.push(`/community/${result.id}`);
            } catch (e) {
              handleError(e, setError);
            } finally {
              setBusy(false);
            }
          }}
        >
          <fieldset
            disabled={
              busy ||
              uploading ||
              auth.state !== "authenticated" ||
              auth.me?.status === "SUSPENDED"
            }
            className="space-y-6 disabled:opacity-60"
          >
            <label className={fieldLabel}>
              <span>게시판</span>
              <select
                required
                className={input}
                value={boardId}
                onChange={(e) => setBoardId(Number(e.target.value))}
              >
                {boards.map((b) => (
                  <option key={b.id} value={b.id}>
                    {b.name}
                  </option>
                ))}
              </select>
            </label>
            <label className={fieldLabel}>
              <span>제목</span>
              <input
                required
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className={input}
              />
            </label>
            <label className={fieldLabel}>
              <span>본문</span>
              <textarea
                required
                maxLength={20000}
                rows={12}
                value={body}
                onChange={(e) => setBody(e.target.value)}
                className={input}
              />
            </label>
            <fieldset>
              <legend className="mb-2 text-label font-medium">
                학교 태그 ({selected.length}/5)
              </legend>
              <div className="flex max-h-56 flex-wrap gap-2 overflow-y-auto">
                {schools.map((u) => (
                  <label
                    key={u.id}
                    className="inline-flex min-h-11 cursor-pointer items-center gap-2 rounded-pill border border-line bg-surface px-3 text-label font-medium has-checked:border-ink has-checked:bg-lime has-disabled:opacity-45"
                  >
                    <input
                      type="checkbox"
                      className="size-4 accent-ink"
                      checked={selected.includes(u.id)}
                      disabled={
                        !selected.includes(u.id) && selected.length >= 5
                      }
                      onChange={(e) =>
                        setSelected((old) =>
                          e.target.checked
                            ? [...old, u.id]
                            : old.filter((id) => id !== u.id),
                        )
                      }
                    />
                    {u.name} {u.campus}
                  </label>
                ))}
              </div>
            </fieldset>
            <div className="space-y-3">
              <label className={fieldLabel}>
                <span>이미지 ({images.length}/10)</span>
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,image/gif"
                  multiple
                  className="block w-full text-label text-ink-2 file:mr-3 file:min-h-11 file:cursor-pointer file:rounded-control file:border file:border-ink file:bg-transparent file:px-4 file:font-semibold file:text-ink"
                  onChange={(e) => {
                    const files = Array.from(e.target.files ?? []);
                    e.target.value = "";
                    void upload(files);
                  }}
                />
              </label>
              <p className="text-caption text-ink-3">
                JPG, PNG, WEBP, GIF · 한 장당 최대 5MB · 최대 10장
              </p>
              <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                {images.map((image, index) => (
                  <li
                    key={image.id}
                    className="space-y-2 rounded-card border border-line bg-surface p-2"
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      alt={`첨부 이미지 ${index + 1}`}
                      src={resolveApiUrl(image.url)}
                      className="h-28 w-full rounded-control object-contain"
                    />
                    <button
                      type="button"
                      className={`${button} w-full`}
                      onClick={() =>
                        setImages((old) => old.filter((i) => i.id !== image.id))
                      }
                    >
                      이미지 {index + 1} 제거
                    </button>
                  </li>
                ))}
              </ul>
            </div>
            <div className="flex gap-2">
              <button
                className={primary}
                disabled={!title.trim() || !body.trim() || !boardId}
              >
                {id === undefined ? "등록" : "수정 저장"}
              </button>
              <Link
                className={button}
                href={id === undefined ? "/community" : `/community/${id}`}
              >
                취소
              </Link>
            </div>
          </fieldset>
          {uploading && (
            <p role="status" className="text-label text-ink-3">
              업로드 중…
            </p>
          )}
          {busy && (
            <p role="status" className="text-label text-ink-3">
              저장 중…
            </p>
          )}
        </form>
      )}
    </Frame>
  );
}
