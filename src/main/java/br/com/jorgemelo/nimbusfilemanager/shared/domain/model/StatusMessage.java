package br.com.jorgemelo.nimbusfilemanager.shared.domain.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * The user-facing status message of an execution or execution step: either a
 * stable {@code code} plus its typed {@code args} (resolved to localized text
 * on read), or a raw free-text {@code text} for arbitrary messages that have no
 * catalog code (e.g. an external tool's failure string).
 *
 * <p>
 * The two forms are mutually exclusive - a coded message clears the text and a
 * raw message clears the code - and that invariant is expressed here through
 * the factory methods so no call site has to juggle the three columns by hand.
 * Shared as an {@code @Embeddable} by {@link Execution} and
 * {@link ExecutionStep}, which both carry the same
 * {@code message}/{@code message_code}/{@code message_args} columns, so the
 * concept lives in one place instead of being duplicated.
 */
@Embeddable
@Getter
@NoArgsConstructor
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode
public class StatusMessage {

	@Column(name = "message")
	private String text;

	@Column(name = "message_code", length = 100)
	private String code;

	@Column(name = "message_args", length = 2000)
	private String args;

	/**
	 * Raw free-text message with no catalog code (e.g. an arbitrary error string).
	 */
	public static StatusMessage raw(String text) {
		return new StatusMessage(text, null, null);
	}

	/** Coded message with no arguments. */
	public static StatusMessage code(String code) {
		return new StatusMessage(null, code, null);
	}

	/** Coded message with its encoded typed arguments. */
	public static StatusMessage coded(String code, String args) {
		return new StatusMessage(null, code, args);
	}
}