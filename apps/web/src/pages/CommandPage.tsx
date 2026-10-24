import { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';

import { CommandHeader } from '@/components/command/CommandHeader';
import { CreateIncidentDialog } from '@/components/command/CreateIncidentDialog';
import { IncidentDetailPanel } from '@/components/command/IncidentDetailPanel';
import { IncidentQueue } from '@/components/command/IncidentQueue';
import { MapPanel } from '@/components/command/MapPanel';
import { OverviewTiles } from '@/components/command/OverviewTiles';
import { TopologyPanel } from '@/components/command/TopologyPanel';
import {
  api,
  authStorage,
  type IncidentDetail,
  type IncidentStatus,
  type ResponderAccount,
} from '@/lib/api';
import { EventStream, shouldRefreshList } from '@/lib/events';
import { useAuthStore } from '@/state/auth';
import { useIncidentStore } from '@/state/incidents';
import { useSimulationStore } from '@/state/simulation';

const POLL_INTERVAL_MS = 15_000;

/**
 * Responder command centre.
 *
 * <p>Three panels, each answering one question: what needs attention, what
 * happened to a specific message, and what the network is doing. Polling is
 * deliberately interval-based rather than socket-driven for the incident list;
 * a responder watching a queue does not need sub-second updates, and a quiet
 * socket that reconnects in a loop is worse than a fifteen second poll.
 */
export function CommandPage() {
  const identity = useAuthStore((state) => state.identity);
  const signOut = useAuthStore((state) => state.signOut);
  const navigate = useNavigate();

  const incidents = useIncidentStore((state) => state.items);
  const overview = useIncidentStore((state) => state.overview);
  const loading = useIncidentStore((state) => state.loading);
  const unreachable = useIncidentStore((state) => state.unreachable);
  const stale = useIncidentStore((state) => state.stale);
  const refreshError = useIncidentStore((state) => state.error);
  const refresh = useIncidentStore((state) => state.refresh);
  const updateStatus = useIncidentStore((state) => state.updateStatus);
  const setStatusFilter = useIncidentStore((state) => state.setStatusFilter);
  const statusFilter = useIncidentStore((state) => state.filters.status);
  const setQuery = useIncidentStore((state) => state.setQuery);

  const { runs, activeRun, events, loadRuns, selectRun } = useSimulationStore();

  const [live, setLive] = useState(false);
  const [creating, setCreating] = useState(false);
  const [accounts, setAccounts] = useState<ResponderAccount[]>([]);

  const [selected, setSelected] = useState<string | null>(null);
  const [detail, setDetail] = useState<IncidentDetail | null>(null);
  const [detailLoading, setDetailLoading] = useState(false);

  useEffect(() => {
    if (!identity) return;
    void refresh();
    void loadRuns();
  }, [identity, refresh, loadRuns]);

  // Assignable accounts are admin-only on the server, so the list is fetched
  // only for that role and a failure is ignored rather than surfaced: an
  // operator who cannot see the list simply cannot assign, which is the
  // server's decision and not an error worth interrupting them for.
  useEffect(() => {
    if (identity?.role !== 'ADMIN') {
      setAccounts([]);
      return;
    }

    let cancelled = false;

    void api.accounts()
      .then((list) => {
        if (!cancelled) setAccounts(list);
      })
      .catch(() => {
        if (!cancelled) setAccounts([]);
      });

    return () => {
      cancelled = true;
    };
  }, [identity]);

  /**
   * Live updates, with polling kept underneath.
   *
   * <p>The stream is an accelerator, not a dependency. Polling continues
   * whether the socket is open, retrying, or permanently disconnected.
   * Every connection transition triggers a refresh because the stream carries
   * no replay, so missed events are recovered by asking again.
   */
  useEffect(() => {
    if (!identity) return undefined;

    const stream = new EventStream({
      token: () => authStorage.token(),
      onEvent: (event) => {
        if (shouldRefreshList(event)) {
          void refresh();
        } else if (event.type === 'simulation.ingested') {
          void loadRuns();
        }
      },
      onConnectionChange: (connected) => {
        setLive(connected);
        // Reconcile the view on open and close so missed events are recovered.
        void refresh();
      },
    });

    stream.start();

    return () => stream.stop();
  }, [identity, refresh, loadRuns]);

  useEffect(() => {
    if (!identity) return undefined;

    const timer = window.setInterval(() => {
      if (document.hidden) return;
      void refresh();
    }, POLL_INTERVAL_MS);

    return () => window.clearInterval(timer);
  }, [identity, refresh]);

  useEffect(() => {
    if (!selected) {
      setDetail(null);
      return;
    }

    let cancelled = false;
    setDetailLoading(true);

    api
      .incident(selected)
      .then((result) => {
        if (!cancelled) setDetail(result);
      })
      .catch(() => {
        if (!cancelled) setDetail(null);
      })
      .finally(() => {
        if (!cancelled) setDetailLoading(false);
      });

    return () => {
      cancelled = true;
    };
  }, [selected, incidents]);

  if (!identity) {
    return <SignInGate onSignIn={(ok) => (ok ? undefined : undefined)} />;
  }

  const handleStatusChange = async (
    reference: string,
    status: IncidentStatus,
    note?: string,
  ) => {
    await updateStatus(reference, status, note);
  };

  return (
    <div className="flex min-h-screen flex-col bg-base-900">
      {/* Reachability, not idleness. Tying this to `loading` made the
          indicator alternate on every 15-second poll, which trains an operator
          to ignore it. */}
      <CommandHeader
        connected={!unreachable}
        unreachable={unreachable}
        live={live && !unreachable && !stale}
        stale={stale}
        onCreateIncident={identity ? () => setCreating(true) : undefined}
      />

      {/*
        One live region for everything that changes without a page load.

        `aria-live="polite"` rather than "assertive" because a new incident is
        information the user can read when they look. Assertive would interrupt
        them mid-sentence on every poll.
      */}
      <div className="sr-only" role="status" aria-live="polite" aria-atomic="true">
        {unreachable
          ? 'The coordination gateway is unreachable. The incident list below may be out of date.'
          : stale
            ? 'The last refresh failed. The incident list below may be out of date.'
            : incidents.length === 1
              ? '1 incident in the queue.'
              : `${incidents.length} incidents in the queue.`}
        {overview ? ` ${overview.open} open, ${overview.critical} critical.` : ''}
      </div>

      <main className="mx-auto w-full max-w-[1600px] flex-1 px-5 py-6 sm:px-8">
        {stale ? (
          <div
            role="alert"
            className="mb-5 rounded-lg border border-emergency/45 bg-emergency-soft px-4 py-3 text-sm text-emergency"
          >
            The last refresh failed{refreshError ? ` (${refreshError})` : ''}. The incident
            list is from the previous refresh and may be out of date.
          </div>
        ) : null}

        <OverviewTiles
          overview={overview}
          nodeCount={activeRun?.nodeCount ?? null}
          eventCount={events.length || null}
        />

        <div className="mt-6 grid gap-5 xl:grid-cols-[minmax(320px,380px)_minmax(0,1fr)]">
          <div className="surface flex max-h-[calc(100vh-15rem)] flex-col p-4">
            <IncidentQueue
              incidents={incidents}
              selected={selected}
              loading={loading}
              unreachable={unreachable}
              activeStatus={statusFilter}
              onSelect={setSelected}
              onFilterChange={(patch) => {
                if (patch.status !== undefined) setStatusFilter(patch.status);
                if (patch.query !== undefined) setQuery(patch.query);
              }}
            />
          </div>

          <div className="grid min-w-0 gap-5">
            <div className="min-h-[26rem]">
              <IncidentDetailPanel
                detail={detail}
                loading={detailLoading}
                role={identity.role}
                onStatusChange={handleStatusChange}
                accounts={accounts}
                onAssign={async (reference, accountId) => {
                  if (accountId === null) {
                    // There is no unassign endpoint, so this is stated rather
                    // than faked. The audit trail records the assignment, which
                    // is what an investigator needs anyway.
                    throw new Error(
                      'There is no way to clear an assignment. Reassign it to '
                      + 'somebody else instead.',
                    );
                  }

                  await api.assign(reference, accountId);
                  await refresh();
                }}
              />
            </div>

            <div className="grid gap-5 lg:grid-cols-2">
              <MapPanel incidents={incidents} selected={selected} onSelect={setSelected} />

              <div className="flex flex-col gap-5">
                {runs.length > 1 ? (
                  <div className="surface p-4">
                    <label className="eyebrow" htmlFor="run-select">
                      Simulation run
                    </label>

                    <select
                      id="run-select"
                      value={activeRun?.runId ?? ''}
                      onChange={(event) => void selectRun(event.target.value)}
                      className="mt-2 w-full rounded-lg border border-base-600 bg-base-850 px-3 py-2 text-[13px] text-ink-primary focus-visible:border-network focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-network"
                    >
                      {runs.map((run) => (
                        <option key={run.runId} value={run.runId}>
                          {run.topology} · {run.nodeCount} nodes · seed {run.seed}
                        </option>
                      ))}
                    </select>
                  </div>
                ) : null}

                <TopologyPanel run={activeRun} events={events} />
              </div>
            </div>
          </div>
        </div>
      </main>

      <CreateIncidentDialog
        open={creating}
        onClose={() => setCreating(false)}
        onCreate={async (input) => {
          const created = await api.createIncident(input);
          await refresh();
          setSelected(created.reference);
          return created;
        }}
      />
    </div>
  );

  // Unreachable in practice; kept explicit so a failed sign-in cannot fall
  // through to a half-authenticated console.
  function SignInGate(_props: { onSignIn: (ok: boolean) => void }): React.ReactElement {
    return (
      <div className="grid min-h-screen place-items-center px-6">
        <div className="surface max-w-sm p-8 text-center">
          <h1 className="text-lg font-semibold text-ink-primary">Sign in required</h1>
          <p className="mt-2 text-sm text-ink-secondary">
            Your session ended. Sign in again to continue.
          </p>
          <button
            type="button"
            onClick={() => {
              // Reloading /command brought this gate straight back. Go to the
              // sign-in page instead, and only sign out if a token is still
              // stored: an expired session is already signed out, and signing
              // out again would clear the message that says why.
              if (useAuthStore.getState().token) signOut();
              navigate('/signin', { replace: true });
            }}
            className="mt-6 min-h-[44px] w-full rounded-xl bg-network px-4 text-sm font-medium text-base-900"
          >
            Return to sign in
          </button>
        </div>
      </div>
    );
  }
}
