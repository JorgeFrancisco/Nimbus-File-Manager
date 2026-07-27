package br.com.jorgemelo.nimbusfilemanager.shared.application;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks code no test can reach, so coverage stops asking for it.
 *
 * <p>
 * <b>Why the name carries "Generated":</b> JaCoCo only filters members annotated
 * with an annotation whose simple name contains {@code Generated} and whose
 * retention is {@code CLASS} or {@code RUNTIME} - the same hook Lombok uses for
 * {@code lombok.Generated}. Nothing here is generated; the name is the price of
 * the only filter JaCoCo offers.
 *
 * <p>
 * <b>When it applies</b> - only these two, and the reason goes in the
 * {@code value}:
 * <ul>
 * <li>framework wiring that exists solely so the container can build the object
 * (a Spring constructor that only forwards to a package-private one a test can
 * call directly);</li>
 * <li>an I/O failure path that needs the operating system to deny something -
 * permissions, an unreadable volume, a handle that dies mid-walk.</li>
 * </ul>
 *
 * <p>
 * <b>When it does not</b>: a one-line delegation, a branch that is merely
 * awkward to set up, or anything a restructure would make reachable. Twice in
 * one afternoon chasing coverage found dead code instead - an unreachable
 * {@code return} and a redundant guard - and deleting those beat hiding them. If
 * the honest options are "annotate" or "restructure", restructure.
 */
@Retention(RetentionPolicy.CLASS)
@Target({ ElementType.METHOD, ElementType.CONSTRUCTOR, ElementType.TYPE })
public @interface CoverageGenerated {

	/** Why no test can reach this. Required: the annotation is an argument. */
	String value();
}