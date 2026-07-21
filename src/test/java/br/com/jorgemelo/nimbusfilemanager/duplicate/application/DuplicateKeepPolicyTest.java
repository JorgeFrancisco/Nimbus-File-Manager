package br.com.jorgemelo.nimbusfilemanager.duplicate.application;

import java.time.LocalDateTime;
import java.time.Month;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.Decision;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.Signals;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Reason;
import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.Verdict;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.DateSource;
import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

class DuplicateKeepPolicyTest {

	private final DuplicateKeepPolicy policy = new DuplicateKeepPolicy();

	private static final UUID A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
	private static final UUID B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");

	private Signals original(UUID id, int w, int h) {
		return new Signals(id, true, MediaSubcategory.CAMERA, w, h, DateSource.EXIF,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));
	}

	private Signals whatsapp(UUID id, int w, int h) {
		return new Signals(id, false, MediaSubcategory.WHATSAPP, w, h, DateSource.FILE_NAME,
				LocalDateTime.of(2020, Month.JANUARY, 1, 0, 0), LocalDateTime.of(2021, Month.JUNE, 1, 8, 0));
	}

	private Signals edited(UUID id, int w, int h) {
		return new Signals(id, false, MediaSubcategory.AIRBRUSH, w, h, DateSource.FILE_NAME,
				LocalDateTime.of(2020, Month.JANUARY, 2, 0, 0), LocalDateTime.of(2021, Month.JUNE, 1, 8, 0));
	}

	@Test
	void keepsOriginalAndMarksWhatsAppCopy() {
		Map<UUID, Decision> d = policy.decide(List.of(original(A, 4000, 3000), whatsapp(B, 1600, 1200)), false);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.ORIGINAL))
				.containsEntry(B, new Decision(Verdict.DELETE_CANDIDATE, Reason.WHATSAPP_COPY));
	}

	@Test
	void keepsOriginalAndMarksEditedCopy() {
		Map<UUID, Decision> d = policy.decide(List.of(original(A, 4000, 3000), edited(B, 4000, 3000)), false);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.ORIGINAL))
				.containsEntry(B, new Decision(Verdict.DELETE_CANDIDATE, Reason.EDITED_COPY));
	}

	@Test
	void keepsOriginalEvenWhenDerivativeHasHigherResolution() {
		// categorical: originality comes before resolution
		Map<UUID, Decision> d = policy.decide(List.of(original(A, 2000, 1500), edited(B, 6000, 4000)), false);

		Assertions.assertThat(d.get(A).verdict()).isEqualTo(Verdict.KEEP);
		Assertions.assertThat(d.get(B).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);
	}

	@Test
	void doesNotMarkAnyoneWhenNoClearOriginal() {
		// only WhatsApp copies -> trava
		Map<UUID, Decision> d = policy.decide(List.of(whatsapp(A, 1600, 1200), whatsapp(B, 800, 600)), false);

		Assertions.assertThat(d.values()).noneMatch(x -> x.verdict() == Verdict.DELETE_CANDIDATE);
		Assertions.assertThat(d.get(A).verdict()).isEqualTo(Verdict.KEEP); // best (higher res) = suggestion
		Assertions.assertThat(d.get(B).verdict()).isEqualTo(Verdict.REVIEW);
	}

	@Test
	void doesNotMarkWhenTwoOriginalsLikeABurst() {
		Map<UUID, Decision> d = policy.decide(List.of(original(A, 4000, 3000), original(B, 4000, 3000)), false);

		Assertions.assertThat(d.values()).noneMatch(x -> x.verdict() == Verdict.DELETE_CANDIDATE);
		Assertions.assertThat(d.values()).anyMatch(x -> x.verdict() == Verdict.REVIEW);
	}

	@Test
	void exactGroupBypassesTravaEvenWithoutClearOriginal() {
		Map<UUID, Decision> d = policy.decide(List.of(whatsapp(A, 1600, 1200), whatsapp(B, 1600, 1200)), true);

		Assertions.assertThat(d.values()).anyMatch(x -> x.verdict() == Verdict.KEEP);
		Assertions.assertThat(d.values()).anyMatch(x -> x.verdict() == Verdict.DELETE_CANDIDATE);
		Assertions.assertThat(d.values()).noneMatch(x -> x.verdict() == Verdict.REVIEW);
	}

	@Test
	void tiebreakPrefersHigherResolution() {
		Map<UUID, Decision> d = policy.decide(List.of(whatsapp(A, 800, 600), whatsapp(B, 1600, 1200)), true);

		Assertions.assertThat(d.get(B).verdict()).isEqualTo(Verdict.KEEP); // higher resolution
		Assertions.assertThat(d.get(A).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);
	}

	@Test
	void keepsNonDerivativeAudioOverWhatsAppCopyThatLooksOlderByDateOnlyName() {
		// Audio has no EXIF, so neither sibling is "original" by embedded metadata; the
		// WhatsApp copy's date-only name (midnight) looks older than the source's real
		// timestamp. Without the derivative penalty the copy would win the "oldest"
		// tiebreak.
		Signals source = new Signals(A, false, MediaSubcategory.OTHER, null, null, DateSource.FILE_NAME_CONFIRMED,
				LocalDateTime.of(2026, Month.JULY, 4, 14, 35, 46), LocalDateTime.of(2026, Month.JULY, 4, 14, 36, 14));

		Signals copy = new Signals(B, false, MediaSubcategory.WHATSAPP, null, null, DateSource.FILE_NAME,
				LocalDateTime.of(2026, Month.JULY, 4, 0, 0, 0), LocalDateTime.of(2026, Month.JULY, 4, 14, 37, 51));

		Map<UUID, Decision> d = policy.decide(List.of(copy, source), true);

		Assertions.assertThat(d.get(A).verdict()).isEqualTo(Verdict.KEEP);
		Assertions.assertThat(d).containsEntry(B, new Decision(Verdict.DELETE_CANDIDATE, Reason.WHATSAPP_COPY));
	}

	private Signals signal(UUID id, boolean camera, MediaSubcategory subcategory, DateSource dateSource) {
		return new Signals(id, camera, subcategory, 4000, 3000, dateSource,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));
	}

	@Test
	void treatsCameraExifAloneAsOriginalEvenWithoutAnEmbeddedDate() {
		// hasCameraExif is an independent "original" signal: a file whose date fell
		// back to the
		// file name is still the original when it carries camera EXIF.
		Signals cameraOnly = signal(A, true, MediaSubcategory.CAMERA, DateSource.FILE_NAME);

		Map<UUID, Decision> d = policy.decide(List.of(cameraOnly, whatsapp(B, 1600, 1200)), false);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.ORIGINAL))
				.containsEntry(B, new Decision(Verdict.DELETE_CANDIDATE, Reason.WHATSAPP_COPY));
	}

	@Test
	void treatsAnEmbeddedExifDateAsOriginalEvenWithoutCameraExif() {
		// A date sourced from EXIF marks the original even when no camera manufacturer
		// tag is
		// present (e.g. some scanners/apps write only the date).
		Signals embeddedDate = signal(A, false, MediaSubcategory.OTHER, DateSource.EXIF);

		Map<UUID, Decision> d = policy.decide(List.of(embeddedDate, whatsapp(B, 1600, 1200)), false);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.ORIGINAL));
		Assertions.assertThat(d.get(B).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);
	}

	@Test
	void treatsAMediaInfoDateAsOriginal() {
		// Video keeps its capture date via MEDIA_INFO rather than EXIF; that also
		// counts as the
		// intact embedded metadata of an original.
		Signals video = signal(A, false, MediaSubcategory.OTHER, DateSource.MEDIA_INFO);

		Map<UUID, Decision> d = policy.decide(List.of(video, whatsapp(B, 1600, 1200)), false);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.ORIGINAL));
		Assertions.assertThat(d.get(B).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);
	}

	@Test
	void aNonDerivativeFileWithoutEmbeddedMetadataIsNotAnOriginal() {
		// No camera EXIF and only a file-name date -> not "intact embedded metadata",
		// so it is a
		// plain derivative next to a true original: kept file is the original, the
		// other is a
		// DELETE_CANDIDATE with the generic DERIVATIVE reason (non-exact group).
		Signals plain = signal(B, false, MediaSubcategory.OTHER, DateSource.FILE_NAME);

		Map<UUID, Decision> d = policy.decide(List.of(original(A, 4000, 3000), plain), false);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.ORIGINAL))
				.containsEntry(B, new Decision(Verdict.DELETE_CANDIDATE, Reason.DERIVATIVE));
	}

	@Test
	void exactGroupWithoutOriginalKeepsBestInGroupAndMarksIdenticalCopy() {
		// No original, but an exact (byte-identical) group is always safe to dedupe:
		// the higher
		// resolution wins as BEST_IN_GROUP and the sibling is an IDENTICAL_COPY.
		Signals high = signal(A, false, MediaSubcategory.OTHER, DateSource.FILE_NAME);
		Signals low = new Signals(B, false, MediaSubcategory.OTHER, 800, 600, DateSource.FILE_NAME,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));

		Map<UUID, Decision> d = policy.decide(List.of(high, low), true);

		Assertions.assertThat(d).containsEntry(A, new Decision(Verdict.KEEP, Reason.BEST_IN_GROUP))
				.containsEntry(B, new Decision(Verdict.DELETE_CANDIDATE, Reason.IDENTICAL_COPY));
	}

	@Test
	void returnsNoDecisionsForNullOrEmptyGroup() {
		Assertions.assertThat(policy.decide(null, false)).isEmpty();
		Assertions.assertThat(policy.decide(List.of(), true)).isEmpty();
	}

	@Test
	void aDerivativeMarkerBeatsCameraExifSoTheFileIsNotOriginal() {
		// Camera EXIF present, but the WhatsApp derivative marker wins: not "original".
		Signals cameraWhatsApp = new Signals(A, true, MediaSubcategory.WHATSAPP, 4000, 3000, DateSource.EXIF,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));

		Map<UUID, Decision> d = policy.decide(List.of(cameraWhatsApp, original(B, 4000, 3000)), false);

		Assertions.assertThat(d).containsEntry(B, new Decision(Verdict.KEEP, Reason.ORIGINAL));
		Assertions.assertThat(d.get(A).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);
	}

	@Test
	void tiebreakFallsToDateReliabilityWhenOriginalityAndPixelsTie() {
		// Non-original, non-derivative siblings with identical pixels: the more reliable
		// date source is kept (exact group -> a candidate is marked, not REVIEW).
		Map<UUID, Decision> byModifiedOverCreated = policy
				.decide(List.of(tiebreak(B, DateSource.FILE_CREATED_AT), tiebreak(A, DateSource.FILE_MODIFIED_AT)), true);

		Assertions.assertThat(byModifiedOverCreated.get(A).verdict()).isEqualTo(Verdict.KEEP);
		Assertions.assertThat(byModifiedOverCreated.get(B).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);

		Map<UUID, Decision> byConfirmedOverUnknown = policy
				.decide(List.of(tiebreak(B, DateSource.UNKNOWN), tiebreak(A, DateSource.FILE_NAME_CONFIRMED)), true);

		Assertions.assertThat(byConfirmedOverUnknown.get(A).verdict()).isEqualTo(Verdict.KEEP);
	}

	@Test
	void aMissingDimensionCountsAsZeroPixels() {
		Signals noHeight = new Signals(A, false, MediaSubcategory.OTHER, 4000, null, DateSource.FILE_NAME,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));
		Signals withDimensions = new Signals(B, false, MediaSubcategory.OTHER, 800, 600, DateSource.FILE_NAME,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));

		Map<UUID, Decision> d = policy.decide(List.of(noHeight, withDimensions), true);

		Assertions.assertThat(d.get(B).verdict()).isEqualTo(Verdict.KEEP);
		Assertions.assertThat(d.get(A).verdict()).isEqualTo(Verdict.DELETE_CANDIDATE);
	}

	private Signals tiebreak(UUID id, DateSource dateSource) {
		return new Signals(id, false, MediaSubcategory.OTHER, 1000, 1000, dateSource,
				LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0), LocalDateTime.of(2020, Month.JANUARY, 1, 10, 0));
	}
}