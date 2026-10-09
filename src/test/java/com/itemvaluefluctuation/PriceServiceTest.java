/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Covers the traffic invariants of {@link PriceService} (single-flight, de-dupe, rate ceiling,
 * backoff, degraded /latest, restart safety) against a fake in-process API, never the network.
 *
 * <p>Synchronisation is event-driven, never time-driven: tests wait on a monitor that is signalled
 * when a fake request starts, when the service notifies its update listener, and when an OkHttp
 * call has fully finished (including its callback). Timeouts only bound how long a broken test
 * can hang.
 */
public class PriceServiceTest
{
	private static final long TIMEOUT_MS = 10_000L;
	private static final String TS_BODY =
		"{\"data\":[{\"timestamp\":1,\"avgLowPrice\":100},{\"timestamp\":2,\"avgLowPrice\":120}]}";
	private static final String LATEST_BODY = "{\"data\":{\"1\":{\"low\":150},\"2\":{\"low\":null}}}";

	/** Signalled on every observable event; waiters re-check their condition under this lock. */
	private final Object monitor = new Object();

	/** Number of OkHttp calls that have completely finished, callback included. */
	private final AtomicInteger finishedCalls = new AtomicInteger();

	private FakeApi api;
	private ThreadPoolExecutor executor;
	private OkHttpClient client;
	private PriceService service;

	private void init(long gapMs, int maxPerWindow, long windowMs)
	{
		init(gapMs, maxPerWindow, windowMs, 30 * 60_000L, 5 * 60_000L);
	}

	/** Pins in-flight concurrency so ordering/lifecycle assertions are deterministic. */
	private void initConcurrency(int maxInFlight, long gapMs, int maxPerWindow, long windowMs)
	{
		api = new FakeApi(this::signal);

		executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
			new SynchronousQueue<>())
		{
			@Override
			protected void afterExecute(Runnable r, Throwable t)
			{
				finishedCalls.incrementAndGet();
				signal();
			}
		};

