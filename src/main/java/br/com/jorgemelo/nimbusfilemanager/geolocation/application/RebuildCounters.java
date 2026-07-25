package br.com.jorgemelo.nimbusfilemanager.geolocation.application;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe running totals for a single location rebuild pass.
 * Package-private holder used only by {@link LocationRebuildService}; its
 * counters are updated concurrently by the parallel batch workers, so each is
 * an {@link AtomicLong}.
 */
final class RebuildCounters {

	final AtomicLong candidates = new AtomicLong();
	final AtomicLong resolved = new AtomicLong();
	final AtomicLong unresolved = new AtomicLong();
	final AtomicLong errors = new AtomicLong();
}