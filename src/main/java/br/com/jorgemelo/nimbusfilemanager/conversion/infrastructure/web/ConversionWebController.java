package br.com.jorgemelo.nimbusfilemanager.conversion.infrastructure.web;

import static br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants.PAGE_SIZE_KEY;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionCandidateService;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.ConversionCommitService;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.HardwareEncoderProbe;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.VideoConversionAsyncRunner;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.constants.ConversionConstants;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionCandidateView;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionOptions;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionProgress;
import br.com.jorgemelo.nimbusfilemanager.conversion.application.dto.ConversionRequest;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.AudioHandling;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.ConversionQuality;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.NameAffixPosition;
import br.com.jorgemelo.nimbusfilemanager.conversion.domain.enums.OriginalDisposition;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.quarantine.application.QuarantinePurgeService;
import br.com.jorgemelo.nimbusfilemanager.shared.i18n.LocalizedComponent;
import br.com.jorgemelo.nimbusfilemanager.shared.util.EnumUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PageUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;

/**
 * Renders the "Conversão" screen and drives the background batch behind it.
 *
 * <p>
 * The three options (quality, audio, what happens to the original) are stored
 * per user like every other screen preference, so reopening the screen offers
 * the same choices as last time instead of silently resetting to the defaults -
 * which matters here, since one of them decides whether the original file is
 * kept.
 */
@Controller
public class ConversionWebController extends LocalizedComponent {

	// Conversion is selected in bulk and runs for hours, so the page doubles as the
	// selection unit: picking 300 candidates at once is a normal way to use it.
	private static final List<Integer> PAGE_SIZES = List.of(25, 50, 100, 150, 200, 250, 300);
	private static final int DEFAULT_PAGE_SIZE = 25;
	private static final String SYSTEM_USERNAME = "system";

	private final ConversionCandidateService conversionCandidateService;
	private final VideoConversionAsyncRunner videoConversionAsyncRunner;
	private final ConversionCommitService conversionCommitService;
	private final QuarantinePurgeService quarantinePurgeService;
	private final UserPagePreferenceService userPagePreferenceService;
	private final HardwareEncoderProbe hardwareEncoderProbe;

	@Autowired
	public ConversionWebController(ConversionCandidateService conversionCandidateService,
			VideoConversionAsyncRunner videoConversionAsyncRunner, ConversionCommitService conversionCommitService,
			QuarantinePurgeService quarantinePurgeService, UserPagePreferenceService userPagePreferenceService,
			HardwareEncoderProbe hardwareEncoderProbe) {
		this.conversionCandidateService = conversionCandidateService;
		this.videoConversionAsyncRunner = videoConversionAsyncRunner;
		this.conversionCommitService = conversionCommitService;
		this.quarantinePurgeService = quarantinePurgeService;
		this.userPagePreferenceService = userPagePreferenceService;
		this.hardwareEncoderProbe = hardwareEncoderProbe;
	}

	@GetMapping("/app/conversion")
	public String conversion(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			Authentication authentication, Model model) {
		Map<String, String> preferences = preferencesOf(authentication);

		int pageSize = resolvePageSize(size, authentication, preferences);

		int pageNumber = page == null || page < 0 ? 0 : page;

		Page<ConversionCandidateView> candidates = conversionCandidateService
				.candidates(PageRequest.of(pageNumber, pageSize));

		model.addAttribute("candidates", candidates.getContent());
		model.addAttribute("pageNumber", candidates.getNumber());
		model.addAttribute("hasPrevious", candidates.getNumber() > 0);
		model.addAttribute("hasNext", candidates.hasNext());
		model.addAttribute("totalElements", candidates.getTotalElements());
		model.addAttribute("totalPages", candidates.getTotalPages());
		model.addAttribute("pageSizes", PAGE_SIZES);
		model.addAttribute(PAGE_SIZE_KEY, pageSize);

		model.addAttribute("quality", storedQuality(preferences).name());
		// Only offered where a real encode session opens: an option that fails halfway
		// through a batch is worse than an option that was never there.
		model.addAttribute("hardwareEncoder", hardwareEncoderProbe.isAvailable());
		model.addAttribute("recommendedQuality", recommendedQuality().name());
		model.addAttribute("audio", storedAudio(preferences).name());
		model.addAttribute("disposition", storedDisposition(preferences).name());
		model.addAttribute("nameAffix", storedAffix(preferences));
		model.addAttribute("affixPosition", storedAffixPosition(preferences).name());
		model.addAttribute("quarantineConfigured", conversionCommitService.quarantineRoot().isPresent());
		// The quarantine is not forever: the screen has to say when the original is
		// expunged for good. Zero means no purge is scheduled, and then no deadline is
		// shown - promising one nobody enforces would be worse than saying nothing.
		model.addAttribute("quarantineRetentionDays", quarantinePurgeService.retentionDays());
		model.addAttribute("conversionRunning", videoConversionAsyncRunner.isRunning());

		return "app/conversion";
	}

