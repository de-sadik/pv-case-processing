package com.pv.cases.model;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One extracted version of a pharmacovigilance case, exactly as the document
 * extractor produces it. Mirrors the shape of {@code case_v1.json}.
 *
 * <p>{@code sections} is an open two-level map on purpose: section names
 * ({@code patient}, {@code suspect_drug}) and field names ({@code weight_kg})
 * are extractor output rather than a fixed schema, so a follow-up document may
 * introduce sections and fields this build has never seen. The trade-off is no
 * compile-time field safety — the merge logic treats them uniformly instead.
 *
 * @param caseId             stable case identifier, e.g. {@code "PV-2026-0451"}
 * @param version            monotonically increasing version of this extraction
 * @param caseClassification controlled vocabulary, e.g. {@code "non-significant"};
 *                           kept as a string so an unrecognised value surfaces as
 *                           a validation error rather than failing deserialization
 * @param extractedAt        when the extractor ran
 * @param sourceDocument     filename of the document this version was extracted from
 * @param sections           section name to (field name to field), insertion-ordered
 * @param missingFields      dot-separated paths the extractor could not resolve,
 *                           e.g. {@code "adverse_event.onset_date"}; may be null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CaseDocument(
		@JsonProperty("case_id") @NotBlank String caseId,
		@Positive int version,
		@JsonProperty("case_classification") String caseClassification,
		@JsonProperty("extracted_at") Instant extractedAt,
		@JsonProperty("source_document") String sourceDocument,
		@NotNull Map<String, @Valid Map<String, @Valid FieldValue>> sections,
		@JsonProperty("missing_fields") List<String> missingFields) {

	/**
	 * Defensively copies the mutable collections. The store hands the same
	 * instance to every caller, so without this a merge could mutate the stored
	 * case in place. Null is preserved rather than defaulted so that
	 * {@code @NotNull} on {@code sections} still means something.
	 */
	public CaseDocument {
		sections = copySections(sections);
		missingFields = missingFields == null ? null : List.copyOf(missingFields);
	}

	/**
	 * @return an unmodifiable deep copy that preserves the document's section and
	 *         field order, which matters when a reviewer diffs two versions
	 *         on screen. {@code Map.copyOf} is unsuitable here: its iteration
	 *         order is unspecified.
	 */
	private static Map<String, Map<String, FieldValue>> copySections(
			Map<String, Map<String, FieldValue>> sections) {
		if (sections == null) {
			return null;
		}
		Map<String, Map<String, FieldValue>> copy = new LinkedHashMap<>();
		sections.forEach((name, fields) -> copy.put(name,
				fields == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(fields))));
		return Collections.unmodifiableMap(copy);
	}
}
