package br.com.jorgemelo.nimbusfilemanager.shared;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.AppliedLocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.application.dto.LocationChange;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;

/**
 * What the location door answers once it has applied a change.
 *
 * <p>
 * It answers with the placement as the database now holds it - including the
 * two values the database derives and nobody else may name - and callers use
 * that answer to bring their own copy of the file back into agreement. A test
 * whose door answers nothing therefore proves nothing about that agreement,
 * which is why this exists rather than a bare {@code mock(...)}.
 */
public final class AppliedLocationChanges {

	/** The change as applied, first time, exactly as asked for. */
	public static AppliedLocationChange applying(LocationChange change) {
		Path destination = change.newPath();

		return new AppliedLocationChange(1L, PathUtils.normalize(destination), PathUtils.normalize(destination),
				PathUtils.normalize(destination.getParent()), false);
	}

	private AppliedLocationChanges() {
	}
}