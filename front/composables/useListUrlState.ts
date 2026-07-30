// 목록 page/query URL과 Pinia 상태를 동기화하는 유틸리티.
// 일반 함수(composable 컨텍스트 불필요) — window.history 직접 조작.

export function normalizeSearchQuery(value: string): string {
  return value.trim();
}

export function getListStateFromLocation(): { page: number; query: string } {
  const params = new URLSearchParams(window.location.search);
  const pageValue = Number.parseInt(params.get("page") ?? "1", 10);
  return {
    page: Number.isFinite(pageValue) && pageValue > 0 ? pageValue : 1,
    query: normalizeSearchQuery(params.get("query") ?? "")
  };
}

export function updateListStateInUrl(page: number, query: string, options: { replace?: boolean } = {}): void {
  const nextPage = Math.max(page, 1);
  const normalizedQuery = normalizeSearchQuery(query);
  const url = new URL(window.location.href);
  url.searchParams.set("page", String(nextPage));
  if (normalizedQuery) {
    url.searchParams.set("query", normalizedQuery);
  } else {
    url.searchParams.delete("query");
  }
  const method = options.replace ? "replaceState" : "pushState";
  window.history[method]({}, "", `${url.pathname}${url.search}${url.hash}`);
}
