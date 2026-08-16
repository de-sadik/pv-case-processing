package com.pv.cases.model;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A single field extracted from a source document, with the extractor's
 * confidence and a human-readable pointer back into that document.
 *
 * <p>All three property names already match the wire format, so no Jackson
 * annotations are required.
 *
 * <p>{@code value} is always a string, even for ages and dates: the extractor
 * emits everything as text, and keeping it that way makes change detection a
 * plain {@link Object#equals(Object)} comparison. Interpretation belongs to the
 * service layer.
 *
 * @param value      the extracted text, e.g. {@code "20 mg"}
 * @param confidence extractor confidence in {@code [0.0, 1.0]}; boxed so that an
 *                   absent value is distinguishable from a genuine {@code 0.0}
 * @param source     provenance within the source document, e.g. {@code "p.4 §1"}
 */
public record FieldValue(
		@NotBlank String value,
		@NotNull @DecimalMin("0.0") @DecimalMax("1.0") Double confidence,
		@NotBlank String source) {
}
