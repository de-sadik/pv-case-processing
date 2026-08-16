package com.pv.cases.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import com.pv.cases.model.CaseDocument;
import com.pv.cases.model.FieldValue;
import com.pv.cases.model.MergedCase;
import com.pv.cases.model.MergedCase.MergeSummary;
import com.pv.cases.model.MergedField;

/**
 * Merges a follow-up extraction into a stored case, annotating every field with
 * the {@link com.pv.cases.model.FieldStatus} a reviewer needs to see what changed.
 *
 * <p>Status is driven by the field <em>value</em> alone. Confidence and source are
 * refreshed from the follow-up without affecting status, so a re-extraction that
 * nudges confidence from 0.94 to 0.95 does not flood the reviewer with false
 * "overridden" flags.
 *
 * <p>The section-level rules fall out of the field-level ones rather than being
 * special-cased: a section only in the stored case contributes fields that are
 * "stored only" and therefore all retained, and a section only in the follow-up
 * contributes fields that are all new.
 *
 * <p>Deliberately free of Spring annotations — pure input-to-output logic,
 * constructed directly in tests.
 */
public class MergeService {

	public MergedCase merge(CaseDocument stored, CaseDocument followUp) {
		Objects.requireNonNull(stored, "stored");
		Objects.requireNonNull(followUp, "followUp");

		Map<String, Map<String, MergedField>> sections = mergeSections(sectionsOf(stored), sectionsOf(followUp));

		return new MergedCase(
				stored.caseId(),
				stored.version() + 1,
				stored.version(),
				followUp.version(),
				// The reviewer may have reclassified the case on the follow-up.
				followUp.caseClassification(),
				Instant.now(),
				sourceDocuments(stored, followUp),
				sections,
				followUp.missingFields(),
				summarise(sections));
	}

	/**
	 * Walks the union of section names, stored sections first so the merged result
	 * keeps the reviewer's familiar ordering and appends anything new at the end.
	 */
	private static Map<String, Map<String, MergedField>> mergeSections(
			Map<String, Map<String, FieldValue>> stored, Map<String, Map<String, FieldValue>> followUp) {
		Map<String, Map<String, MergedField>> merged = new LinkedHashMap<>();
		for (String section : union(stored.keySet(), followUp.keySet())) {
			merged.put(section, mergeFields(fieldsOf(stored, section), fieldsOf(followUp, section)));
		}
		return merged;
	}

	private static Map<String, MergedField> mergeFields(Map<String, FieldValue> stored,
			Map<String, FieldValue> followUp) {
		Map<String, MergedField> merged = new LinkedHashMap<>();
		for (String field : union(stored.keySet(), followUp.keySet())) {
			merged.put(field, mergeField(stored.get(field), followUp.get(field)));
		}
		return merged;
	}

	private static MergedField mergeField(FieldValue stored, FieldValue followUp) {
		if (stored == null) {
			return MergedField.added(followUp);
		}
		if (followUp == null) {
			return MergedField.retained(stored);
		}
		if (Objects.equals(stored.value(), followUp.value())) {
			// Same value, but take the follow-up's confidence and source.
			return MergedField.unchanged(followUp);
		}
		return MergedField.overridden(followUp, stored);
	}

	/**
	 * @return every document that contributed to the merge, stored first, deduped,
	 *         insertion order preserved
	 */
	private static List<String> sourceDocuments(CaseDocument stored, CaseDocument followUp) {
		Set<String> documents = new LinkedHashSet<>();
		if (stored.sourceDocument() != null) {
			documents.add(stored.sourceDocument());
		}
		if (followUp.sourceDocument() != null) {
			documents.add(followUp.sourceDocument());
		}
		return List.copyOf(documents);
	}

	private static MergeSummary summarise(Map<String, Map<String, MergedField>> sections) {
		int unchanged = 0;
		int overridden = 0;
		int added = 0;
		int retained = 0;
		for (Map<String, MergedField> fields : sections.values()) {
			for (MergedField field : fields.values()) {
				switch (field.status()) {
					case UNCHANGED -> unchanged++;
					case OVERRIDDEN -> overridden++;
					case NEW -> added++;
					case RETAINED -> retained++;
				}
			}
		}
		return new MergeSummary(unchanged, overridden, added, retained);
	}

	private static Set<String> union(Set<String> first, Set<String> second) {
		Set<String> union = new LinkedHashSet<>(first);
		union.addAll(second);
		return union;
	}

	private static Map<String, Map<String, FieldValue>> sectionsOf(CaseDocument doc) {
		return doc.sections() == null ? Map.of() : doc.sections();
	}

	private static Map<String, FieldValue> fieldsOf(Map<String, Map<String, FieldValue>> sections, String section) {
		Map<String, FieldValue> fields = sections.get(section);
		return fields == null ? Map.of() : fields;
	}
}
