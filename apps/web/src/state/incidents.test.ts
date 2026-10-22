
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import type { IncidentSummary, Overview, Page } from '@/lib/api';

const { apiMock } = vi.hoisted(() => ({
  apiMock: {
    incidents: vi.fn(),
    search: vi.fn(),
    overview: vi.fn(),
    updateStatus: vi.fn(),
  },
}));

vi.mock('@/lib/api', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/api')>();

  return {
    ...actual,
    api: apiMock,
  };
});

import { useIncidentStore } from './incidents';

function incident(
  reference: string,
  severity = 1,
): IncidentSummary {
  return {
    reference,
    title: `Incident ${reference}`,
    categoryLabel: 'Test',
    severity,
    status: 'NEW',
  } as IncidentSummary;
}

function page(
  content: IncidentSummary[],
  number = 0,
  totalPages = 1,
): Page<IncidentSummary> {
  return {
    content,
    number,
    size: 25,
    totalPages,
    totalElements: totalPages * 25,
  };
}

const overview = {
  open: 1,
  critical: 0,
  total: 1,
  newIncidents: 1,
  resolved: 0,
} as Overview;

function resetStore() {
  useIncidentStore.setState({
    items: [],
    overview: null,
    filters: { status: null, query: '', severity: null },
    loading: false,
    loadingMore: false,
    page: 0,
    totalPages: 0,
    totalElements: 0,
    unreachable: false,
    error: null,
  });
}

describe('incident store pagination regressions', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    resetStore();

    apiMock.incidents.mockImplementation(async (_status, pageNumber = 0) =>
      page([], pageNumber, 1),
    );
    apiMock.search.mockImplementation(async (_query, pageNumber = 0) =>
      page([], pageNumber, 1),
    );
    apiMock.overview.mockResolvedValue(overview);
    apiMock.updateStatus.mockResolvedValue(undefined);
  });

  afterEach(() => {
    vi.clearAllMocks();
  });

  it('loads another page and does not duplicate incident references', async () => {
    apiMock.incidents
      .mockResolvedValueOnce(
        page([incident('RQ-1'), incident('RQ-2')], 0, 2),
      )
      .mockResolvedValueOnce(
        page([incident('RQ-2'), incident('RQ-3')], 1, 2),
      );

    await useIncidentStore.getState().refresh();
    await useIncidentStore.getState().loadMore();

    expect(useIncidentStore.getState().items.map((item) => item.reference))
      .toEqual(['RQ-1', 'RQ-2', 'RQ-3']);
    expect(useIncidentStore.getState().page).toBe(1);
  });

  it('refreshes results when the status filter changes', async () => {
    apiMock.incidents.mockResolvedValue(
      page([incident('RQ-NEW')]),
    );

    useIncidentStore.getState().setStatusFilter('RESOLVED');

    await vi.waitFor(() => {
      expect(apiMock.incidents).toHaveBeenCalledWith('RESOLVED', 0);
    });

    await vi.waitFor(() => {
      expect(useIncidentStore.getState().items.map((item) => item.reference))
        .toEqual(['RQ-NEW']);
    });
  });

  it('uses search results when the query changes', async () => {
    apiMock.search.mockResolvedValue(
      page([incident('RQ-SEARCH')]),
    );

    useIncidentStore.getState().setQuery('broken road');

    await vi.waitFor(() => {
      expect(apiMock.search).toHaveBeenCalledWith('broken road', 0);
    });

    await vi.waitFor(() => {
      expect(useIncidentStore.getState().items.map((item) => item.reference))
        .toEqual(['RQ-SEARCH']);
    });
  });

  it('handles empty results without leaving the store loading', async () => {
    apiMock.incidents.mockResolvedValue(page([]));

    await useIncidentStore.getState().refresh();

    expect(useIncidentStore.getState().items).toEqual([]);
    expect(useIncidentStore.getState().loading).toBe(false);
    expect(useIncidentStore.getState().error).toBeNull();
  });

  it('includes matching severity incidents from later pages', async () => {
    apiMock.incidents
      .mockResolvedValueOnce(
        page([incident('RQ-LOW', 1)], 0, 2),
      )
      .mockResolvedValueOnce(
        page([incident('RQ-HIGH', 3)], 1, 2),
      );

    useIncidentStore.setState({
      filters: { status: null, query: '', severity: 3 },
    });

    await useIncidentStore.getState().refresh();

    expect(apiMock.incidents).toHaveBeenCalledWith(undefined, 1);
    expect(useIncidentStore.getState().items.map((item) => item.reference))
      .toEqual(['RQ-HIGH']);
  });

  it('does not let an older refresh overwrite a newer filter result', async () => {
    let resolveOld!: (value: Page<IncidentSummary>) => void;

    apiMock.incidents.mockImplementation((_status, pageNumber = 0) => {
      if (pageNumber === 0) {
        return new Promise<Page<IncidentSummary>>((resolve) => {
          resolveOld = resolve;
        });
      }

      return Promise.resolve(page([], pageNumber, 1));
    });

    const oldRefresh = useIncidentStore.getState().refresh();

    useIncidentStore.setState({
      filters: { status: 'RESOLVED', query: '', severity: null },
    });

    apiMock.incidents.mockResolvedValue(
      page([incident('RQ-LATEST')]),
    );

    const newRefresh = useIncidentStore.getState().refresh();
    await newRefresh;

    resolveOld(page([incident('RQ-STALE')]));
    await oldRefresh;

    expect(useIncidentStore.getState().items.map((item) => item.reference))
      .toEqual(['RQ-LATEST']);
  });
});
