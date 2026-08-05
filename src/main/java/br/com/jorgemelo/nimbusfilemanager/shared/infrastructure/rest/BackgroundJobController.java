package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BackgroundJobActivity;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintActivityService;
import io.swagger.v3.oas.annotations.Operation;

/**
 * What the page banner polls, and it answers {@code null} when nothing is
 * running - which is what tells the banner to disappear.
 *
 * <p>
 * It existed because the fingerprint backlogs had no execution record of their
 * own. They do now, and this stayed for a different reason: the banner wants
 * something ready to draw - a label, a link, done out of total, a percentage and
 * an estimate - and an execution row is none of those. The numbers come from the
 * row either way.
 */
@RestController
@RequestMapping("/api/background-job")
public class BackgroundJobController {

	private final FingerprintActivityService fingerprintActivityService;

	public BackgroundJobController(FingerprintActivityService fingerprintActivityService) {
		this.fingerprintActivityService = fingerprintActivityService;
	}

	@GetMapping
	@Operation(summary = "Returns the background job in progress",
			description = "Reports the background job the page banner shows - today the photo and video"
					+ " fingerprint backlogs - ready to draw. Null when nothing is running.")
	public BackgroundJobActivity current() {
		return fingerprintActivityService.current().orElse(null);
	}
}