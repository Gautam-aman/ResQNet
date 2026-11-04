import { create } from 'zustand';
import {
  api,
  ApiError,
  type IncidentStatus,
  type IncidentSummary,
  type Overview,
} from '@/lib/api';
import { useAuthStore } from '@/state/auth';

interface IncidentFilters {
  status: IncidentStatus | null;
  query: string;
  severity: number | null;
}

interface IncidentState {
  items: IncidentSummary[];
  overview: Overview | null;
  filters: IncidentFilters;
  loading: boolean;
  loadingMore: boolean;
  page: number;
  totalElements: number;
  hasMore: boolean;
  unreachable: boolean;
  stale: boolean;
  error: string | null;

  setStatusFilter: (status: IncidentStatus | null) => void;
  setQuery: (query: string) => void;
  setSeverityFilter: (severity: number | null) => void;
  refresh: () => Promise<void>;
  loadMore: () => Promise<void>;
  updateStatus: (
    reference: string,
    status: IncidentStatus,
    note?: string,
  ) => Promise<void>;
}

let latestRefresh = 0;

export const useIncidentStore = create<IncidentState>((set, get) => ({
  items: [],
  overview: null,
  filters: { status: null, query: '', severity: null },
  loading: false,
  loadingMore: false,
  page: 0,
  totalElements: 0,
  hasMore: true,
  unreachable: false,
  stale: false,
  error: null,

  setStatusFilter: (status) => {
    set({
      filters: { ...get().filters, status },
      page: 0,
      totalElements: 0,
      hasMore: true,
    });
  },

  setQuery: (query) => {
    set({
      filters: { ...get().filters, query },
      page: 0,
      totalElements: 0,
      hasMore: true,
    });
  },

  setSeverityFilter: (severity) => {
    set({ filters: { ...get().filters, severity } });
  },

  async refresh() {
    const filters = { ...get().filters };
    const requestedPage = get().page;
    const id = ++latestRefresh;
    const token = useAuthStore.getState().token;

    const current = () =>
      id === latestRefresh &&
      useAuthStore.getState().token === token;

    if (get().items.length === 0) {
      set({ loading: true });
    }

    set({ error: null });

    const fetchPage = (pageNumber: number) =>
      filters.query
        ? api.search(filters.query, pageNumber)
        : api.incidents(filters.status ?? undefined, pageNumber);

    try {
      // Fetch the first page to determine the current result count.
      const firstPage = await fetchPage(0);

      const lastPage = Math.max(
        0,
        Math.min(requestedPage, firstPage.totalPages - 1),
      );

      // Reload every page already displayed so a background refresh
      // doesn't reduce the queue to the first 25 incidents.
      const remainingPages = await Promise.all(
        Array.from(
          { length: lastPage },
          (_, index) => fetchPage(index + 1),
        ),
      );

      const overview = await api.overview();

      if (!current()) return;

      const allItems = [
        ...firstPage.content,
        ...remainingPages.flatMap((result) => result.content),
      ];

      // Deduplicate incidents in case the queue changed between requests.
      const uniqueItems = [
        ...new Map(
          allItems.map((item) => [item.reference, item]),
        ).values(),
      ];

      const items = filters.severity === null
        ? uniqueItems
        : uniqueItems.filter(
            (item) => item.severity === filters.severity,
          );

      set({
        items,
        overview,
        page: lastPage,
        totalElements: firstPage.totalElements,
        hasMore: lastPage + 1 < firstPage.totalPages,
        loading: false,
        loadingMore: false,
        unreachable: false,
        stale: false,
        error: null,
      });
    } catch (cause) {
      if (!current()) return;

      if (cause instanceof ApiError && cause.status === 0) {
  // Keep existing incidents visible during a gateway outage.
  set({
    unreachable: true,
    stale: false,
    loading: false,
    loadingMore: false,
    error: cause.message,
  });
  return;
}

      if (cause instanceof ApiError && cause.isAuthFailure) {
        set({
          items: [],
          overview: null,
          page: 0,
          totalElements: 0,
          hasMore: true,
          loading: false,
          loadingMore: false,
          unreachable: false,
          stale: false,
          error: null,
        });

        useAuthStore.getState().signOut();
        useAuthStore.setState({
          error: 'Your session expired. Sign in again.',
        });
        return;
      }

      // Keep the previous queue if the refresh fails.
      set({
        loading: false,
        loadingMore: false,
        unreachable: false,
        stale: true,
        error: cause instanceof Error
          ? cause.message
          : 'Could not load incidents',
      });
    }
  },

  async loadMore() {
    const state = get();

    if (
      state.loadingMore ||
      state.loading ||
      !state.hasMore
    ) {
      return;
    }

    const filters = { ...state.filters };
    const nextPage = state.page + 1;
    const id = ++latestRefresh;
    const token = useAuthStore.getState().token;

    const current = () =>
      id === latestRefresh &&
      useAuthStore.getState().token === token;

    set({ loadingMore: true, error: null });

    try {
      const result = filters.query
        ? await api.search(filters.query, nextPage)
        : await api.incidents(
            filters.status ?? undefined,
            nextPage,
          );

      if (!current()) return;

      const existing = get().items;
      const combined = [
        ...existing,
        ...result.content,
      ];

      const uniqueItems = [
        ...new Map(
          combined.map((item) => [item.reference, item]),
        ).values(),
      ];

      const items = filters.severity === null
        ? uniqueItems
        : uniqueItems.filter(
            (item) => item.severity === filters.severity,
          );

      set({
        items,
        page: nextPage,
        totalElements: result.totalElements,
        hasMore: nextPage + 1 < result.totalPages,
        loadingMore: false,
        unreachable: false,
        stale: false,
        error: null,
      });
    } catch (cause) {
      if (!current()) return;

      set({
        loadingMore: false,
        stale: true,
        error: cause instanceof Error
          ? cause.message
          : 'Could not load more incidents',
      });
    }
  },

  async updateStatus(reference, status, note) {
    await api.updateStatus(reference, status, note);
    await get().refresh();
  },
}));