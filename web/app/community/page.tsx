import { Suspense } from "react";
import PostList from "@/components/community/post-list";
export default function Page() {
  return (
    <Suspense fallback={<p>커뮤니티를 불러오는 중입니다…</p>}>
      <PostList />
    </Suspense>
  );
}
