import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { EventStream, shouldRefreshList, type StreamEvent } from './events';

/**
 * A socket that must never be the reason the dashboard goes stale.
 *
 * <p>The contract under test is that every failure mode falls back to polling
 * rather than to a frozen screen, and that a reconnect always implies a
 * refresh because the stream carries no replay.
 */
describe('EventStream', () => {
  let sockets: FakeSocket[];
  let stream: EventStream | null;

  class FakeSocket {
    static OPEN = 1;
    static CONNECTING = 0;
    readyState = 0;
    onopen: (() => void) | null = null;
    onmessage: ((event: MessageEvent<string>) => void) | null = null;
    onerror: (() => void) | null = null;
    onclose: (() => void) | null = null;
    readonly protocols: string | string[];

    constructor(public url: string, protocols: string | string[]) {
      this.protocols = protocols;
      sockets.push(this);
    }

    close(): void {
      this.readyState = 3;
      this.onclose?.();
    }
  }

  beforeEach(() => {
    sockets = [];
    vi.stubGlobal('WebSocket', FakeSocket);
    vi.useFakeTimers();
    stream = null;
  });

  afterEach(() => {
    stream?.stop();
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('sends the token in a subprotocol, never in the URL', () => {
    stream = new EventStream({ token: () => 'secret-token', onEvent: () => {} });
    stream.start();

    expect(sockets).toHaveLength(1);
    expect(sockets[0]!.url).not.toContain('secret-token');
    expect(sockets[0]!.protocols).toEqual(['resqnet.v1', 'bearer.secret-token']);
  });

  it('does not open a socket without a token', () => {
    stream = new EventStream({ token: () => null, onEvent: () => {} });
    stream.start();

    expect(sockets).toHaveLength(0);
  });

  it('reports connected on open', () => {
    const seen: boolean[] = [];

    stream = new EventStream({
      token: () => 't',
      onEvent: () => {},
      onConnectionChange: (connected) => seen.push(connected),
    });

    stream.start();
    sockets[0]!.onopen?.();

    expect(seen).toContain(true);
    expect(stream.state).toBe('open');
  });

  it('reports disconnected when the socket closes', () => {
    const seen: boolean[] = [];

    stream = new EventStream({
      token: () => 't',
      onEvent: () => {},
      onConnectionChange: (connected) => seen.push(connected),
    });

    stream.start();
    sockets[0]!.onopen?.();
    sockets[0]!.onclose?.();

    expect(seen[seen.length - 1]).toBe(false);
    expect(stream.state).toBe('retrying');
  });

  it('retries with backoff after an unexpected close', () => {
    stream = new EventStream({ token: () => 't', onEvent: () => {} });
    stream.start();
    sockets[0]!.onopen?.();
    sockets[0]!.onclose?.();

    expect(stream.state).toBe('retrying');
    expect(sockets).toHaveLength(1);

    vi.advanceTimersByTime(1_000);
    expect(sockets).toHaveLength(2);

    // Second failure waits longer, so a down gateway is not hammered.
    sockets[1]!.onclose?.();
    vi.advanceTimersByTime(1_000);
    expect(sockets).toHaveLength(2);
    vi.advanceTimersByTime(1_000);
    expect(sockets).toHaveLength(3);
  });

  it('stops retrying once stopped', () => {
    const changes: boolean[] = [];

    stream = new EventStream({
      token: () => 't',
      onEvent: () => {},
      onConnectionChange: (connected) => changes.push(connected),
    });

    stream.start();
    sockets[0]!.onopen?.();
    stream.stop();

    vi.advanceTimersByTime(60_000);

    expect(sockets).toHaveLength(1);
    expect(stream.state).toBe('idle');
    expect(changes[changes.length - 1]).toBe(false);
  });

  it('delivers parsed events and ignores keepalives', () => {
    const events: StreamEvent[] = [];

    stream = new EventStream({
      token: () => 't',
      onEvent: (event) => events.push(event),
    });

    stream.start();

    sockets[0]!.onmessage?.({
      data: JSON.stringify({
        type: 'incident.created',
        reference: 'RQ-1',
        at: '',
        detail: '',
        incident: null,
      }),
    } as MessageEvent<string>);

    sockets[0]!.onmessage?.({
      data: '{"type":"pong"}',
    } as MessageEvent<string>);

    sockets[0]!.onmessage?.({
      data: '{"type":"connected"}',
    } as MessageEvent<string>);

    sockets[0]!.onmessage?.({
      data: 'not json',
    } as MessageEvent<string>);

    expect(events.map((event) => event.type)).toEqual(['incident.created']);
  });

  it('marks incident events as needing a list refresh', () => {
    const base = { reference: null, at: '', detail: '', incident: null };

    expect(shouldRefreshList({ ...base, type: 'incident.created' })).toBe(true);
    expect(shouldRefreshList({ ...base, type: 'incident.resolved' })).toBe(true);
    expect(shouldRefreshList({ ...base, type: 'gateway.sync' })).toBe(false);
    expect(shouldRefreshList({ ...base, type: 'simulation.ingested' })).toBe(false);
  });
});
