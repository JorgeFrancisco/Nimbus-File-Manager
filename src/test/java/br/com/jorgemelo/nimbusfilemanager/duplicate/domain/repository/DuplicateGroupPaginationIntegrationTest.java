package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.assertj.core.api.Assertions;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection.DuplicateGroupRawResponse;
import br.com.jorgemelo.nimbusfilemanager.shared.CatalogFiles;
import br.com.jorgemelo.nimbusfilemanager.shared.SharedPostgresIntegrationTest;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.FileType;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.LifecycleStatus;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.model.CatalogFile;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileLocationRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.repository.CatalogFileRepository;
import jakarta.persistence.EntityManager;

/**
 * What a page of duplicate groups really is, and what it costs to ask for one.
 *
 * <p>
 * The query groups by digest and keeps only the groups with more than one file,
 * which makes a page of it two questions rather than one: which groups are on
 * this page, and how many groups there are in total. Spring Data answers the
 * second by deriving a query from the first, and a derivation over a
 * {@code GROUP BY} is the kind of thing that is either exactly right or quietly
 * counts the wrong population - a total of files where the screen says groups,
 * or a paginator with the wrong number of pages.
 *
 * <p>
 * Neither reading the repository nor reading Spring Data settles which of those
 * it is. This asks the database and looks at what was actually sent.
 *
 * <p>
 * <b>Three groups, and deliberately not more.</b> The subject is the shape of
 * the statements, not how long they take: a bigger fixture would prove exactly
 * the same thing while inviting a conclusion about performance that a table this
 * size cannot support.
 *
 * <p>
 * <b>Why AUDIO.</b> The count is over the whole catalog, so a fixture that used
 * PHOTO would be answering about rows other tests wrote. Nothing else here
 * catalogues audio, and the query filters by type, which makes this population
 * this test's alone.
 */
class DuplicateGroupPaginationIntegrationTest extends SharedPostgresIntegrationTest {

	private static final Set<FileType> AUDIO = Set.of(FileType.AUDIO);

	/**
	 * What identifies this query and its derived count, taken from the filter
	 * rather than from the projection: a derived count rewrites the select list
	 * and would not carry the projection's name, which is exactly how a first
	 * attempt at this test managed to see only half of what was sent.
	 */
	private static final String SIGNATURE = "TRIM(m.sha256)";

	@Autowired
	private CatalogFileRepository catalogFileRepository;

	@Autowired
	private CatalogFileLocationRepository catalogFileLocationRepository;

	@Autowired
	private DuplicateRepository duplicateRepository;

	@Autowired
	private EntityManager entityManager;

	@BeforeEach
	void threeGroupsAndOneLoneFile(@TempDir Path library) {
		// Sizes chosen so the wasted bytes each group reports are distinct, which is
		// what the ordering is on: 3000, 700 and 300.
		//
		// The largest group holds four and not three, so that no number here equals
		// any other: three groups, four files in the biggest, eight files in groups,
		// nine catalogued. A total of three can then only mean the groups - with a
		// group of three it would equally have meant "the first row of the count",
		// which is a different mechanism and a different defect.
		catalogued(library, "alpha", 1000L, 4);
		catalogued(library, "beta", 700L, 2);
		catalogued(library, "gamma", 300L, 2);

		// And one file that is nobody's duplicate, so the HAVING has something to
		// leave out.
		catalogued(library, "lonely", 500L, 1);

		entityManager.flush();
	}

	@Test
	void theTotalIsTheNumberOfGroupsAndNotTheNumberOfFiles() {
		Page<DuplicateGroupRawResponse> page = duplicateRepository.findDuplicateGroups(AUDIO, PageRequest.of(0, 50));

		Assertions.assertThat(page.getTotalElements())
				.as("three groups - not the eight files in them, nor the four of the biggest").isEqualTo(3);

		Assertions.assertThat(page.getContent()).extracting(DuplicateGroupRawResponse::files)
				.containsExactly(4L, 2L, 2L);
	}

