import { Suspense } from "react";
import NoticesView from "@/components/notices/notices-view";

export default function Page() {
  return (
    <Suspense fallback={<p role="status">공고를 불러오는 중…</p>}>
      <NoticesView />
    </Suspense>
  );
}
