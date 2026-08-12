package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.io.Serial;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LocationChangeFailure;
import lombok.Getter;

/**
 * The catalog would not move a file, and says which of the refusals it was.
 *
 * <p>
 * Unchecked because every one of these is either a race a caller has to decide
 * about at the point it happens, or a defect - and neither is improved by being
 * declared through every method between here and the screen.
 *
 * <p>
 * The message is the database's, kept for the log because it names the file and
 * the two paths involved. Nothing should read it: {@link #failure} is the part
 * that is a contract.
 */
@Getter
public class LocationChangeException extends RuntimeException {

	@Serial
	private static final long serialVersionUID = 1L;

	private final LocationChangeFailure failure;

	public LocationChangeException(LocationChangeFailure failure, String message, Throwable cause) {
		super(message, cause);

		this.failure = failure;
	}
}