	/**
	 * A second page, because a paginator that is wrong about the total is often
	 * right about the first page - the one everybody looks at.
	 */
	@Test
	void thePagesDivideTheGroupsAndTheSecondContinuesWhereTheFirstStopped() {
		Page<DuplicateGroupRawResponse> first = duplicateRepository.findDuplicateGroups(AUDIO, PageRequest.of(0, 2));

		Assertions.assertThat(first.getTotalElements()).isEqualTo(3);
		Assertions.assertThat(first.getTotalPages()).isEqualTo(2);
		Assertions.assertThat(first.getContent()).extracting(DuplicateGroupRawResponse::wastedSizeBytes)
				.as("ordered by what each group wastes, most first").containsExactly(3000L, 700L);

		Page<DuplicateGroupRawResponse> second = duplicateRepository.findDuplicateGroups(AUDIO, PageRequest.of(1, 2));

		Assertions.assertThat(second.getTotalElements()).as("the total does not change with the page").isEqualTo(3);
		Assertions.assertThat(second.getContent()).extracting(DuplicateGroupRawResponse::wastedSizeBytes)
				.containsExactly(300L);

		Assertions.assertThat(second.getContent()).as("no group appears on both pages")
				.doesNotContainAnyElementsOf(first.getContent());
	}

	/**
	 * The statements themselves. Read as the set of distinct shapes rather than as
	 * a count of executions, because the suite runs classes in parallel over one
	 * shared context and another class asking the same question would inflate a
	 * count while leaving the shapes exactly as they are.
	 */
	@Test
	void askingForAPageSendsTheGroupingQueryAndACountOfGroups() {
		Statistics statistics = statistics();

		statistics.clear();

		duplicateRepository.findDuplicateGroups(AUDIO, PageRequest.of(0, 2));

		List<String> shapes = Arrays.stream(statistics.getQueries()).filter(query -> query.contains(SIGNATURE))
				.distinct().toList();

		Assertions.assertThat(shapes).as("one page of groups is one grouping query plus one count: %s", shapes)
				.hasSize(2);

		// Told apart by the projection and not by the word "count": the query that
		// returns the page counts the files of each group, so both of them say it.
		String content = shapes.stream().filter(DuplicateGroupPaginationIntegrationTest::isProjection)
				.collect(Collectors.joining());
		String count = shapes.stream().filter(query -> !isProjection(query)).collect(Collectors.joining());

		Assertions.assertThat(content).as("the query that returns the page").contains("GROUP BY", "HAVING");

		Assertions.assertThat(count).as("the query that decides the total: %s", count).contains("count(")
				.contains("GROUP BY", "HAVING");
	}

	private static boolean isProjection(String query) {
		return query.contains("DuplicateGroupRawResponse");
	}

	private Statistics statistics() {
		Statistics statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();

		// Turned on here rather than in the context, so the shared configuration -
		// and with it the one context this suite is built around - stays untouched.
		statistics.setStatisticsEnabled(true);

		return statistics;
	}

	private void catalogued(Path library, String name, long sizeBytes, int copies) {
		String sha = UUID.randomUUID().toString().replace("-", "").repeat(2);

		for (int copy = 0; copy < copies; copy++) {
			CatalogFile file = CatalogFile.builder().catalogFilePublicId(UUID.randomUUID()).extension("mp3")
					.sizeBytes(sizeBytes).fileType(FileType.AUDIO).lifecycleStatus(LifecycleStatus.ACTIVE)
					.sha256(sha).modifiedAt(Instant.EPOCH).importedAt(Instant.EPOCH).build();

			CatalogFiles.catalogued(catalogFileRepository, catalogFileLocationRepository,
					CatalogFiles.located(file, library.resolve(name + "-" + copy + ".mp3")));
		}
	}
}