package com.pv.cases.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * The result of merging a follow-up {@link CaseDocument} into a stored one:
 * every field carries a {@link FieldStatus} so a reviewer can see what the
 * follow-up document changed.
 *
 * <p>Response-only — this is never deserialized from an extractor payload.
 *
 * @param caseId             identifier shared by both merged versions
 * @param version            version of the merged result
 * @param baseVersion        version of the stored case that was merged into
 * @param incomingVersion    version of the follow-up case that was merged in
 * @param caseClassification classification carried by the merged result
 * @param mergedAt           when the merge ran
 * @param sourceDocuments    every document that contributed to this result, oldest
 *                           first; provenance stays plural after the first merge
 * @param sections           section name to (field name to annotated field)
 * @param missingFields      dot-separated paths the extractor could not resolve,
 *                           surfaced from the follow-up payload; may be null
 * @param summary            per-status field counts, derived from {@code sections}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergedCase(
		@JsonProperty("case_id") String caseId,
		int version,
		@JsonProperty("base_version") int baseVersion,
		@JsonProperty("incoming_version") int incomingVersion,
		@JsonProperty("case_classification") String caseClassification,
		@JsonProperty("merged_at") Instant mergedAt,
		@JsonProperty("source_documents") List<String> sourceDocuments,
		Map<String, Map<String, MergedField>> sections,
		@JsonProperty("missing_fields") List<String> missingFields,
		MergeSummary summary) {

	public MergedCase {
		sourceDocuments = sourceDocuments == null ? null : List.copyOf(sourceDocuments);
		sections = copySections(sections);
		missingFields = missingFields == null ? null : List.copyOf(missingFields);
	}

	/**
	 * @return an unmodifiable deep copy preserving section and field order. See
	 *         {@code CaseDocument.copySections} for why {@code Map.copyOf} is
	 *         unsuitable.
	 */
	private static Map<String, Map<String, MergedField>> copySections(
			Map<String, Map<String, MergedField>> sections) {
		if (sections == null) {
			return null;
		}
		Map<String, Map<String, MergedField>> copy = new LinkedHashMap<>();
		sections.forEach((name, fields) -> copy.put(name,
				fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields))));
		return Collections.unmodifiableMap(copy);
	}

	/**
	 * Field counts by status, so a reviewer UI can say "3 fields changed" without
	 * walking the section map.
	 *
	 * @param unchanged  count of {@link FieldStatus#UNCHANGED} fields
	 * @param overridden count of {@link FieldStatus#OVERRIDDEN} fields
	 * @param added      count of {@link FieldStatus#NEW} fields; named {@code added}
	 *                   because {@code new} is a Java keyword
	 * @param retained   count of {@link FieldStatus#RETAINED} fields
	 */
	public record MergeSummary(
			int unchanged,
			int overridden,
			@JsonProperty("new") int added,
			int retained) {

		public int total() {
			return unchanged + overridden + added + retained;
		}
	}
}
