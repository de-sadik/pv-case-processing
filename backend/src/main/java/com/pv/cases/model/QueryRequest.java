package com.pv.cases.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.NotBlank;

/**
 * Inbound payload for raising a reviewer query.
 *
 * <p>Separate from {@link Query} because {@code id} and {@code createdAt} are
 * server-generated — a client cannot supply them, and this record makes that
 * explicit rather than relying on the server to ignore them.
 *
 * @param caseId    the case the question is about
 * @param fieldPath dot-separated section and field, e.g. {@code "patient.age"}
 * @param question  the reviewer's question in free text
 */
public record QueryRequest(
		@JsonProperty("case_id") @NotBlank String caseId,
		@JsonProperty("field_path") @NotBlank String fieldPath,
		@NotBlank String question) {
}
