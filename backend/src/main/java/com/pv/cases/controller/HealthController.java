package com.pv.cases.controller;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import com.pv.cases.repository.InMemoryCaseStore;

/**
 * Liveness endpoint that also reports what the in-memory store is holding.
 *
 * <p>The counts are the useful part: because the store is in-memory, "the
 * service is up" and "the service has data" are separate questions, and a
 * {@code cases_loaded} of 0 is the signature of a restart that lost its state.
 */
@RestController
public class HealthController {

	private final InMemoryCaseStore store;

	public HealthController(InMemoryCaseStore store) {
		this.store = store;
	}

	@GetMapping("/health")
	public HealthResponse health() {
		return new HealthResponse("UP", this.store.caseCount(), this.store.queryCount());
	}

	/** {@code {"status": "UP", "cases_loaded": 1, "queries_count": 0}} */
	private record HealthResponse(
			String status,
			@JsonProperty("cases_loaded") int casesLoaded,
			@JsonProperty("queries_count") int queriesCount) {
	}
}
