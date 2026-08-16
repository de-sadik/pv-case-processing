package com.pv.cases.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.pv.cases.model.CaseDocument;
import com.pv.cases.model.FieldStatus;
import com.pv.cases.model.FieldValue;
import com.pv.cases.model.MergedCase;
import com.pv.cases.model.MergedField;

class MergeServiceTest {

	private static final String CASE_ID = "PV-2026-0451";

	private final MergeService mergeService = new MergeService();

	@Test
	void unchangedField_sameValue_statusUnchanged() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(section("patient", field("age", "62", 0.97, "p.9 §3"))));

		MergedField age = mergeService.merge(stored, followUp).sections().get("patient").get("age");

		assertThat(age.status()).isEqualTo(FieldStatus.UNCHANGED);
		assertThat(age.value()).isEqualTo("62");
		assertThat(age.previousValue()).as("nothing was superseded").isNull();
		assertThat(age.confidence()).as("confidence refreshed from the follow-up").isEqualTo(0.97);
		assertThat(age.source()).as("source refreshed from the follow-up").isEqualTo("p.9 §3");
	}

	@Test
	void overriddenField_differentValue_previousValueIsFullStoredFieldValue() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(section("patient", field("age", "63", 0.95, "p.1 §2"))));

		MergedField age = mergeService.merge(stored, followUp).sections().get("patient").get("age");

		assertThat(age.status()).isEqualTo(FieldStatus.OVERRIDDEN);
		assertThat(age.value()).isEqualTo("63");
		assertThat(age.confidence()).isEqualTo(0.95);
		assertThat(age.source()).isEqualTo("p.1 §2");
		assertThat(age.previousValue())
				.as("the whole superseded field is preserved, not just its text")
				.isEqualTo(new FieldValue("62", 0.91, "p.2 §1"));
	}

	@Test
	void newField_onlyInFollowUp_statusNew() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(section("patient",
				field("age", "62", 0.91, "p.2 §1"),
				field("weight_kg", "78", 0.85, "p.3 §2"))));

		MergedField weight = mergeService.merge(stored, followUp).sections().get("patient").get("weight_kg");

		assertThat(weight.status()).isEqualTo(FieldStatus.NEW);
		assertThat(weight.value()).isEqualTo("78");
		assertThat(weight.confidence()).isEqualTo(0.85);
		assertThat(weight.source()).isEqualTo("p.3 §2");
		assertThat(weight.previousValue()).isNull();
	}

	@Test
	void retainedField_onlyInStored_statusRetained() {
		CaseDocument stored = stored(sections(section("patient",
				field("age", "62", 0.91, "p.2 §1"),
				field("sex", "Male", 0.99, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));

		MergedField sex = mergeService.merge(stored, followUp).sections().get("patient").get("sex");

		assertThat(sex.status()).isEqualTo(FieldStatus.RETAINED);
		assertThat(sex.value()).as("stored value carried forward").isEqualTo("Male");
		assertThat(sex.confidence()).isEqualTo(0.99);
		assertThat(sex.source()).isEqualTo("p.2 §1");
		assertThat(sex.previousValue()).isNull();
	}

	@Test
	void retainedSection_sectionMissingFromFollowUp_allFieldsRetained() {
		CaseDocument stored = stored(sections(
				section("patient", field("age", "62", 0.91, "p.2 §1")),
				section("reporter",
						field("qualification", "Physician", 0.95, "p.1 §1"),
						field("country", "India", 0.99, "p.1 §1"))));
		CaseDocument followUp = followUp(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));

		Map<String, MergedField> reporter = mergeService.merge(stored, followUp).sections().get("reporter");

		assertThat(reporter).as("the whole section survives the merge").hasSize(2);
		assertThat(reporter.values()).extracting(MergedField::status)
				.containsOnly(FieldStatus.RETAINED);
		assertThat(reporter.get("country").value()).isEqualTo("India");
		assertThat(reporter.get("qualification").value()).isEqualTo("Physician");
	}

	@Test
	void newSection_sectionOnlyInFollowUp_allFieldsNew() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(
				section("patient", field("age", "62", 0.91, "p.2 §1")),
				section("adverse_event",
						field("event_term", "Myalgia", 0.94, "p.4 §1"),
						field("outcome", "Recovered", 0.81, "p.5 §1"))));

		Map<String, MergedField> event = mergeService.merge(stored, followUp).sections().get("adverse_event");

		assertThat(event).hasSize(2);
		assertThat(event.values()).extracting(MergedField::status).containsOnly(FieldStatus.NEW);
		assertThat(event.get("event_term").value()).isEqualTo("Myalgia");
	}

	@Test
	void missingFields_nullInFollowUp_nullInMergedCase() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));

		assertThat(followUp.missingFields()).isNull();
		assertThat(mergeService.merge(stored, followUp).missingFields()).isNull();
	}

	@Test
	void missingFields_presentInFollowUp_passedThrough() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = new CaseDocument(CASE_ID, 2, "significant",
				Instant.parse("2026-05-01T10:00:00Z"), "followup.pdf",
				sections(section("patient", field("age", "62", 0.91, "p.2 §1"))),
				List.of("adverse_event.onset_date", "patient.weight_kg"));

		assertThat(mergeService.merge(stored, followUp).missingFields())
				.containsExactly("adverse_event.onset_date", "patient.weight_kg");
	}

	@Test
	void mergeSummary_countsAreCorrect_acrossAllStatuses() {
		CaseDocument stored = stored(sections(
				section("patient",
						field("age", "62", 0.91, "p.2 §1"),
						field("sex", "Male", 0.99, "p.2 §1")),
				section("reporter",
						field("qualification", "Physician", 0.95, "p.1 §1"),
						field("country", "India", 0.99, "p.1 §1"))));
		CaseDocument followUp = followUp(sections(
				section("patient",
						field("age", "63", 0.95, "p.1 §2"),
						field("weight_kg", "78", 0.85, "p.3 §2")),
				section("adverse_event",
						field("event_term", "Myalgia", 0.94, "p.4 §1"))));

		MergedCase merged = mergeService.merge(stored, followUp);

		// patient.age overridden; patient.sex retained; patient.weight_kg new;
		// reporter.* both retained (section absent from follow-up);
		// adverse_event.event_term new (section absent from stored).
		assertThat(merged.summary()).isEqualTo(new MergedCase.MergeSummary(0, 1, 2, 3));
		assertThat(merged.summary().total()).isEqualTo(6);
	}

	@Test
	void caseClassification_takenFromFollowUp_notStored() {
		CaseDocument stored = new CaseDocument(CASE_ID, 1, "non-significant",
				Instant.parse("2026-04-08T09:14:00Z"), "initial.pdf",
				sections(section("patient", field("age", "62", 0.91, "p.2 §1"))), null);
		CaseDocument followUp = new CaseDocument(CASE_ID, 2, "significant",
				Instant.parse("2026-05-01T10:00:00Z"), "followup.pdf",
				sections(section("patient", field("age", "62", 0.91, "p.2 §1"))), null);

		assertThat(mergeService.merge(stored, followUp).caseClassification()).isEqualTo("significant");
	}

	@Test
	void mergedCaseHeader_versionsAndSourceDocumentsArePopulated() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = followUp(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));

		MergedCase merged = mergeService.merge(stored, followUp);

		assertThat(merged.caseId()).isEqualTo(CASE_ID);
		assertThat(merged.baseVersion()).isEqualTo(1);
		assertThat(merged.incomingVersion()).isEqualTo(2);
		assertThat(merged.version()).as("merging produces the next version").isEqualTo(2);
		assertThat(merged.mergedAt()).isNotNull();
		assertThat(merged.sourceDocuments()).containsExactly("initial.pdf", "followup.pdf");
	}

	@Test
	void sourceDocuments_sameDocumentOnBothSides_deduped() {
		CaseDocument stored = stored(sections(section("patient", field("age", "62", 0.91, "p.2 §1"))));
		CaseDocument followUp = new CaseDocument(CASE_ID, 2, "significant",
				Instant.parse("2026-05-01T10:00:00Z"), "initial.pdf",
				sections(section("patient", field("age", "62", 0.91, "p.2 §1"))), null);

		assertThat(mergeService.merge(stored, followUp).sourceDocuments()).containsExactly("initial.pdf");
	}

	// --- fixtures -----------------------------------------------------------

	private static CaseDocument stored(Map<String, Map<String, FieldValue>> sections) {
		return new CaseDocument(CASE_ID, 1, "non-significant",
				Instant.parse("2026-04-08T09:14:00Z"), "initial.pdf", sections, null);
	}

	private static CaseDocument followUp(Map<String, Map<String, FieldValue>> sections) {
		return new CaseDocument(CASE_ID, 2, "significant",
				Instant.parse("2026-05-01T10:00:00Z"), "followup.pdf", sections, null);
	}

	@SafeVarargs
	private static Map<String, Map<String, FieldValue>> sections(
			Map.Entry<String, Map<String, FieldValue>>... entries) {
		Map<String, Map<String, FieldValue>> sections = new LinkedHashMap<>();
		for (Map.Entry<String, Map<String, FieldValue>> entry : entries) {
			sections.put(entry.getKey(), entry.getValue());
		}
		return sections;
	}

	@SafeVarargs
	private static Map.Entry<String, Map<String, FieldValue>> section(String name,
			Map.Entry<String, FieldValue>... entries) {
		Map<String, FieldValue> fields = new LinkedHashMap<>();
		for (Map.Entry<String, FieldValue> entry : entries) {
			fields.put(entry.getKey(), entry.getValue());
		}
		return Map.entry(name, fields);
	}

	private static Map.Entry<String, FieldValue> field(String name, String value, double confidence, String source) {
		return Map.entry(name, new FieldValue(value, confidence, source));
	}
}
