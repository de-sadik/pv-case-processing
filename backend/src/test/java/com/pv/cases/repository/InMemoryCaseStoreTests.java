package com.pv.cases.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.pv.cases.model.CaseDocument;
import com.pv.cases.model.FieldValue;
import com.pv.cases.model.Query;

class InMemoryCaseStoreTests {

	private InMemoryCaseStore store;

	@BeforeEach
	void setUp() {
		store = new InMemoryCaseStore();
	}

	@Test
	void findCaseReturnsEmptyForAnUnknownId() {
		assertThat(store.findCase("PV-NOPE")).isEmpty();
		assertThat(store.caseCount()).isZero();
	}

	@Test
	void savedCaseIsRetrievableById() {
		CaseDocument doc = caseDocument("PV-1", 1);
		store.saveCase("PV-1", doc);

		assertThat(store.findCase("PV-1")).contains(doc);
		assertThat(store.caseCount()).isEqualTo(1);
	}

	@Test
	void savingTheSameIdReplacesRatherThanAccumulates() {
		store.saveCase("PV-1", caseDocument("PV-1", 1));
		store.saveCase("PV-1", caseDocument("PV-1", 2));

		assertThat(store.caseCount()).isEqualTo(1);
		assertThat(store.findCase("PV-1")).get().extracting(CaseDocument::version).isEqualTo(2);
	}

	@Test
	void findQueriesReturnsAnEmptyListNotNullForAnUnknownId() {
		assertThat(store.findQueriesByCaseId("PV-NOPE")).isNotNull().isEmpty();
	}

	@Test
	void queriesAreGroupedByCaseAndKeptInInsertionOrder() {
		Query first = Query.create("PV-1", "patient.age", "Age conflicts with the narrative?");
		Query second = Query.create("PV-1", "patient.sex", "Sex not stated in section 2?");
		Query other = Query.create("PV-2", "reporter.country", "Reporter country missing?");
		store.saveQuery(first);
		store.saveQuery(second);
		store.saveQuery(other);

		assertThat(store.findQueriesByCaseId("PV-1")).containsExactly(first, second);
		assertThat(store.findQueriesByCaseId("PV-2")).containsExactly(other);
	}

	@Test
	void queryCountSumsAcrossEveryCase() {
		store.saveQuery(Query.create("PV-1", "patient.age", "why?"));
		store.saveQuery(Query.create("PV-1", "patient.sex", "why?"));
		store.saveQuery(Query.create("PV-2", "reporter.country", "why?"));

		assertThat(store.queryCount()).isEqualTo(3);
		assertThat(store.caseCount()).as("queries must not create cases").isZero();
	}

	@Test
	void returnedQueryListIsACopyAndCannotMutateStoredState() {
		store.saveQuery(Query.create("PV-1", "patient.age", "why?"));
		List<Query> returned = store.findQueriesByCaseId("PV-1");

		assertThatThrownBy(() -> returned.add(Query.create("PV-1", "patient.sex", "sneaky")))
				.isInstanceOf(UnsupportedOperationException.class);
		assertThat(store.queryCount()).isEqualTo(1);
	}

	private static CaseDocument caseDocument(String caseId, int version) {
		Map<String, Map<String, FieldValue>> sections = new LinkedHashMap<>();
		sections.put("patient", new LinkedHashMap<>(Map.of("age", new FieldValue("62", 0.91, "p.2 §1"))));
		return new CaseDocument(caseId, version, "non-significant", Instant.parse("2026-04-08T09:14:00Z"),
				"report.pdf", sections, null);
	}
}
