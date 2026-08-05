package br.com.jorgemelo.nimbusfilemanager.metadata.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataDateDifference;
import br.com.jorgemelo.nimbusfilemanager.metadata.application.dto.MetadataRebuildSimulationResult;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewItemRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.model.MetadataRebuildPreviewRecord;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewItemRepository;
import br.com.jorgemelo.nimbusfilemanager.metadata.domain.repository.MetadataRebuildPreviewRepository;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;

/**
 * Storing what a dry run found, so the screen can read it after the process
 * that computed it is gone.
 */
class MetadataRebuildPreviewWriterTest {

	private static final String FOLDER = "D:\\photos";

	private static final LocalDateTime NOW = LocalDateTime.of(2026, Month.MAY, 1, 10, 0);

	private final MetadataRebuildPreviewRepository previewRepository = mock(MetadataRebuildPreviewRepository.class);
	private final MetadataRebuildPreviewItemRepository itemRepository = mock(
			MetadataRebuildPreviewItemRepository.class);

	private final MetadataRebuildPreviewWriter writer = new MetadataRebuildPreviewWriter(previewRepository,
			itemRepository);

	@Test
	void theHeaderCarriesTheCountsAndTheItemsCarryTheOrderTheyWereFoundIn() {
		writer.write(7L, FOLDER, new MetadataRebuildSimulationResult(80, 20, 50, 2,
				List.of(difference("a.jpg", DateSource.FILE_NAME), difference("b.jpg", null))));

		ArgumentCaptor<MetadataRebuildPreviewRecord> header = ArgumentCaptor
				.forClass(MetadataRebuildPreviewRecord.class);

		verify(previewRepository).save(header.capture());

		Assertions.assertThat(header.getValue()).satisfies(preview -> {
			Assertions.assertThat(preview.getExecutionId()).isEqualTo(7L);
			Assertions.assertThat(preview.getSourcePath()).isEqualTo(FOLDER);
			Assertions.assertThat(preview.getCandidates()).isEqualTo(80);
			Assertions.assertThat(preview.getSkippedByCutoff()).isEqualTo(20);
			Assertions.assertThat(preview.getExamined()).isEqualTo(50);
			Assertions.assertThat(preview.getWouldChange()).isEqualTo(2);
		});

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<MetadataRebuildPreviewItemRecord>> items = ArgumentCaptor.forClass(List.class);

		verify(itemRepository).saveAll(items.capture());

		Assertions.assertThat(items.getValue()).hasSize(2);
		Assertions.assertThat(items.getValue()).extracting(MetadataRebuildPreviewItemRecord::getOrdinal)
				.containsExactly(0, 1);
		Assertions.assertThat(items.getValue().getFirst()).satisfies(item -> {
			Assertions.assertThat(item.getExecutionId()).isEqualTo(7L);
			Assertions.assertThat(item.getPath()).isEqualTo(FOLDER + "\\a.jpg");
			Assertions.assertThat(item.getCurrentSource()).isEqualTo(DateSource.FILE_NAME);
			Assertions.assertThat(item.getNewSource()).isEqualTo(DateSource.EXIF);
		});
		Assertions.assertThat(items.getValue().getLast().getCurrentSource()).isNull();
	}

	/**
	 * A second attempt of the same execution replaces the first one's rows. Without
	 * clearing them, an attempt that found fewer differences would leave the tail
	 * of the previous one behind - rows nobody would ever be able to explain.
	 */
	@Test
	void aSecondAttemptReplacesWhatTheFirstOneLeft() {
		writer.write(7L, FOLDER, new MetadataRebuildSimulationResult(10, 0, 10, 0, List.of()));

		InOrder order = inOrder(itemRepository, previewRepository);

		order.verify(itemRepository).deleteByExecutionId(7L);
		order.verify(previewRepository).save(any());
		order.verify(itemRepository).saveAll(anyList());
	}

	private MetadataDateDifference difference(String name, DateSource currentSource) {
		return new MetadataDateDifference(FOLDER + "\\" + name, NOW.minusDays(1), currentSource, NOW, DateSource.EXIF);
	}
}