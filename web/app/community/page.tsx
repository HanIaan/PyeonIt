import { Suspense } from "react";
import PostList from "@/components/community/post-list";
export default function Page() {
  return (
    <Suspense
      fallback={
        <p role="status" className="text-label text-ink-3">
          불러오는 중…
        </p>
      }
    >
      <PostList />
    </Suspense>
  );
}
