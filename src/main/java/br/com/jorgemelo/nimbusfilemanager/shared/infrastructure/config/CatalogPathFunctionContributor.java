package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config;

import org.hibernate.boot.model.FunctionContributions;
import org.hibernate.boot.model.FunctionContributor;
import org.hibernate.type.StandardBasicTypes;

/**
 * Lets a query ask the database about a path, instead of Java answering and
 * hoping the two agree.
 *
 * <p>
 * {@code path_key} is a stored generated column: the database decided its value
 * when the row was written. Looking a row up by path therefore has to reach the
 * same answer, and the only way to be sure is to ask the same function. Writing
 * the rule a second time in Java - lowercase here, separators there - is exactly
 * the divergence the generated column exists to make impossible.
 *
 * <p>
 * The same argument covers the file's name. It is no longer a column, and three
 * screens order by it while the database is paginating - which Java cannot do
 * afterwards, since it never sees the rows the sort left out.
 */
public class CatalogPathFunctionContributor implements FunctionContributor {

	/** {@code canonicalPath(path, flavor)} - the stored form of a path. */
	public static final String CANONICAL_PATH = "canonicalPath";

	/** {@code catalogFileName(path, flavor)} - the last segment of a path. */
	public static final String FILE_NAME = "catalogFileName";

	@Override
	public void contributeFunctions(FunctionContributions functionContributions) {
		register(functionContributions, CANONICAL_PATH, "canonicalize_catalog_path(?1, ?2)");
		register(functionContributions, FILE_NAME, "catalog_file_name(?1, ?2)");
	}

	private void register(FunctionContributions functionContributions, String name, String pattern) {
		functionContributions.getFunctionRegistry().registerPattern(name, pattern, functionContributions
				.getTypeConfiguration().getBasicTypeRegistry().resolve(StandardBasicTypes.STRING));
	}
}