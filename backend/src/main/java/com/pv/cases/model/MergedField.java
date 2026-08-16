package com.pv.cases.model;

import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A field in a {@link MergedCase}: the resulting value plus the annotation a
 * reviewer needs to see what the follow-up document did to it.
 *
 * <p>The value/confidence/source triple is spelled out flat rather than
 * composing a {@link FieldValue} and unwrapping it. {@code @JsonUnwrapped} is
 * not supported on creator parameters, so a record composing a {@code
 * FieldValue} would serialize correctly and then fail to deserialize.
 *
 * @param value         the resulting value after the merge
 * @param confidence    confidence of the resulting value
 * @param source        provenance of the resulting value
 * @param status        how this field relates to the stored case
 * @param previousValue the complete superseded field — value, confidence and
 *                      source — so the reviewer can compare provenance and not
 *                      just text; only ever set for {@link FieldStatus#OVERRIDDEN}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MergedField(
		String value,
		Double confidence,
		String source,
		FieldStatus status,
		@JsonProperty("previous_value") FieldValue previousValue) {

	public MergedField {
		Objects.requireNonNull(status, "status");
		if (previousValue != null && status != FieldStatus.OVERRIDDEN) {
			throw new IllegalArgumentException(
					"previousValue is only meaningful for OVERRIDDEN fields, but status was " + status);
		}
	}

	/** The incoming document repeated the stored value verbatim. */
	public static MergedField unchanged(FieldValue current) {
		return of(current, FieldStatus.UNCHANGED, null);
	}

	/**
	 * The incoming document supplied a different value.
	 *
	 * @param current  the incoming field, which wins
	 * @param previous the stored field it replaced
	 */
	public static MergedField overridden(FieldValue current, FieldValue previous) {
		return of(current, FieldStatus.OVERRIDDEN, Objects.requireNonNull(previous, "previous"));
	}

	/** The incoming document introduced a field the stored case did not have. */
	public static MergedField added(FieldValue current) {
		return of(current, FieldStatus.NEW, null);
	}

	/** The incoming document did not mention this field, so the stored one is carried forward. */
	public static MergedField retained(FieldValue stored) {
		return of(stored, FieldStatus.RETAINED, null);
	}

	private static MergedField of(FieldValue field, FieldStatus status, FieldValue previous) {
		Objects.requireNonNull(field, "field");
		return new MergedField(field.value(), field.confidence(), field.source(), status, previous);
	}
}
