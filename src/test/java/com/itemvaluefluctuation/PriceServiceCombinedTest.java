/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import com.google.gson.Gson;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import net.runelite.api.gameval.ItemID;
import okhttp3.Dispatcher;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Exercises the component-summation maths of {@link PriceService#getCachedCombined} against the
 * same fake in-process API / event-driven pattern as {@link PriceServiceTest}, never the network.
 * Each component's current price is seeded via the bulk {@code /latest} map and its history via a
 * cached {@code /timeseries} response, then the combined figures are asserted.
 */
public class PriceServiceCombinedTest
{
	private static final long TIMEOUT_MS = 10_000L;

	private final Object monitor = new Object();

	private FakeApi api;
	private ThreadPoolExecutor executor;
	private OkHttpClient client;
	private PriceService service;

	@Before
	public void setUp()
	{
		api = new FakeApi(this::signal);

		executor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60, TimeUnit.SECONDS,
			new SynchronousQueue<>());

		client = new OkHttpClient.Builder()
			.dispatcher(new Dispatcher(executor))
			.addInterceptor(api)
			.build();
		service = new PriceService(client, new Gson(), 5L, 1000, 60_000L);
		service.setUpdateListener(this::signal);
		service.start();
	}

	@After
	public void tearDown()
	{
		if (service != null)
		{
			service.shutdown();
		}
		if (executor != null)
		{
			executor.shutdown();
		}
	}

	/** A single plain component must equal the figures the existing getCached returns (no regression). */
	@Test
	public void singleComponentEqualsGetCached() throws Exception
	{
		api.setLatest(100, 150L);
		api.setTimeseries(100, 100L, 120L); // anchor 100, high 120
		seed(100);

		final PriceFluctuation single = service.getCached(100, TimeFrame.DAY);
		final PriceFluctuation combined = service.getCachedCombined(100,
			List.of(new Component(100, 1)), TimeFrame.DAY);
		assertNotNull(single);
		assertNotNull(combined);
		assertEquals(100, combined.getItemId());
		assertEquals(single.getCurrentPrice(), combined.getCurrentPrice());
		assertEquals(single.getAnchorPrice(), combined.getAnchorPrice());
		assertEquals(single.getGpChange(), combined.getGpChange());
		assertEquals(single.getPercentChange(), combined.getPercentChange(), 0.0001);
		assertEquals(single.getPeriodHigh(), combined.getPeriodHigh());
		assertEquals(single.getPeriodLow(), combined.getPeriodLow());
	}

	/** Two components sum their current/anchor and compute pct/gp against the summed anchor. */
	@Test
	public void twoComponentsSumAndComputePercent() throws Exception
	{
		api.setLatest(100, 150L);
		api.setLatest(200, 300L);
		api.setTimeseries(100, 100L, 160L); // anchor 100, high 160, low 100
		api.setTimeseries(200, 200L, 320L); // anchor 200, high 320, low 200
		seed(100);
		seed(200);

		// Parent worth 1x item 100 + 2x item 200.
		final PriceFluctuation f = service.getCachedCombined(42,
			List.of(new Component(100, 1), new Component(200, 2)), TimeFrame.DAY);
		assertNotNull(f);
		assertEquals(42, f.getItemId());
		// current = 150 + 2*300 = 750; anchor = 100 + 2*200 = 500.
		assertEquals(750L, f.getCurrentPrice());
		assertEquals(500L, f.getAnchorPrice());
		assertEquals(250L, f.getGpChange());
		assertEquals(50.0, f.getPercentChange(), 0.0001);
		// high = max(160 + 2*320, 750) = 800; low = min(100 + 2*200, 750) = 500.
		assertEquals(800L, f.getPeriodHigh());
		assertEquals(500L, f.getPeriodLow());
	}

	/** A missing current /latest low for any component yields null. */
	@Test
	public void missingLatestLowYieldsNull() throws Exception
	{
		api.setLatest(100, 150L);
		api.setTimeseries(100, 100L, 120L);
		api.setTimeseries(200, 200L, 220L); // history present, but no /latest low for 200
		seed(100);
		seed(200);

		assertNull(service.getCachedCombined(42,
			List.of(new Component(100, 1), new Component(200, 1)), TimeFrame.DAY));
	}

	/** A missing cached component history for any component yields null. */
	@Test
	public void missingComponentDataYieldsNull() throws Exception
	{
		api.setLatest(100, 150L);
		api.setLatest(200, 300L);
		api.setTimeseries(100, 100L, 120L);
		seed(100); // note: 200's history is never requested/cached

		assertNull(service.getCachedCombined(42,
			List.of(new Component(100, 1), new Component(200, 1)), TimeFrame.DAY));
	}

	/**
	 * The percent-change computation never produces NaN or infinity. The real price layer rejects a
	 * zero anchor at parse time (so a cached anchor is always &gt; 0), but the combined summation
	 * still guards {@code anchorPrice == 0} defensively; a normal small-anchor case must stay finite
	 * and a hand-built zero-anchor component list must yield {@code percentChange == 0}, not NaN.
	 */
	@Test
	public void percentChangeIsAlwaysFiniteAndZeroAnchorIsGuarded() throws Exception
	{
		api.setLatest(100, 1L);
		api.setTimeseries(100, 1L, 1L); // smallest valid anchor
		seed(100);

		final PriceFluctuation finite = service.getCachedCombined(100,
			List.of(new Component(100, 1)), TimeFrame.DAY);
		assertNotNull(finite);
		assertTrue(Double.isFinite(finite.getPercentChange()));

		// A component whose per-item quantity is zero contributes 0 to every sum, so the combined
		// anchor is 0 and the guard must return percentChange 0 rather than dividing by zero.
		final PriceFluctuation zeroAnchor = service.getCachedCombined(100,
			List.of(new Component(100, 0)), TimeFrame.DAY);
		assertNotNull(zeroAnchor);
		assertEquals(0L, zeroAnchor.getAnchorPrice());
		assertEquals(0L, zeroAnchor.getCurrentPrice());
		assertEquals(0L, zeroAnchor.getGpChange());
		assertEquals(0.0, zeroAnchor.getPercentChange(), 0.0001);
	}

	/** Coins/platinum use their fixed gp value and never touch /latest or /timeseries. */
	@Test
	public void fixedPriceCoinsAndPlatinum()
	{
		// 500 coins + 3 platinum tokens, no API data seeded at all.
		final PriceFluctuation f = service.getCachedCombined(ItemID.COINS,
			List.of(new Component(ItemID.COINS, 500), new Component(ItemID.PLATINUM, 3)),
			TimeFrame.DAY);
		assertNotNull(f);
		assertEquals(ItemID.COINS, f.getItemId());
		// 500*1 + 3*1000 = 3500.
		assertEquals(3500L, f.getCurrentPrice());
		assertEquals(3500L, f.getAnchorPrice());
		assertEquals(3500L, f.getPeriodHigh());
		assertEquals(3500L, f.getPeriodLow());
		assertEquals(0L, f.getGpChange());
		assertEquals(0.0, f.getPercentChange(), 0.0001);
		// No timeseries request was ever started for the fixed-price ids.
		assertEquals(0, api.timeseriesStarts());
	}

	/** Loads the bulk /latest map and the per-item timeseries history, waiting until both cached. */
	private void seed(int itemId) throws Exception
	{
		service.ensureLatestFresh();
		await(() -> service.isLatestLoaded(), TIMEOUT_MS);
		service.request(itemId, TimeFrame.DAY);
		await(() -> service.getComponentData(itemId, TimeFrame.DAY) != null, TIMEOUT_MS);
	}

	private void signal()
	{
		synchronized (monitor)
		{
			monitor.notifyAll();
		}
	}

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

	/** In-process stand-in for the wiki API with per-item configurable /latest and /timeseries. */
	private static final class FakeApi implements Interceptor
	{
		private final Runnable onEvent;
		private final Map<Integer, Long> lows = new ConcurrentHashMap<>();
		private final Map<Integer, long[]> histories = new ConcurrentHashMap<>();
		private final AtomicInteger timeseriesStarts = new AtomicInteger();

		private FakeApi(Runnable onEvent)
		{
			this.onEvent = onEvent;
		}

		private void setLatest(int itemId, long low)
		{
			lows.put(itemId, low);
		}

		/** Seeds a two-point timeseries: anchor (first) and a second point at {@code high}. */
		private void setTimeseries(int itemId, long anchor, long high)
		{
			histories.put(itemId, new long[]{anchor, high});
		}

		private int timeseriesStarts()
		{
			return timeseriesStarts.get();
		}

		@Override
		public Response intercept(Chain chain) throws IOException
		{
			final Request request = chain.request();

			if (request.url().encodedPath().endsWith("/latest"))
			{
				return ok(request, latestBody());
			}

			timeseriesStarts.incrementAndGet();
			final int id = Integer.parseInt(request.url().queryParameter("id"));
			onEvent.run();
			final long[] hist = histories.get(id);
			if (hist == null)
			{
				return ok(request, "{\"data\":[]}");
			}
			return ok(request, timeseriesBody(hist[0], hist[1]));
		}

		private String latestBody()
		{
			final StringBuilder sb = new StringBuilder("{\"data\":{");
			boolean first = true;
			for (Map.Entry<Integer, Long> e : lows.entrySet())
			{
				if (!first)
				{
					sb.append(',');
				}
				first = false;
				sb.append('"').append(e.getKey()).append("\":{\"low\":").append(e.getValue())
					.append('}');
			}
			sb.append("}}");
			return sb.toString();
		}

		private static String timeseriesBody(long anchor, long high)
		{
			return "{\"data\":[{\"timestamp\":1,\"avgLowPrice\":" + anchor
				+ "},{\"timestamp\":2,\"avgLowPrice\":" + high + "}]}";
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
