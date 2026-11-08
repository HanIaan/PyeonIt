import { Suspense } from "react";
import SearchView from "@/components/search/search-view";
export default function Page() {
  return (
    <Suspense fallback={<p>검색을 불러오는 중입니다…</p>}>
      <SearchView />
    </Suspense>
  );
}
