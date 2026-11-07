import { Suspense } from "react";
import { notFound } from "next/navigation";
import PostEditor from "@/components/community/post-editor";

// 공통 AuthProvider가 사전 렌더링할 대표 경로. 다른 ID도 런타임에 처리한다.
export function generateStaticParams() {
  return [{ postId: "1" }];
}
type Props = { params: Promise<{ postId: string }> };
async function Content({ params }: Props) {
  const { postId } = await params;
  if (
    !/^\d+$/.test(postId) ||
    !Number.isSafeInteger(Number(postId)) ||
    Number(postId) < 1
  )
    notFound();
  return <PostEditor key={postId} id={Number(postId)} />;
}
export default function Page(props: Props) {
  return (
    <Suspense fallback={<p role="status">글을 불러오는 중입니다…</p>}>
      <Content {...props} />
    </Suspense>
  );
}
