package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Teaches HQL to ask whether a value is one of an array of values, rendered as
 * PostgreSQL's {@code = any(?)}.
 *
 * <p>
 * It exists because the alternatives are all worse for a query that names a
 * whole library. Listing the ids with {@code IN :ids} spends one bind parameter
 * per element, and the wire protocol carries 65.535 of them - a library of
 * 119.870 photos asked for 119.872 and the driver refused to prepare the
 * statement at all. Hibernate's own {@code array_contains} does bind the ids as
 * a single array, but renders {@code ? @> array[id]}, which PostgreSQL can only
 * answer by re-scanning the array once per row: measured on 120.000 rows, that
 * plan is a sequential scan taking <b>99 seconds</b>, against <b>33 ms</b> for
 * an index-only scan when the same array is written as {@code = any(?)}. Same
 * answer, same single parameter, 650 times the cost - so the rendering is the
 * whole point of this class.
 *
 * <p>
 * A function rather than native SQL for the four queries that needed it: they
 * project typed records over mapped joins, and rewriting them by hand would put
 * fifteen column mappings and an enum literal at risk to change one predicate.
 */
public class ArrayMembershipFunctionContributor implements FunctionContributor {

	/** {@code inArray(value, array)} - true when the array holds the value. */
	public static final String FUNCTION_NAME = "inArray";

	@Override
	public void contributeFunctions(FunctionContributions functionContributions) {
		functionContributions.getFunctionRegistry().registerPattern(FUNCTION_NAME, "?1 = any(?2)",
				functionContributions.getTypeConfiguration().getBasicTypeRegistry()
						.resolve(StandardBasicTypes.BOOLEAN));
	}
}