	/**
	 * Starts the batch in the background and answers with the first progress
	 * snapshot; the screen then polls {@link #progress()} until it finishes. The
	 * chosen options are persisted here - the same request that acts on them - so
	 * what the screen reopens with is exactly what was last used.
	 */
	@PostMapping("/app/conversion/convert")
	@ResponseBody
	public ConversionProgress convert(@RequestBody ConversionRequest request, Authentication authentication) {
		List<UUID> ids = request == null || request.ids() == null ? List.of() : request.ids();

		ConversionOptions options = request == null ? ConversionOptions.defaults()
				: ConversionOptions.of(request.quality(), request.audio(), request.disposition(), request.nameAffix(),
						request.affixPosition());

		remember(authentication, options);

		if (videoConversionAsyncRunner.start(ids.size())) {
			try {
				videoConversionAsyncRunner.run(ids, options);
			} catch (TaskRejectedException _) {
				// The @Async task was never submitted (shared executor saturated or shutting
				// down), so run()'s finally never releases the claim - release it here so the
				// screen isn't stuck "in progress" and future conversions can still start.
				videoConversionAsyncRunner.releaseRejectedSubmission();
			}
		}

		return progress();
	}

	/**
	 * Prunes the selection the browser kept: answers with the subset of
	 * {@code ids} that is still convertible. The screen holds its selection across
	 * pagination, so a batch that finished while it was closed left ids behind and
	 * the counter announced files that no longer exist as candidates. The decision
	 * is the server's; the screen only shows what comes back.
	 */
	@PostMapping("/app/conversion/selection")
	@ResponseBody
	public List<UUID> selection(@RequestBody ConversionRequest request) {
		return conversionCandidateService.convertible(request == null ? List.of() : request.ids());
	}

	/**
	 * Stores the options as soon as the user picks them, without waiting for a
	 * conversion to be started: the screen is where the choice is made, so that is
	 * where it has to be remembered - otherwise leaving the page after changing an
	 * option silently discards it.
	 */
	@PostMapping("/app/conversion/preferences")
	@ResponseBody
	public ConversionOptions rememberOptions(@RequestBody ConversionOptions options, Authentication authentication) {
		ConversionOptions effective = options == null ? ConversionOptions.defaults()
				: ConversionOptions.of(options.quality(), options.audio(), options.disposition(), options.nameAffix(),
						options.affixPosition());

		remember(authentication, effective);

		return effective;
	}

	/**
	 * Stops the running batch: the file being encoded is abandoned (its
	 * half-written output deleted, the source untouched) and no further file is
	 * started. Answers with the progress snapshot so the screen updates
	 * immediately.
	 */
	@PostMapping("/app/conversion/cancel")
	@ResponseBody
	public ConversionProgress cancel() {
		videoConversionAsyncRunner.cancel();

		return progress();
	}

	@GetMapping("/app/conversion/progress")
	@ResponseBody
	public ConversionProgress progress() {
		boolean running = videoConversionAsyncRunner.isRunning();

		return new ConversionProgress(running, videoConversionAsyncRunner.processed(),
				videoConversionAsyncRunner.total(), videoConversionAsyncRunner.percent(),
				videoConversionAsyncRunner.filePercent(), videoConversionAsyncRunner.etaSeconds(),
				videoConversionAsyncRunner.currentFile(), running ? null : videoConversionAsyncRunner.lastResult());
	}

	private void remember(Authentication authentication, ConversionOptions options) {
		String username = SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME);

