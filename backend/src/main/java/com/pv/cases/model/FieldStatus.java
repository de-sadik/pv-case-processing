package com.pv.cases.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * How a field in a merged case relates to the stored case it was merged into.
 *
 * <p>The wire vocabulary is lowercase, so the mapping is declared explicitly via
 * {@link JsonValue} / {@link JsonCreator} rather than relying on a global
 * {@code ObjectMapper} feature — that way a bare {@code ObjectMapper} in a unit
 * test produces the same JSON as the Spring-configured one.
 */
public enum FieldStatus {

	/** Present in both the stored and incoming case, with the same value. */
	UNCHANGED("unchanged"),

	/** Present in both, with a different value; the prior field is retained for comparison. */
	OVERRIDDEN("overridden"),

	/** Absent from the stored case, introduced by the incoming case. */
	NEW("new"),

	/** Present in the stored case, absent from the incoming case, and carried forward. */
	RETAINED("retained");

	private final String wireName;

	FieldStatus(String wireName) {
		this.wireName = wireName;
	}

	@JsonValue
	public String wireName() {
		return wireName;
	}

	@JsonCreator
	public static FieldStatus fromWire(String wireName) {
		for (FieldStatus status : values()) {
			if (status.wireName.equalsIgnoreCase(wireName)) {
				return status;
			}
		}
		throw new IllegalArgumentException("Unknown field status: " + wireName);
	}
}
