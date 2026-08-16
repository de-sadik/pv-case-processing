package com.pv.cases.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import com.pv.cases.model.CaseDocument;
import com.pv.cases.repository.InMemoryCaseStore;

/**
 * Exercises the real startup path. Beyond the seeding itself, this is what proves
 * the {@code ObjectMapper} constructor parameter resolves against the
 * {@code JsonMapper} bean Spring Boot 4 auto-configures — a wiring failure here
 * would otherwise only surface when the application is run by hand.
 */
@SpringBootTest
class StartupDataLoaderTests {

	@Autowired
	private InMemoryCaseStore store;

	@Test
	void seedsExactlyTheBootstrapCase() {
		assertThat(store.caseCount()).isEqualTo(1);
		assertThat(store.queryCount()).isZero();
	}

	@Test
	void loadsEverySectionOfTheSeedFile() {
		CaseDocument doc = store.findCase("PV-2026-0451").orElseThrow();

		assertThat(doc.version()).isEqualTo(1);
		assertThat(doc.caseClassification()).isEqualTo("non-significant");
		assertThat(doc.sourceDocument()).isEqualTo("initial_report_PV-2026-0451.pdf");
		assertThat(doc.sections()).hasSize(4)
				.containsOnlyKeys("patient", "suspect_drug", "adverse_event", "reporter");
	}

	@Test
	void extractedFieldsKeepTheirConfidenceAndSource() {
		CaseDocument doc = store.findCase("PV-2026-0451").orElseThrow();

		assertThat(doc.sections().get("patient").get("age"))
				.satisfies(age -> {
					assertThat(age.value()).isEqualTo("62");
					assertThat(age.confidence()).isEqualTo(0.91);
					assertThat(age.source()).isEqualTo("p.2 §1");
				});
	}
}
