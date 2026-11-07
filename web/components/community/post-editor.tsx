"use client";
import Link from "next/link";
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
        <p role="status">로그인 상태를 확인 중입니다…</p>
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
            className="space-y-5 disabled:opacity-60"
          >
            <label className="block text-sm font-medium">
              게시판
              <select
                required
                className={`${input} mt-2`}
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
            <label className="block text-sm font-medium">
              제목
              <input
                required
                maxLength={200}
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                className={`${input} mt-2`}
                placeholder="제목을 입력해 주세요 (최대 200자)"
              />
            </label>
            <label className="block text-sm font-medium">
              본문
              <textarea
                required
                maxLength={20000}
                rows={12}
                value={body}
                onChange={(e) => setBody(e.target.value)}
                className={`${input} mt-2`}
                placeholder="궁금한 점이나 나누고 싶은 이야기를 적어 주세요."
              />
            </label>
            <fieldset>
              <legend className="mb-2 text-sm font-medium">
                학교 태그 ({selected.length}/5)
              </legend>
              <div className="flex max-h-56 flex-wrap gap-2 overflow-y-auto">
                {schools.map((u) => (
                  <label
                    key={u.id}
                    className={`${button} gap-2 ${selected.includes(u.id) ? "border-indigo-600 text-indigo-600" : ""}`}
                  >
                    <input
                      type="checkbox"
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
              <label className="block text-sm font-medium">
                이미지 ({images.length}/10)
                <input
                  type="file"
                  accept="image/jpeg,image/png,image/webp,image/gif"
                  multiple
                  className="mt-2 block w-full text-sm file:mr-3 file:rounded-lg file:border file:border-slate-300 file:bg-slate-50 file:px-4 file:py-3 file:text-slate-900"
                  onChange={(e) => {
                    const files = Array.from(e.target.files ?? []);
                    e.target.value = "";
                    void upload(files);
                  }}
                />
              </label>
              <p className="text-xs text-slate-500">
                JPG, PNG, WEBP, GIF · 한 장당 최대 5MB · 최대 10장
              </p>
              <ul className="grid grid-cols-2 gap-3 sm:grid-cols-3">
                {images.map((image, index) => (
                  <li
                    key={image.id}
                    className="space-y-2 rounded-xl border border-slate-200 p-2"
                  >
                    {/* eslint-disable-next-line @next/next/no-img-element */}
                    <img
                      alt={`첨부 이미지 ${index + 1}`}
                      src={image.url}
                      className="h-28 w-full rounded-lg object-contain"
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
          {uploading && <p role="status">이미지를 업로드하는 중입니다…</p>}
          {busy && <p role="status">글을 저장하는 중입니다…</p>}
        </form>
      )}
    </Frame>
  );
}