		client = new OkHttpClient.Builder()
			.dispatcher(new Dispatcher(executor))
			.addInterceptor(api)
			.build();
		service = new PriceService(client, new Gson(), maxInFlight, gapMs, maxPerWindow, windowMs);
		service.setUpdateListener(this::signal);
		service.start();
	}

	private void init(long gapMs, int maxPerWindow, long windowMs, long timeseriesTtlMs,
		long latestTtlMs)
	{
		api = new FakeApi(this::signal);

		// afterExecute runs after the whole async call (interceptors, callback, dispatcher
		// bookkeeping), which is the precise "this call is fully done" event the tests need.
		executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
			new SynchronousQueue<>())
		{
			@Override
			protected void afterExecute(Runnable r, Throwable t)
			{
				finishedCalls.incrementAndGet();
				signal();
			}
		};

		client = new OkHttpClient.Builder()
			.dispatcher(new Dispatcher(executor))
			.addInterceptor(api)
			.build();
		service = new PriceService(client, new Gson(), gapMs, maxPerWindow, windowMs,
			timeseriesTtlMs, latestTtlMs);
		service.setUpdateListener(this::signal);
		service.start();
	}

	@After
	public void tearDown()
	{
		if (api != null)
		{
			api.openAllGates();
		}
		if (service != null)
		{
			service.shutdown();
		}
		if (executor != null)
		{
			executor.shutdown();
		}
	}

	@Test
	public void singleFlightFifoAndDedupe() throws Exception
	{
		initConcurrency(1, 20, 1000, 60_000); // pin to 1 in flight to assert FIFO order
		api.gates.put(1, new CountDownLatch(1));

		for (int i = 0; i < 5; i++)
		{
			service.request(1, TimeFrame.DAY); // repeated: must not be queued twice
		}
		service.request(2, TimeFrame.DAY);
		service.request(3, TimeFrame.DAY);

		// Request 1 is held open at its gate, so 2 and 3 cannot have started: single-flight.
		assertTrue(await(() -> api.startedIds().size() == 1, TIMEOUT_MS));
		assertEquals(Collections.singletonList("1"), api.startedIds());
		// Counts are exact because request() is synchronous: 1 in flight, 2 and 3 queued, no dupes.
		assertEquals(3, service.pendingCount(TimeFrame.DAY));

		api.gates.get(1).countDown();
		assertTrue(await(() -> service.getCached(3, TimeFrame.DAY) != null, TIMEOUT_MS));

		assertEquals(List.of("1", "2", "3"), api.startedIds());
		assertEquals(1, api.maxConcurrentTimeseries.get());
		assertEquals(0, service.pendingCount(TimeFrame.DAY));
	}

	@Test
	public void everyRequestCarriesDescriptiveUserAgent() throws Exception
	{
		init(5, 1000, 60_000);
		service.request(1, TimeFrame.DAY);
		assertTrue(await(() -> service.getCached(1, TimeFrame.DAY) != null, TIMEOUT_MS));

		assertTrue(api.latestCalls.get() >= 1);
		assertTrue(api.userAgents.size() >= 2);
		for (String ua : api.userAgents)
		{
			assertTrue(ua, ua.startsWith("item-value-fluctuation RuneLite plugin"));
		}
	}

	@Test
	public void tokenBucketLimitsAverageRateAndDisallowsBurstBeyondCapacity() throws Exception
	{
		// Refill rate = cap / window = 2 / 200 = 0.01 tokens/ms (one token per 100ms). This ctor
		// path uses the production maxInFlight (4), which is also the bucket capacity.
		final int cap = 2;
		final long window = 200;
		final int capacity = 4; // == MAX_IN_FLIGHT_TIMESERIES
		final long refillIntervalMs = window / cap; // 100ms per token
		final int n = 8;
		init(5, cap, window);

		for (int id = 1; id <= n; id++)
		{
			service.request(id, TimeFrame.DAY);
		}

		assertTrue(await(() -> api.startedIds().size() == n, TIMEOUT_MS));
		final List<Long> times = api.startTimes();

		// (a) Average rate: starts after the initial full-bucket burst are paced at the refill
		// rate. From the first start, the (capacity)-th start drained the initial bucket, so the
		// remaining (n - capacity) starts take at least (n - capacity) refill intervals.
		final long elapsed = times.get(n - 1) - times.get(capacity - 1);
		final long minElapsed = (long) (n - capacity) * refillIntervalMs - 100;
		assertTrue("sustained start rate exceeded the token refill rate: elapsed=" + elapsed,
			elapsed >= minElapsed);

		// (b) No burst beyond capacity: once the initial bucket is drained, each further start is
		// spaced from the previous one by at least the refill interval.
		for (int i = capacity; i < times.size(); i++)
		{
			assertTrue("start " + i + " burst past the token-bucket refill interval",
				times.get(i) - times.get(i - 1) >= refillIntervalMs - 50);
		}
	}

	/**
	 * {@link PriceService#request} reports whether it ACTUALLY admitted new work, so the overlay's
	 * per-pass prefetch bound counts real admissions only. Without this, cached (and queued/in-
	 * flight/backing-off) keys would be counted as new work every render, letting the first N keys
	 * exhaust the per-pass ceiling and permanently starve later bank items (the YEAR-prefetch
	 * prefix-starvation bug). Here: a fresh key admits once (true), is a no-op while queued/in
	 * flight and once cached (false), while a brand-new key still admits (true).
	 */
	@Test
	public void requestReportsOnlyRealAdmissionsSoNoOpsDoNotConsumeBudget() throws Exception
	{
		initConcurrency(1, 20, 1000, 60_000); // pin to 1 in flight for deterministic queue state
		api.gates.put(1, new CountDownLatch(1)); // hold id 1 open so we can observe in-flight

		// First request for id 1 admits a new job.
		assertTrue(service.request(1, TimeFrame.DAY));
		assertTrue(await(() -> api.startedIds().size() == 1, TIMEOUT_MS));

		// id 1 is in flight (held at its gate): re-requesting it is a no-op, must NOT count.
		assertFalse(service.request(1, TimeFrame.DAY));

		// A different key admits.
		assertTrue(service.request(2, TimeFrame.DAY));
		// id 2 is now queued behind the single in-flight slot: re-requesting is a no-op.
		assertFalse(service.request(2, TimeFrame.DAY));

		// Let everything drain and cache.
		api.gates.get(1).countDown();
		assertTrue(await(() -> service.getCached(2, TimeFrame.DAY) != null, TIMEOUT_MS));

		// Now cached-fresh: re-requesting either key is a no-op and must NOT be counted as work.
		assertFalse(service.request(1, TimeFrame.DAY));
		assertFalse(service.request(2, TimeFrame.DAY));

		// A later, still-uncached key is reached and admitted (not starved by the no-ops above).
		assertTrue(service.request(3, TimeFrame.DAY));
	}

	@Test
	public void failedKeyBacksOffInsteadOfRetryingImmediately() throws Exception
	{
		init(5, 1000, 60_000);
		api.timeseriesFails = true;

		service.request(1, TimeFrame.DAY);
		// Wait until the failed call (callback included) has fully finished.
		assertTrue(await(() -> api.startedIds().size() == 1 && finishedCalls.get() >= 1
			&& service.pendingCount(TimeFrame.DAY) == 0, TIMEOUT_MS));

		for (int i = 0; i < 5; i++)
		{
			service.request(1, TimeFrame.DAY);
			// request() is synchronous: a backed-off key must not be queued at all, so nothing
			// can ever start for it.
			assertEquals(0, service.pendingCount(TimeFrame.DAY));
		}

		assertEquals(1, api.startedIds().size());
		assertNull(service.getCached(1, TimeFrame.DAY));
	}

	@Test
	public void degradedLatestNeverLoadsAndKeepsUnknownPricesOutOfTheQueue() throws Exception
	{
		init(5, 1000, 60_000);
		api.latestFails = true;

		service.ensureLatestFresh();
		assertTrue(await(() -> service.isLatestRetrying(), TIMEOUT_MS));

		assertFalse(service.isLatestLoaded());
		assertNull(service.getLatestLow(1));
		// The overlay's pre-check must reject an unknown price, so nothing is ever requested.
		assertFalse(ItemValueFluctuationOverlay.passesPreCheck(service.getLatestLow(1), 1, 0, 0));
		assertEquals(0, api.startedIds().size());
	}

	@Test
	public void preCheckAppliesValueFilters()
	{
		assertFalse(ItemValueFluctuationOverlay.passesPreCheck(null, 1, 1000, 100_000));
		assertFalse(ItemValueFluctuationOverlay.passesPreCheck(500L, 1000, 1000, 100_000));
		assertFalse(ItemValueFluctuationOverlay.passesPreCheck(5_000L, 3, 1000, 100_000));
		assertTrue(ItemValueFluctuationOverlay.passesPreCheck(5_000L, 20, 1000, 100_000));
		assertTrue(ItemValueFluctuationOverlay.passesPreCheck(50_000L, 0, 1000, 10_000));
	}

	@Test
	public void bulkLatestPriceIsUsedAsCurrentPrice() throws Exception
	{
		init(5, 1000, 60_000);
		service.ensureLatestFresh();
		assertTrue(await(() -> service.isLatestLoaded(), TIMEOUT_MS));
		assertEquals(Long.valueOf(150), service.getLatestLow(1));
		assertNull(service.getLatestLow(2)); // null low is skipped

		service.request(1, TimeFrame.DAY);
		assertTrue(await(() -> service.getCached(1, TimeFrame.DAY) != null, TIMEOUT_MS));

		final PriceFluctuation f = service.getCached(1, TimeFrame.DAY);
		assertNotNull(f);
		assertEquals(150L, f.getCurrentPrice());
		assertEquals(100L, f.getAnchorPrice());
		assertEquals(50.0, f.getPercentChange(), 0.0001);
		assertEquals(1, api.latestCalls.get());
	}

	@Test
	public void lateCallbackFromBeforeRestartCannotCorruptNewLifecycle() throws Exception
	{
		initConcurrency(1, 5, 1000, 60_000); // pin to 1 in flight to assert restart ordering
		api.gates.put(1, new CountDownLatch(1));
		api.gates.put(2, new CountDownLatch(1));

		service.request(1, TimeFrame.DAY);
		assertTrue(await(() -> api.startedIds().size() == 1, TIMEOUT_MS));

		// Restart while request 1 is still in flight.
		service.shutdown();
		service.start();

		service.request(2, TimeFrame.DAY);
		service.request(3, TimeFrame.DAY);
		assertTrue(await(() -> api.startedIds().size() == 2, TIMEOUT_MS));
		assertEquals(List.of("1", "2"), api.startedIds());
		assertEquals(2, service.pendingCount(TimeFrame.DAY)); // 2 in flight, 3 queued

		// The old call finishes now. Wait until it has fully completed (callback included); it
		// must neither free request 2's slot nor write its result.
		final int finishedBefore = finishedCalls.get();
		api.gates.get(1).countDown();
		assertTrue(await(() -> finishedCalls.get() > finishedBefore, TIMEOUT_MS));
		assertEquals(List.of("1", "2"), api.startedIds());
		assertEquals(2, service.pendingCount(TimeFrame.DAY));
		assertNull(service.getCached(1, TimeFrame.DAY));

		api.gates.get(2).countDown();
		assertTrue(await(() -> service.getCached(3, TimeFrame.DAY) != null, TIMEOUT_MS));
		assertNotNull(service.getCached(2, TimeFrame.DAY));
		assertEquals(List.of("1", "2", "3"), api.startedIds());
	}

	@Test
	public void newKeyAdmittedDuringTheGapStillWaitsForTheGap() throws Exception
	{
		final long gap = 400;
		init(gap, 1000, 60_000);

		service.request(1, TimeFrame.DAY);
		// The cache is written under the same lock that starts the gap, so this request lands
		// inside the gap window deterministically.
		assertTrue(await(() -> service.getCached(1, TimeFrame.DAY) != null, TIMEOUT_MS));
		service.request(2, TimeFrame.DAY);

		assertTrue(await(() -> api.startedIds().size() == 2, TIMEOUT_MS));
		final List<Long> times = api.startTimes();
		// Request 1 does no waiting, so its start is within jitter of its finish.
		assertTrue("start 2 skipped the gap: " + (times.get(1) - times.get(0)),
			times.get(1) - times.get(0) >= gap - 50);
	}

	@Test
	public void expiredEntryKeepsBeingServedAndIsRefreshedExactlyOnce() throws Exception
	{
		// Zero TTL: every cached entry is immediately expired.
		init(400, 1000, 60_000, 0, 5 * 60_000L);

		service.request(1, TimeFrame.DAY);
		assertTrue(await(() -> service.getCached(1, TimeFrame.DAY) != null, TIMEOUT_MS));

		for (int i = 0; i < 5; i++)
		{
			service.request(1, TimeFrame.DAY); // expired: queued once, then de-duped
			assertNotNull(service.getCached(1, TimeFrame.DAY)); // still served meanwhile
		}
		assertEquals(1, service.pendingCount(TimeFrame.DAY));

		assertTrue(await(() -> api.startedIds().size() == 2, TIMEOUT_MS));
		assertEquals(List.of("1", "1"), api.startedIds());
	}

	@Test
	public void refreshedLatestRebasesAlreadyCachedHistory() throws Exception
	{
		// Zero latest TTL: every ensureLatestFresh() refetches the bulk map.
		init(5, 1000, 60_000, 30 * 60_000L, 0);

		service.request(1, TimeFrame.DAY);
		assertTrue(await(() -> service.getCached(1, TimeFrame.DAY) != null, TIMEOUT_MS));
		assertTrue(await(() -> service.getCached(1, TimeFrame.DAY).getCurrentPrice() == 150L,
			TIMEOUT_MS));

		api.latestBody = "{\"data\":{\"1\":{\"low\":200}}}";
		// Re-poked on every wake-up: a /latest call that was already in flight with the old body
		// is simply followed by a new one.
		assertTrue(await(() ->
		{
			service.ensureLatestFresh();
			return service.getCached(1, TimeFrame.DAY).getCurrentPrice() == 200L;
		}, TIMEOUT_MS));

		final PriceFluctuation f = service.getCached(1, TimeFrame.DAY);
		assertEquals(100L, f.getAnchorPrice());
		assertEquals(200L, f.getGpChange() + f.getAnchorPrice());
		assertEquals(100.0, f.getPercentChange(), 0.0001);
		assertEquals(200L, f.getPeriodHigh());
		assertEquals(1, api.startedIds().size()); // no extra history fetch
	}

	private void signal()
	{
		synchronized (monitor)
		{
			monitor.notifyAll();
		}
	}

	/**
	 * Blocks until the condition holds, waking only when an event is signalled. The timeout merely
	 * bounds a broken test; a passing test never waits for it.
	 */
	private boolean await(BooleanSupplier condition, long timeoutMs) throws InterruptedException
	{
		final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
		synchronized (monitor)
		{
			while (!condition.getAsBoolean())
			{
				final long remaining = deadline - System.nanoTime();
				if (remaining <= 0)
				{
					return false;
				}
				TimeUnit.NANOSECONDS.timedWait(monitor, remaining);
			}
			return true;
		}
	}

	/** In-process stand-in for the wiki API; records every request and can block or fail them. */
	private static final class FakeApi implements Interceptor
	{
		private final Runnable onEvent;
		private final List<String> started = Collections.synchronizedList(new ArrayList<>());
		private final List<Long> startedAt = Collections.synchronizedList(new ArrayList<>());
		private final List<String> userAgents = Collections.synchronizedList(new ArrayList<>());
		private final Map<Integer, CountDownLatch> gates = new ConcurrentHashMap<>();
		private final AtomicInteger latestCalls = new AtomicInteger();
		private final AtomicInteger concurrentTimeseries = new AtomicInteger();
		private final AtomicInteger maxConcurrentTimeseries = new AtomicInteger();
		private volatile String latestBody = LATEST_BODY;
		private volatile boolean latestFails;
		private volatile boolean timeseriesFails;

		private FakeApi(Runnable onEvent)
		{
			this.onEvent = onEvent;
		}

		@Override
		public Response intercept(Chain chain) throws IOException
		{
			final Request request = chain.request();
			userAgents.add(String.valueOf(request.header("User-Agent")));

			if (request.url().encodedPath().endsWith("/latest"))
			{
				latestCalls.incrementAndGet();
				if (latestFails)
				{
					throw new IOException("latest unavailable");
				}
				return ok(request, latestBody);
			}

			final String id = request.url().queryParameter("id");
			started.add(id);
			startedAt.add(System.currentTimeMillis());
			final int now = concurrentTimeseries.incrementAndGet();
			maxConcurrentTimeseries.accumulateAndGet(now, Math::max);
			onEvent.run();
			try
			{
				final CountDownLatch gate = gates.get(Integer.parseInt(id));
				if (gate != null)
				{
					try
					{
						gate.await(10, TimeUnit.SECONDS);
					}
					catch (InterruptedException e)
					{
						Thread.currentThread().interrupt();
					}
				}
				if (timeseriesFails)
				{
					throw new IOException("timeout");
				}
				return ok(request, TS_BODY);
			}
			finally
			{
				concurrentTimeseries.decrementAndGet();
			}
		}

		private List<String> startedIds()
		{
			synchronized (started)
			{
				return new ArrayList<>(started);
			}
		}

		private List<Long> startTimes()
		{
			synchronized (startedAt)
			{
				return new ArrayList<>(startedAt);
			}
		}

		private void openAllGates()
		{
			gates.values().forEach(CountDownLatch::countDown);
		}

		private static Response ok(Request request, String json)
		{
			return new Response.Builder()
				.request(request)
				.protocol(Protocol.HTTP_1_1)
				.code(200)
				.message("OK")
				.body(ResponseBody.create(MediaType.parse("application/json"), json))
				.build();
		}
	}
}
