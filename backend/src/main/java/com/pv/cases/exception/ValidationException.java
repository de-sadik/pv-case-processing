package com.pv.cases.exception;

/**
 * Raised for validation failures that bean validation cannot express — rules
 * that depend on stored state, such as a follow-up whose version does not
 * follow the stored case's.
 */
public class ValidationException extends RuntimeException {

	public ValidationException(String message) {
		super(message);
	}
}
