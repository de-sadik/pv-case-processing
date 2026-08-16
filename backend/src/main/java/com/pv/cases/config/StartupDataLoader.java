package com.pv.cases.config;

import java.io.IOException;
import java.io.InputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.pv.cases.model.CaseDocument;
import com.pv.cases.repository.InMemoryCaseStore;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Seeds the case store from the bundled {@code case_v1.json} at startup.
 *
 * <p>Every failure path throws {@link IllegalStateException}. Thrown from an
 * {@link ApplicationRunner}, that propagates out of {@code SpringApplication.run},
 * closes the context and exits non-zero — the service must not come up serving an
 * empty store, because every endpoint above it would return "case not found" and
 * look like a data problem rather than a boot problem.
 *
 * <p>The injected {@link ObjectMapper} is {@code tools.jackson.databind}: this
 * build is on Jackson 3, where Spring Boot auto-configures a {@code JsonMapper}
 * (an {@code ObjectMapper} subclass) and {@code com.fasterxml.jackson.databind}
 * is not on the classpath at all.
 */
@Component
public class StartupDataLoader implements ApplicationRunner {

	private static final Logger log = LoggerFactory.getLogger(StartupDataLoader.class);

	private static final String SEED_RESOURCE = "/case_v1.json";

	private static final String EXPECTED_CASE_ID = "PV-2026-0451";

	private final InMemoryCaseStore store;

	private final ObjectMapper objectMapper;

	public StartupDataLoader(InMemoryCaseStore store, ObjectMapper objectMapper) {
		this.store = store;
		this.objectMapper = objectMapper;
	}

	@Override
	public void run(ApplicationArguments args) {
		CaseDocument caseDocument = readSeedCase();

		if (!EXPECTED_CASE_ID.equals(caseDocument.caseId())) {
			throw new IllegalStateException("Expected seed case " + EXPECTED_CASE_ID + " but "
					+ SEED_RESOURCE + " declares " + caseDocument.caseId());
		}
		if (caseDocument.sections() == null) {
			throw new IllegalStateException(
					SEED_RESOURCE + " declares no sections; refusing to start with an unusable case");
		}

		store.saveCase(caseDocument.caseId(), caseDocument);
		log.info("Loaded case {} with {} sections", caseDocument.caseId(), caseDocument.sections().size());
	}

	private CaseDocument readSeedCase() {
		InputStream in = getClass().getResourceAsStream(SEED_RESOURCE);
		if (in == null) {
			throw new IllegalStateException(
					SEED_RESOURCE + " not found on the classpath; the case store would start empty");
		}
		try (in) {
			return objectMapper.readValue(in, CaseDocument.class);
		}
		// JacksonException covers the parse and is unchecked in Jackson 3;
		// IOException is reachable only from closing the stream.
		catch (IOException | JacksonException ex) {
			throw new IllegalStateException("Failed to read seed case from " + SEED_RESOURCE, ex);
		}
	}
}
