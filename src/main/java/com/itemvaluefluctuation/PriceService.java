/*
 * Copyright (c) 2026, Item Value Fluctuation contributors
 * BSD 2-Clause License (see LICENSE).
 */
package com.itemvaluefluctuation;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Fetches price data from the OSRS Wiki real-time prices API (v2) and computes per-item
 * {@link PriceFluctuation}s for the active {@link TimeFrame}.
 *
 * <p>Scale-safe by design, following the wiki's guidance not to loop {@code /timeseries} per item
 * in a burst:
 * <ul>
 *   <li>Current prices come from ONE bulk {@code /latest} call, cached for {@link #LATEST_TTL}.</li>
 *   <li>History comes from {@code /timeseries}, which is per-item, so those requests go through a
 *       FIFO queue with modest concurrency and a continuous TOKEN-BUCKET rate limiter: starts drip
 *       at a steady {@link #DEFAULT_MAX_REQUESTS_PER_WINDOW} per minute (180/min, 3/s) with only a
 *       small burst capacity, so a timeframe switch on a large bank fills in steadily instead of
 *       bursting and then stalling. Items already queued or in flight are never queued twice.</li>
 *   <li>Results are cached per (item, timeframe) for {@link #TIMESERIES_TTL}; failures back off
 *       exponentially per key.</li>
 *   <li>A descriptive {@code User-Agent} is set on every request (the wiki blocks generic agents).</li>
 *   <li>All HTTP is async via {@link Call#enqueue}; OkHttp callbacks run OFF the client thread, so
 *       this class never touches {@code client} or config &mdash; it only updates its own
 *       concurrent state and notifies a listener, which hops back to the client thread itself.</li>
 *   <li>Every call belongs to a lifecycle generation. {@link #clear()} / {@link #shutdown()}
 *       cancel active calls and bump the generation, so a late callback from before a restart is
 *       ignored and cannot disturb the single-flight or rate-cap state of the new lifecycle.</li>
 * </ul>
 */
@Slf4j
@Singleton
class PriceService
{
	private static final String API_BASE = "https://prices.runescape.wiki/api/v2/osrs";
	private static final String USER_AGENT =
		"item-value-fluctuation RuneLite plugin - @Wyrembelski on GitHub";

	/** How long a per-item timeseries result stays fresh before we refetch. */
	private static final Duration TIMESERIES_TTL = Duration.ofMinutes(30);

	/**
	 * The 1-year series (used only for the near-high ring) barely moves day to day, so it is cached
	 * far longer than the active-timeframe history: the slow first-load populate then happens once
	 * per session rather than on every bank reopen.
	 */
	private static final Duration YEAR_TTL = Duration.ofHours(6);

	/** How long the bulk /latest map stays fresh before we refetch it (one call for every item). */
	private static final Duration LATEST_TTL = Duration.ofMinutes(5);

	/**
	 * How long a bulk window-start ANCHOR snapshot stays fresh. The "price N days ago" barely moves
	 * minute to minute, so this can be generous; a switch back to a cached timeframe is then instant.
	 */
	private static final Duration ANCHOR_TTL = Duration.ofMinutes(30);

	/** After a failed /latest fetch, wait this long before trying again. */
	private static final Duration LATEST_FAILURE_COOLDOWN = Duration.ofSeconds(60);

	/** Backoff after consecutive failures for one key: base * 2^(n-1), capped. */
	private static final Duration FAILURE_BASE_COOLDOWN = Duration.ofSeconds(30);
	private static final Duration FAILURE_MAX_COOLDOWN = Duration.ofMinutes(10);

	/**
	 * At most this many /timeseries requests are in flight at once. A small amount of concurrency
	 * (ordinary, polite HTTP to a single host) keeps a bank view responsive; the per-minute cap
	 * below is the real safety ceiling that protects the API, not a serial one-at-a-time crawl.
	 */
	private static final int MAX_IN_FLIGHT_TIMESERIES = 4;

	/**
	 * Gap between one /timeseries request finishing and the next starting. Zero by default:
	 * responsiveness comes from the modest concurrency above, and the API is protected by the
	 * continuous token-bucket rate limiter rather than an artificial serial delay.
	 */
	private static final long DEFAULT_REQUEST_GAP_MS = 0L;

	/**
	 * Steady /timeseries start rate, expressed as request starts per {@link #DEFAULT_RATE_WINDOW_MS}.
	 * Interpreted as the refill rate of a continuous token bucket, so starts drip at a smooth
	 * 300/min (5/s) rather than bursting up to a window ceiling and then stalling. This is a
	 * SELF-IMPOSED safety cap, not an API rule: the OSRS Wiki does not publish a rate limit and
	 * states that trouble would only begin at "multiple large queries per second, sustained". These
	 * are tiny single-item queries, so 300/min (5/s) is comfortably polite while letting a large
	 * bank fill in quickly; the cap mainly exists to stop a runaway bug from hammering the wiki.
	 */
	private static final int DEFAULT_MAX_REQUESTS_PER_WINDOW = 300;
	private static final long DEFAULT_RATE_WINDOW_MS = 60_000L;

	private final OkHttpClient httpClient;
	private final Gson gson;
	private final int maxInFlight;
	private final long requestGapMs;
	private final int maxRequestsPerWindow;
	private final long rateWindowMs;
	private final long timeseriesTtlMs;
	private final long latestTtlMs;

	/** Cache keyed by "itemId:lookback" -> fetched history (current price is rebased on read). */
	private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();

	/** Keys waiting in {@link #queue} and keys currently being fetched (for de-dupe + pending count). */
	private final Set<String> queued = Collections.newSetFromMap(new ConcurrentHashMap<>());
	private final Set<String> inFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** FIFO of pending /timeseries jobs. */
	private final Deque<Job> queue = new ConcurrentLinkedDeque<>();
	private final AtomicInteger inFlightCount = new AtomicInteger();

	/**
	 * Continuous token-bucket limiter state (guarded by {@link #pumpLock}). Tokens accrue at
	 * {@code maxRequestsPerWindow / rateWindowMs} per ms up to a small {@code maxInFlight}-sized
	 * capacity; each started /timeseries consumes one token. Deliberately NOT reset by
	 * {@link #clear()}: a restart must not refill the budget and let the user bypass the cap, so the
	 * token count and last-refill timestamp are carried across a restart.
	 */
	private double tokens;
	private long lastRefillMs;
	private final Object pumpLock = new Object();

	/**
	 * Earliest time (epoch ms) the next /timeseries request may start. Set when a request finishes
	 * and honoured by every {@link #pump()} call, so a freshly admitted key cannot jump the gap.
	 * Guarded by {@link #pumpLock}.
	 */
	private long nextStartAllowedAtMs;

	/**
	 * Bumped (under {@link #pumpLock}) by every {@link #clear()}. Each HTTP call remembers the
	 * generation it started in; its callback is ignored if the service was reset meanwhile.
	 */
	private int generation;

	/** Calls that have been enqueued and not yet completed; cancelled on reset. */
	private final Set<Call> activeCalls = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** Consecutive failures and the time of the last failure per key, for exponential backoff. */
	private final Map<String, Integer> failureCount = new ConcurrentHashMap<>();
	private final Map<String, Instant> lastFailure = new ConcurrentHashMap<>();

	/** Bulk current instant-sell ("low") prices from /latest, itemId -> gp. Swapped atomically. */
	private volatile Map<Integer, Long> latestLow = Collections.emptyMap();
	private volatile Instant latestFetchedAt;
	private volatile long latestFailedAtMs;
	private final AtomicBoolean latestInFlight = new AtomicBoolean();

	/**
	 * Bulk WINDOW-START anchor maps, one per timeframe: a single {@code /5m}|{@code /1h} snapshot at
	 * ~"now - window" giving itemId -> instant-sell ("low") price then. This is the key to a fast
	 * big bank: the active-timeframe percent change for the WHOLE bank comes from two bulk calls
	 * (/latest now + this anchor then) instead of a per-item /timeseries each. Keyed by timeframe
	 * lookback. Only populated for timeframes with {@link TimeFrame#hasBulkAnchor()} (6h/24h/7d/30d);
	 * 6m/1y have no aggregate reach and fall back to the per-item timeseries anchor.
	 */
	private final Map<String, Map<Integer, Long>> anchorLow = new ConcurrentHashMap<>();
	private final Map<String, Instant> anchorFetchedAt = new ConcurrentHashMap<>();
	private final Map<String, Long> anchorFailedAtMs = new ConcurrentHashMap<>();
	private final Set<String> anchorInFlight = Collections.newSetFromMap(new ConcurrentHashMap<>());

	/** Schedules the inter-request gap; created by {@link #start()}, destroyed by {@link #shutdown()}. */
	private volatile ScheduledExecutorService scheduler;

	/** Notified (off the client thread) whenever new data becomes available. */
	private volatile Runnable updateListener = () -> { };

	/** The item id of the most recent successful /timeseries fetch, for the loading "flash". */
	private volatile int lastResolvedItemId;

	@Inject
	PriceService(OkHttpClient httpClient, Gson gson)
	{
		this(httpClient, gson, DEFAULT_REQUEST_GAP_MS, DEFAULT_MAX_REQUESTS_PER_WINDOW,
			DEFAULT_RATE_WINDOW_MS);
	}

	/** Lets tests shrink the pacing constants (concurrency defaults to the production value). */
	PriceService(OkHttpClient httpClient, Gson gson, long requestGapMs, int maxRequestsPerWindow,
		long rateWindowMs)
	{
		this(httpClient, gson, MAX_IN_FLIGHT_TIMESERIES, requestGapMs, maxRequestsPerWindow,
			rateWindowMs, TIMESERIES_TTL.toMillis(), LATEST_TTL.toMillis());
	}

	/** Lets tests pin the in-flight concurrency (e.g. to 1) to assert ordering deterministically. */
	PriceService(OkHttpClient httpClient, Gson gson, int maxInFlight, long requestGapMs,
		int maxRequestsPerWindow, long rateWindowMs)
	{
		this(httpClient, gson, maxInFlight, requestGapMs, maxRequestsPerWindow, rateWindowMs,
			TIMESERIES_TTL.toMillis(), LATEST_TTL.toMillis());
	}

	/** Lets tests also shrink the cache lifetimes. */
	PriceService(OkHttpClient httpClient, Gson gson, long requestGapMs, int maxRequestsPerWindow,
		long rateWindowMs, long timeseriesTtlMs, long latestTtlMs)
	{
		this(httpClient, gson, MAX_IN_FLIGHT_TIMESERIES, requestGapMs, maxRequestsPerWindow,
			rateWindowMs, timeseriesTtlMs, latestTtlMs);
	}

	private PriceService(OkHttpClient httpClient, Gson gson, int maxInFlight, long requestGapMs,
		int maxRequestsPerWindow, long rateWindowMs, long timeseriesTtlMs, long latestTtlMs)
	{
		this.httpClient = httpClient;
		this.gson = gson;
		this.maxInFlight = maxInFlight;
		this.requestGapMs = requestGapMs;
		this.maxRequestsPerWindow = maxRequestsPerWindow;
		this.rateWindowMs = rateWindowMs;
		this.timeseriesTtlMs = timeseriesTtlMs;
		this.latestTtlMs = latestTtlMs;
		// Start full: a fresh service may burst up to the (modest) capacity immediately, matching
		// the intended small-burst behaviour. Capacity == maxInFlight.
		this.lastRefillMs = System.currentTimeMillis();
		this.tokens = maxInFlight;
	}

	void setUpdateListener(Runnable listener)
	{
		this.updateListener = listener == null ? () -> { } : listener;
	}

	/** Creates the scheduler that spaces out /timeseries requests. Does not block. */
	void start()
	{
		synchronized (pumpLock)
		{
			if (scheduler == null)
			{
				scheduler = Executors.newSingleThreadScheduledExecutor(r ->
				{
					final Thread t = new Thread(r, "ivf-price-pump");
					t.setDaemon(true);
					return t;
				});
			}
		}
	}

	/**
	 * Stops the scheduler (without blocking), cancels active calls and drops all cached and
	 * queued state.
	 */
	void shutdown()
	{
		final ScheduledExecutorService s;
		synchronized (pumpLock)
		{
			s = scheduler;
			scheduler = null;
		}
		if (s != null)
		{
			s.shutdownNow();
		}
		clear();
	}

	/**
	 * Returns the cached fluctuation for the item in the given timeframe if we have one,
	 * otherwise {@code null}. Non-blocking; call {@link #request(int, TimeFrame)} to populate (or
	 * refresh an expired entry, which keeps being returned meanwhile). The current price always
	 * reflects the latest bulk {@code /latest} map, so a /latest refresh needs no new history fetch.
	 */
	@Nullable
	PriceFluctuation getCached(int itemId, TimeFrame timeFrame)
	{
		final CacheEntry entry = cache.get(key(itemId, timeFrame));
		if (entry == null)
		{
			return null;
		}
		return entry.render(latestLow.get(itemId));
	}

	/**
	 * The cached per-timeframe history figures for a single component item, or {@code null} if that
	 * component has not been fetched yet. Current price is NOT included here; it comes from the bulk
	 * {@code /latest} map via {@link #getLatestLow(int)} and is summed separately.
	 */
	@Nullable
	PriceComponents getComponentData(int componentId, TimeFrame timeFrame)
	{
		final CacheEntry entry = cache.get(key(componentId, timeFrame));
		if (entry == null)
		{
			return null;
		}
		return new PriceComponents(entry.anchor, entry.historyHigh, entry.historyLow);
	}

	/**
	 * Combined fluctuation for a (possibly untradeable) item resolved to its tradeable components.
	 * Sums each component's current /latest low and cached history (multiplied by its per-item
	 * quantity) into a single {@link PriceFluctuation} whose {@code itemId} is {@code rawItemId}.
	 *
	 * <p>Returns {@code null} if ANY component is missing its current /latest low or its cached
	 * history, so the caller can request the missing pieces and keep showing stale/no data until
	 * everything is present. Coins and platinum are priced from their fixed gp value and never
	 * consult {@code /latest} or {@code /timeseries}; a fixed-price item has zero change.
	 */
	@Nullable
	PriceFluctuation getCachedCombined(int rawItemId, List<Component> components,
		TimeFrame timeFrame)
	{
		if (components == null || components.isEmpty())
		{
			return null;
		}

		long currentPrice = 0L;
		long anchorPrice = 0L;
		long periodHigh = 0L;
		long periodLow = 0L;
		boolean allFixed = true;

		for (Component c : components)
		{
			final long qty = c.getPerItemQuantity();
			if (ItemResolver.isFixedPrice(c.getTradeableId()))
			{
				final long value = ItemResolver.fixedPrice(c.getTradeableId()) * qty;
				currentPrice += value;
				anchorPrice += value;
				periodHigh += value;
				periodLow += value;
				continue;
			}

			allFixed = false;

			final Long low = latestLow.get(c.getTradeableId());
			if (low == null)
			{
				return null;
			}
			currentPrice += low * qty;

			// Prefer the BULK window-start anchor (one call for the whole bank). Fall back to the
			// per-item /timeseries anchor only when no bulk anchor exists for this timeframe (6m/1y)
			// or the item is absent from the bulk snapshot. periodHigh/Low come from the timeseries
			// when we have it; otherwise they default to the current price (the near-high ring uses
			// its own YEAR data, so a missing active-timeframe range is harmless here).
			final Long bulkAnchor = getAnchorLow(c.getTradeableId(), timeFrame);
			final PriceComponents pc = getComponentData(c.getTradeableId(), timeFrame);

			final Long perItemAnchor;
			if (bulkAnchor != null)
			{
				perItemAnchor = bulkAnchor;
			}
			else if (pc != null)
			{
				perItemAnchor = pc.getAnchor();
			}
			else
			{
				return null; // neither source ready yet
			}

			anchorPrice += perItemAnchor * qty;
			periodHigh += (pc != null ? pc.getPeriodHigh() : low) * qty;
			periodLow += (pc != null ? pc.getPeriodLow() : low) * qty;
		}

		if (allFixed)
		{
			// Fixed-price currency: current == anchor, no movement.
			return new PriceFluctuation(rawItemId, currentPrice, currentPrice, currentPrice,
				currentPrice, 0.0, 0L);
		}

		final long gpChange = currentPrice - anchorPrice;
		final double percentChange = anchorPrice == 0 ? 0.0 : (gpChange * 100.0) / anchorPrice;
		return new PriceFluctuation(rawItemId, currentPrice, anchorPrice,
			Math.max(periodHigh, currentPrice), Math.min(periodLow, currentPrice), percentChange,
			gpChange);
	}

	/**
	 * The combined 1-year high for a (possibly untradeable) item resolved to its tradeable
	 * components: Σ over components of (component's 1y {@code periodHigh} × per-item quantity). Uses
	 * the {@link TimeFrame#YEAR} timeseries regardless of the active time frame. Fixed-price
	 * currency contributes its flat value. The {@code periodHigh} is the high of the instant-sell
	 * ("low") series, consistent with the rest of the plugin.
	 *
	 * <p>Returns {@code null} if ANY non-fixed component's 1-year history is not cached yet, so the
	 * caller simply does not ring the item this frame (it fills in later) rather than guessing.
	 */
	@Nullable
	Long combinedYearHigh(List<Component> components)
	{
		if (components == null || components.isEmpty())
		{
			return null;
		}

		long yearHigh = 0L;
		for (Component c : components)
		{
			final long qty = c.getPerItemQuantity();
			if (ItemResolver.isFixedPrice(c.getTradeableId()))
			{
				yearHigh += ItemResolver.fixedPrice(c.getTradeableId()) * qty;
				continue;
			}

			final PriceComponents pc = getComponentData(c.getTradeableId(), TimeFrame.YEAR);
			if (pc == null)
			{
				return null;
			}
			yearHigh += pc.getPeriodHigh() * qty;
		}
		return yearHigh;
	}

	/**
	 * Combined 1-year LOW (instant-sell) summed over components, mirroring {@link #combinedYearHigh}.
	 * Together with the year high this gives the item's yearly price RANGE, used to suppress the
	 * near-high ring for stable commodities that barely swing over the year. Returns {@code null} if
	 * any non-fixed component's 1-year history is not cached yet.
	 */
	@Nullable
	Long combinedYearLow(List<Component> components)
	{
		if (components == null || components.isEmpty())
		{
			return null;
		}

		long yearLow = 0L;
		for (Component c : components)
		{
			final long qty = c.getPerItemQuantity();
			if (ItemResolver.isFixedPrice(c.getTradeableId()))
			{
				yearLow += ItemResolver.fixedPrice(c.getTradeableId()) * qty;
				continue;
			}

			final PriceComponents pc = getComponentData(c.getTradeableId(), TimeFrame.YEAR);
			if (pc == null)
			{
				return null;
			}
			yearLow += pc.getPeriodLow() * qty;
		}
		return yearLow;
	}

	/** The cached instant-sell price from the bulk /latest map, or {@code null} if unknown. */
	@Nullable
	Long getLatestLow(int itemId)
	{
		return latestLow.get(itemId);
	}

	/** True once a bulk /latest map has been loaded (even if a refresh is now due). */
	boolean isLatestLoaded()
	{
		return latestFetchedAt != null;
	}

	/** The item id of the most recently resolved /timeseries fetch, or 0 if none yet this run. */
	int getLastResolvedItemId()
	{
		return lastResolvedItemId;
	}

	/** True while the last bulk /latest attempt failed recently and its retry is cooling down. */
	boolean isLatestRetrying()
	{
		return latestRecentlyFailed();
	}

	/** Number of keys queued or in flight for the given timeframe. */
	int pendingCount(TimeFrame timeFrame)
	{
		final String suffix = ":" + timeFrame.getLookback();
		int count = 0;
		for (String k : queued)
		{
			if (k.endsWith(suffix))
			{
				count++;
			}
		}
		for (String k : inFlight)
		{
			if (k.endsWith(suffix))
			{
				count++;
			}
		}
		return count;
	}

	/**
	 * Makes sure the bulk /latest map is present and fresh, fetching it with ONE call if needed.
	 * Cheap to call every frame.
	 */
	void ensureLatestFresh()
	{
		final Instant fetchedAt = latestFetchedAt;
		if (fetchedAt != null
			&& Duration.between(fetchedAt, Instant.now()).toMillis() < latestTtlMs)
		{
			return;
		}
		if (latestRecentlyFailed())
		{
			return;
		}

		synchronized (pumpLock)
		{
			if (!latestInFlight.compareAndSet(false, true))
			{
				return;
			}

			final int gen = generation;
			final Request req = new Request.Builder()
				.url(API_BASE + "/latest")
				.header("User-Agent", USER_AGENT)
				.build();

			final Call call = httpClient.newCall(req);
			activeCalls.add(call);
			call.enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					if (completeLatest(gen, call, null))
					{
						log.debug("latest fetch failed: {}", e.getMessage());
					}
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					Map<Integer, Long> parsed = null;
					try (Response r = response)
					{
						if (!r.isSuccessful() || r.body() == null)
						{
							log.debug("latest HTTP {}", r.code());
						}
						else
						{
							parsed = parseLatest(r.body().string());
						}
					}
					catch (IOException | RuntimeException ex)
					{
						log.debug("latest parse failed: {}", ex.getMessage());
					}

					if (completeLatest(gen, call, parsed) && parsed != null)
					{
						updateListener.run();
					}
				}
			});
		}
	}

	/**
	 * Records the outcome of a /latest call. Returns false (and changes nothing) if the call
	 * belongs to a previous lifecycle generation.
	 */
	private boolean completeLatest(int gen, Call call, @Nullable Map<Integer, Long> parsed)
	{
		synchronized (pumpLock)
		{
			activeCalls.remove(call);
			if (gen != generation)
			{
				return false;
			}

			if (parsed != null)
			{
				latestLow = parsed;
				latestFetchedAt = Instant.now();
				latestFailedAtMs = 0L;
			}
			else
			{
				latestFailedAtMs = System.currentTimeMillis();
			}
			latestInFlight.set(false);
			return true;
		}
	}

	/**
	 * Ensures the bulk window-start ANCHOR snapshot for {@code timeFrame} is present and fresh,
	 * fetching it with ONE call ({@code /5m} or {@code /1h} at the aligned "now - window" timestamp)
	 * if needed. No-op for timeframes without a bulk anchor (6m/1y use the per-item timeseries).
	 * Cheap to call every frame.
	 */
	void ensureAnchorFresh(TimeFrame timeFrame)
	{
		if (!timeFrame.hasBulkAnchor())
		{
			return;
		}
		final String lb = timeFrame.getLookback();

		final Instant fetchedAt = anchorFetchedAt.get(lb);
		if (fetchedAt != null
			&& Duration.between(fetchedAt, Instant.now()).toMillis() < ANCHOR_TTL.toMillis())
		{
			return;
		}
		final Long failedAt = anchorFailedAtMs.get(lb);
		if (failedAt != null
			&& System.currentTimeMillis() - failedAt < LATEST_FAILURE_COOLDOWN.toMillis())
		{
			return;
		}

		synchronized (pumpLock)
		{
			if (!anchorInFlight.add(lb))
			{
				return;
			}

			final int gen = generation;
			final long step = timeFrame.getBulkStepSeconds();
			// Align the target time to the aggregate block boundary the endpoint expects.
			final long target = ((System.currentTimeMillis() / 1000L - timeFrame.getWindowSeconds())
				/ step) * step;
			final String endpoint = step <= 300L ? "/5m" : "/1h";
			final HttpUrl url = HttpUrl.parse(API_BASE + endpoint)
				.newBuilder()
				.addQueryParameter("timestamp", Long.toString(target))
				.build();

			final Request req = new Request.Builder()
				.url(url)
				.header("User-Agent", USER_AGENT)
				.build();

			final Call call = httpClient.newCall(req);
			activeCalls.add(call);
			call.enqueue(new Callback()
			{
				@Override
				public void onFailure(Call call, IOException e)
				{
					if (completeAnchor(gen, call, lb, null))
					{
						log.debug("anchor fetch failed ({}): {}", lb, e.getMessage());
					}
				}

				@Override
				public void onResponse(Call call, Response response)
				{
					Map<Integer, Long> parsed = null;
					try (Response r = response)
					{
						if (!r.isSuccessful() || r.body() == null)
						{
							log.debug("anchor HTTP {} ({})", r.code(), lb);
						}
						else
						{
							parsed = parseBulkLow(r.body().string());
						}
					}
					catch (IOException | RuntimeException ex)
					{
						log.debug("anchor parse failed ({}): {}", lb, ex.getMessage());
					}

					if (completeAnchor(gen, call, lb, parsed) && parsed != null)
					{
						updateListener.run();
					}
				}
			});
		}
	}

	/**
	 * Records the outcome of a bulk anchor call for one timeframe. Returns false (changes nothing)
	 * if the call belongs to a previous lifecycle generation.
	 */
	private boolean completeAnchor(int gen, Call call, String lb, @Nullable Map<Integer, Long> parsed)
	{
		synchronized (pumpLock)
		{
			activeCalls.remove(call);
			if (gen != generation)
			{
				return false;
			}

			if (parsed != null)
			{
				anchorLow.put(lb, parsed);
				anchorFetchedAt.put(lb, Instant.now());
				anchorFailedAtMs.remove(lb);
			}
			else
			{
				anchorFailedAtMs.put(lb, System.currentTimeMillis());
			}
			anchorInFlight.remove(lb);
			return true;
		}
	}

	/**
	 * The bulk window-start anchor (instant-sell low) for an item in a timeframe, or {@code null}
	 * if no bulk anchor is available for that timeframe or the item/map is not loaded yet.
	 */
	@Nullable
	private Long getAnchorLow(int itemId, TimeFrame timeFrame)
	{
		if (!timeFrame.hasBulkAnchor())
		{
			return null;
		}
		final Map<Integer, Long> map = anchorLow.get(timeFrame.getLookback());
		return map == null ? null : map.get(itemId);
	}

	/**
	 * Ensures a fresh fluctuation is being fetched for this item/timeframe. Returns immediately;
	 * the work is queued (FIFO, de-duped, rate-capped) and the update listener fires when data
	 * arrives. Safe to call every frame.
	 *
	 * @return {@code true} only if this call actually admitted a NEW job to the fetch queue;
	 *     {@code false} for a no-op (already fresh in cache, already queued or in flight, backing
	 *     off, or the service is not running). Callers that bound work per pass must count only
	 *     true admissions so repeated no-op calls for cached keys do not exhaust that budget.
	 */
	boolean request(int itemId, TimeFrame timeFrame)
	{
		ensureLatestFresh();

		final String key = key(itemId, timeFrame);

		// Lock-free fast path: this is called every frame for every visible slot, almost always
		// for an entry that is still fresh.
		if (isFresh(cache.get(key), timeFrame))
		{
			return false;
		}

		// Under the same lock that completions use, so a request can never slip in between a
		// call finishing (result/backoff recorded, key released) and being observed as finished.
		synchronized (pumpLock)
		{
			if (isFresh(cache.get(key), timeFrame))
			{
				return false; // still fresh
			}

			if (inBackoff(key))
			{
				return false;
			}

			if (scheduler == null || inFlight.contains(key) || !queued.add(key))
			{
				return false; // not running, already fetching, or already queued
			}

			queue.addLast(new Job(key, itemId, timeFrame));
			pump();
			return true;
		}
	}

	private boolean isFresh(@Nullable CacheEntry entry, TimeFrame timeFrame)
	{
		if (entry == null)
		{
			return false;
		}
		// The 1-year series barely moves, so it stays fresh far longer than the active-timeframe
		// history; this makes bank reopens near-instant once the yearly data has loaded once.
		final long ttl = timeFrame == TimeFrame.YEAR
			? Math.max(timeseriesTtlMs, YEAR_TTL.toMillis())
			: timeseriesTtlMs;
		return Duration.between(entry.fetchedAt, Instant.now()).toMillis() < ttl;
	}

	/**
	 * Starts queued jobs while there is a free in-flight slot, the request gap since the last
	 * finished request has elapsed and the token bucket has a token to spend; otherwise schedules
	 * itself for when the gap or the next token frees up. The gap is enforced here (not only by the
	 * delayed pump a finished request schedules), so a key admitted during the gap waits for it too.
	 */
	private void pump()
	{
		synchronized (pumpLock)
		{
			while (scheduler != null && inFlightCount.get() < maxInFlight
				&& !queue.isEmpty())
			{
				final long now = System.currentTimeMillis();
				final long gapWait = nextStartAllowedAtMs - now;
				if (gapWait > 0)
				{
					schedulePump(gapWait);
					return;
				}

				final long wait = tokenWaitMillis(now);
				if (wait > 0)
				{
					schedulePump(wait);
					return;
				}

				final Job job = queue.pollFirst();
				if (job == null || !queued.remove(job.key))
				{
					continue; // cleared while waiting
				}

				inFlight.add(job.key);
				inFlightCount.incrementAndGet();
				tokens -= 1.0; // consume one token at the moment a job actually starts
				startTimeseries(job, generation);
			}
		}
	}

	/**
	 * Refills the token bucket for the elapsed wall-clock time and reports how long until another
	 * token is available: {@code 0} if a start is allowed now, else the ms to wait. Only refills and
	 * reports; it does NOT consume a token (consumption happens at the actual start, so a scheduled
	 * re-pump that finds the slot gone does not waste a token). Capacity is {@link #maxInFlight}.
	 */
	private long tokenWaitMillis(long now)
	{
		final double capacity = maxInFlight;
		final double refillRatePerMs = (double) maxRequestsPerWindow / rateWindowMs;
		final long elapsed = Math.max(0L, now - lastRefillMs);
		tokens = Math.min(capacity, tokens + elapsed * refillRatePerMs);
		lastRefillMs = now;

		if (tokens >= 1.0)
		{
			return 0L;
		}
		return Math.max(1L, (long) Math.ceil((1.0 - tokens) / refillRatePerMs));
	}

	private void schedulePump(long delayMs)
	{
		final ScheduledExecutorService s = scheduler;
		if (s == null)
		{
			return;
		}
		try
		{
			s.schedule(this::pump, delayMs, TimeUnit.MILLISECONDS);
		}
		catch (RejectedExecutionException ex)
		{
			// shut down in the meantime; nothing to do
		}
	}

	/**
	 * Records the outcome of a /timeseries call (fetched history on success, otherwise a failure
	 * reason), frees the in-flight slot and schedules the next pump after the request gap. Does
	 * nothing if the call belongs to a previous lifecycle generation. Returns whether it applied.
	 */
	private boolean completeTimeseries(int gen, Call call, Job job,
		@Nullable CacheEntry fetched, @Nullable String failureReason)
	{
		synchronized (pumpLock)
		{
			activeCalls.remove(call);
			if (gen != generation)
			{
				return false;
			}

			// Record the outcome before releasing the key, so there is never a moment where the
			// key is neither in flight nor cached/backing off.
			if (fetched != null)
			{
				cache.put(job.key, fetched);
				lastFailure.remove(job.key);
				failureCount.remove(job.key);
				lastResolvedItemId = job.itemId; // for the overlay's "now pricing X" flash
			}
			else
			{
				recordFailure(job.key, "item " + job.itemId + ": " + failureReason);
			}
			inFlight.remove(job.key);
			inFlightCount.updateAndGet(v -> Math.max(0, v - 1));
			nextStartAllowedAtMs = System.currentTimeMillis() + requestGapMs;
		}

		schedulePump(requestGapMs);
		return true;
	}

	private void startTimeseries(Job job, int gen)
	{
		final HttpUrl url = HttpUrl.parse(API_BASE + "/timeseries")
			.newBuilder()
			.addQueryParameter("id", Integer.toString(job.itemId))
			.addQueryParameter("lookback", job.timeFrame.getLookback())
			.build();

		final Request req = new Request.Builder()
			.url(url)
			.header("User-Agent", USER_AGENT)
			.build();

		final Call call = httpClient.newCall(req);
		activeCalls.add(call);
		call.enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				completeTimeseries(gen, call, job, null, String.valueOf(e.getMessage()));
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				CacheEntry f = null;
				String failure = null;
				try (Response r = response)
				{
					if (!r.isSuccessful() || r.body() == null)
					{
						failure = "HTTP " + r.code();
					}
					else
					{
						f = parse(job.itemId, r.body().string());
						if (f == null)
						{
							failure = "empty or invalid data";
						}
					}
				}
				catch (IOException | RuntimeException ex)
				{
					failure = "parse error " + ex.getMessage();
				}

				if (completeTimeseries(gen, call, job, f, failure) && f != null)
				{
					updateListener.run();
				}
			}
		});
	}

	private void recordFailure(String key, String reason)
	{
		final int count = failureCount.merge(key, 1, Integer::sum);
		lastFailure.put(key, Instant.now());
		// Only the first failure in a run is logged, so a slow API does not spam the log.
		if (count == 1)
		{
			log.debug("timeseries fetch failed ({}); backing off", reason);
		}
	}

	private boolean inBackoff(String key)
	{
		final Instant failedAt = lastFailure.get(key);
		if (failedAt == null)
		{
			return false;
		}
		final Integer count = failureCount.get(key);
		final int n = count == null ? 1 : count;
		final long baseMs = FAILURE_BASE_COOLDOWN.toMillis();
		final long cooldownMs = Math.min(baseMs << Math.min(Math.max(n - 1, 0), 10),
			FAILURE_MAX_COOLDOWN.toMillis());
		return Duration.between(failedAt, Instant.now()).toMillis() < cooldownMs;
	}

	private boolean latestRecentlyFailed()
	{
		final long failedAt = latestFailedAtMs;
		return failedAt != 0L
			&& System.currentTimeMillis() - failedAt < LATEST_FAILURE_COOLDOWN.toMillis();
	}

	/**
	 * Parses the bulk {@code /latest} response,
	 * {@code {"data":{"<id>":{"high":..,"highTime":..,"low":..,"lowTime":..}, ...}}}, into
	 * itemId -> instant-sell ("low") price. Entries with a null/absent low are skipped.
	 */
	@Nullable
	private Map<Integer, Long> parseLatest(String body)
	{
		final JsonObject root = gson.fromJson(body, JsonObject.class);
		if (root == null || !root.has("data") || !root.get("data").isJsonObject())
		{
			return null;
		}

		final Map<Integer, Long> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("data").entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				continue;
			}
			final Long low = optLong(e.getValue().getAsJsonObject(), "low");
			if (low == null)
			{
				continue;
			}
			try
			{
				out.put(Integer.parseInt(e.getKey()), low);
			}
			catch (NumberFormatException ex)
			{
				// ignore malformed id
			}
		}
		return out;
	}

	/**
	 * Parses a bulk aggregate response ({@code /5m} or {@code /1h}),
	 * {@code {"data":{"<id>":{"avgHighPrice":..,"avgLowPrice":..,"highPriceVolume":..,
	 * "lowPriceVolume":..}, ...}}}, into itemId -> instant-sell ("low") average price for that
	 * block. Entries with a null/absent avgLowPrice are skipped. Averages can be fractional; we
	 * truncate to long gp, consistent with the rest of the plugin.
	 */
	@Nullable
	private Map<Integer, Long> parseBulkLow(String body)
	{
		final JsonObject root = gson.fromJson(body, JsonObject.class);
		if (root == null || !root.has("data") || !root.get("data").isJsonObject())
		{
			return null;
		}

		final Map<Integer, Long> out = new HashMap<>();
		for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("data").entrySet())
		{
			if (!e.getValue().isJsonObject())
			{
				continue;
			}
			final Long low = optLong(e.getValue().getAsJsonObject(), "avgLowPrice");
			if (low == null)
			{
				continue;
			}
			try
			{
				out.put(Integer.parseInt(e.getKey()), low);
			}
			catch (NumberFormatException ex)
			{
				// ignore malformed id
			}
		}
		return out;
	}

	/**
	 * Parses a {@code /timeseries} response into cacheable history. The response is
	 * {@code {"data":[{"timestamp":..,"avgHighPrice":..,"avgLowPrice":..,"highPriceVolume":..,
	 * "lowPriceVolume":..}, ...]}} ordered oldest-first. We use the instant-sell ("low") price:
	 * anchor = first point with a low. The last point with a low is kept as the fallback current
	 * price, used only when the bulk {@code /latest} map has no price for the item.
	 */
	@Nullable
	private CacheEntry parse(int itemId, String body)
	{
		final JsonObject root = gson.fromJson(body, JsonObject.class);
		if (root == null || !root.has("data") || !root.get("data").isJsonArray())
		{
			return null;
		}

		final JsonArray data = root.getAsJsonArray("data");
		if (data.size() == 0)
		{
			return null;
		}

		Long anchor = null;
		Long current = null;
		long high = Long.MIN_VALUE;
		long low = Long.MAX_VALUE;

		for (int i = 0; i < data.size(); i++)
		{
			final JsonObject point = data.get(i).getAsJsonObject();
			final Long lowPrice = optLong(point, "avgLowPrice");
			if (lowPrice == null)
			{
				continue;
			}

			if (anchor == null)
			{
				anchor = lowPrice;
			}
			current = lowPrice;
			high = Math.max(high, lowPrice);
			low = Math.min(low, lowPrice);
		}

		if (anchor == null || current == null || anchor == 0)
		{
			return null;
		}

		return new CacheEntry(itemId, anchor, high, low, current, Instant.now());
	}

	@Nullable
	private static Long optLong(JsonObject obj, String member)
	{
		if (!obj.has(member) || obj.get(member).isJsonNull())
		{
			return null;
		}
		return obj.get(member).getAsLong();
	}

	/**
	 * Full reset: cancels active calls, invalidates their callbacks (generation bump) and drops all
	 * cached, queued and in-flight state. The token bucket's token count and last-refill timestamp
	 * are deliberately carried across a restart (NOT reset to full) so a restart cannot refill the
	 * budget and exceed the self-imposed request rate.
	 */
	void clear()
	{
		synchronized (pumpLock)
		{
			generation++;
			for (Call call : activeCalls)
			{
				call.cancel();
			}
			activeCalls.clear();

			queue.clear();
			queued.clear();
			inFlight.clear();
			inFlightCount.set(0);
			cache.clear();
			lastFailure.clear();
			failureCount.clear();
			latestLow = Collections.emptyMap();
			latestFetchedAt = null;
			latestFailedAtMs = 0L;
			latestInFlight.set(false);
		}
	}

	private static String key(int itemId, TimeFrame timeFrame)
	{
		return itemId + ":" + timeFrame.getLookback();
	}

	/**
	 * Immutable per-timeframe history figures for one component item, used by
	 * {@link #getCachedCombined} to sum components. Current price is deliberately excluded; it comes
	 * from the bulk {@code /latest} map so a /latest refresh rebases without a new history fetch.
	 */
	static final class PriceComponents
	{
		private final long anchor;
		private final long periodHigh;
		private final long periodLow;

		PriceComponents(long anchor, long periodHigh, long periodLow)
		{
			this.anchor = anchor;
			this.periodHigh = periodHigh;
			this.periodLow = periodLow;
		}

		long getAnchor()
		{
			return anchor;
		}

		long getPeriodHigh()
		{
			return periodHigh;
		}

		long getPeriodLow()
		{
			return periodLow;
		}
	}

	private static final class Job
	{
		private final String key;
		private final int itemId;
		private final TimeFrame timeFrame;

		private Job(String key, int itemId, TimeFrame timeFrame)
		{
			this.key = key;
			this.itemId = itemId;
			this.timeFrame = timeFrame;
		}
	}

	/**
	 * Fetched history for one (item, timeframe). The render model is derived from it plus the
	 * current /latest price, and memoised per current price so per-frame reads allocate nothing
	 * until /latest actually changes the price.
	 */
	private static final class CacheEntry
	{
		private final int itemId;
		private final long anchor;
		private final long historyHigh;
		private final long historyLow;
		private final long historyLast;
		private final Instant fetchedAt;
		private volatile PriceFluctuation rendered;

		private CacheEntry(int itemId, long anchor, long historyHigh, long historyLow,
			long historyLast, Instant fetchedAt)
		{
			this.itemId = itemId;
			this.anchor = anchor;
			this.historyHigh = historyHigh;
			this.historyLow = historyLow;
			this.historyLast = historyLast;
			this.fetchedAt = fetchedAt;
		}

		/** Fluctuation using the bulk /latest price when known, else the history's last point. */
		private PriceFluctuation render(@Nullable Long latest)
		{
			final long current = latest != null ? latest : historyLast;
			final PriceFluctuation r = rendered;
			if (r != null && r.getCurrentPrice() == current)
			{
				return r;
			}

			final long gpChange = current - anchor;
			final double pct = (gpChange * 100.0) / anchor;
			final PriceFluctuation built = new PriceFluctuation(itemId, current, anchor,
				Math.max(historyHigh, current), Math.min(historyLow, current), pct, gpChange);
			rendered = built;
			return built;
		}
	}
}