		userPagePreferenceService.save(username, ConversionConstants.PAGE_KEY, ConversionConstants.QUALITY_KEY,
				options.quality().name());
		userPagePreferenceService.save(username, ConversionConstants.PAGE_KEY, ConversionConstants.AUDIO_KEY,
				options.audio().name());
		userPagePreferenceService.save(username, ConversionConstants.PAGE_KEY, ConversionConstants.DISPOSITION_KEY,
				options.disposition().name());
		userPagePreferenceService.save(username, ConversionConstants.PAGE_KEY, ConversionConstants.AFFIX_KEY,
				storableAffix(options.nameAffix()));
		userPagePreferenceService.save(username, ConversionConstants.PAGE_KEY, ConversionConstants.AFFIX_POSITION_KEY,
				options.affixPosition().name());
	}

	/**
	 * Preferences never keep a blank value, so "no affix at all" is stored as a
	 * marker the affix itself can never be: the naming layer strips path separators
	 * from whatever the user types, so a stored separator can only mean this.
	 */
	private String storableAffix(String affix) {
		return affix == null || affix.isBlank() ? ConversionConstants.EMPTY_AFFIX_MARKER : affix;
	}

	private String storedAffix(Map<String, String> preferences) {
		String stored = preferences.get(ConversionConstants.AFFIX_KEY);

		if (stored == null) {
			return ConversionOptions.defaults().nameAffix();
		}

		return ConversionConstants.EMPTY_AFFIX_MARKER.equals(stored) ? "" : stored;
	}

	private NameAffixPosition storedAffixPosition(Map<String, String> preferences) {
		NameAffixPosition stored = EnumUtils.valueOfOrNull(NameAffixPosition.class,
				preferences.get(ConversionConstants.AFFIX_POSITION_KEY));

		return stored == null ? ConversionOptions.defaults().affixPosition() : stored;
	}

	private Map<String, String> preferencesOf(Authentication authentication) {
		return userPagePreferenceService.find(SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME),
				ConversionConstants.PAGE_KEY);
	}

	/**
	 * The profile the screen opens on. A stored choice wins, except when it asks for
	 * a GPU this machine does not have - the equivalent software profile takes over,
	 * so the form never opens with nothing selected and the batch never silently
	 * changes quality level.
	 */
	private ConversionQuality storedQuality(Map<String, String> preferences) {
		ConversionQuality stored = EnumUtils.valueOfOrNull(ConversionQuality.class,
				preferences.get(ConversionConstants.QUALITY_KEY));

		if (stored == null) {
			return recommendedQuality();
		}

		return stored.requiresHardware() && !hardwareEncoderProbe.isAvailable() ? stored.softwareEquivalent() : stored;
	}

	/**
	 * Measured on real footage, the hardware balanced profile matches the software
	 * one in size and in perceived quality while finishing in a fraction of the
	 * time, so it is the better deal wherever the machine can run it. Where it
	 * cannot, the software profile is the recommendation.
	 */
	private ConversionQuality recommendedQuality() {
		return hardwareEncoderProbe.isAvailable() ? ConversionQuality.FAST_BALANCED : ConversionQuality.BALANCED;
	}

	private AudioHandling storedAudio(Map<String, String> preferences) {
		AudioHandling stored = EnumUtils.valueOfOrNull(AudioHandling.class,
				preferences.get(ConversionConstants.AUDIO_KEY));

		return stored == null ? ConversionOptions.defaults().audio() : stored;
	}

	private OriginalDisposition storedDisposition(Map<String, String> preferences) {
		OriginalDisposition stored = EnumUtils.valueOfOrNull(OriginalDisposition.class,
				preferences.get(ConversionConstants.DISPOSITION_KEY));

		return stored == null ? ConversionOptions.defaults().disposition() : stored;
	}

	private int resolvePageSize(Integer requested, Authentication authentication, Map<String, String> preferences) {
		if (requested != null && PAGE_SIZES.contains(requested)) {
			userPagePreferenceService.save(SecurityUtils.usernameOr(authentication, SYSTEM_USERNAME),
					ConversionConstants.PAGE_KEY, PAGE_SIZE_KEY, requested.toString());

			return requested;
		}

		return PageUtils.validSizeOrDefault(preferences.get(PAGE_SIZE_KEY), PAGE_SIZES, DEFAULT_PAGE_SIZE);
	}
}