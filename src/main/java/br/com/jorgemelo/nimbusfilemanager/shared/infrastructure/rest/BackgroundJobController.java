package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto.BackgroundJobActivity;
import br.com.jorgemelo.nimbusfilemanager.duplicate.application.fingerprint.FingerprintActivityService;
import io.swagger.v3.oas.annotations.Operation;

/**
 * What the page banner polls to keep showing background work that has no
 * execution record of its own. Answers {@code null} when nothing is running,
 * which is what tells the banner to disappear.
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
			description = "Reports work that runs outside an execution - today the photo and video fingerprint"
					+ " backlogs - so the page banner can show it. Null when nothing is running.")
	public BackgroundJobActivity current() {
		return fingerprintActivityService.current().orElse(null);
	}
}