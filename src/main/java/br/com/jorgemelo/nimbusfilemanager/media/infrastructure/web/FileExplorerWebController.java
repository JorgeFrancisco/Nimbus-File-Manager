package br.com.jorgemelo.nimbusfilemanager.media.infrastructure.web;

import static br.com.jorgemelo.nimbusfilemanager.media.application.constants.MediaConstants.FILES_PATH_KEY;
import static br.com.jorgemelo.nimbusfilemanager.media.application.constants.MediaConstants.FILES_SIZE_KEY;
import static br.com.jorgemelo.nimbusfilemanager.media.application.constants.MediaConstants.FILES_SORT_KEY;
import static br.com.jorgemelo.nimbusfilemanager.media.application.constants.MediaConstants.FILES_VIEW_KEY;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import br.com.jorgemelo.nimbusfilemanager.media.application.dto.FileExplorerView;
import br.com.jorgemelo.nimbusfilemanager.media.application.explorer.FileExplorerService;
import br.com.jorgemelo.nimbusfilemanager.media.domain.enums.FileSortOption;
import br.com.jorgemelo.nimbusfilemanager.preferences.application.UserPagePreferenceService;
import br.com.jorgemelo.nimbusfilemanager.settings.application.ScanExclusionService;
import br.com.jorgemelo.nimbusfilemanager.shared.application.constants.SharedConstants;
import br.com.jorgemelo.nimbusfilemanager.shared.util.PathUtils;
import br.com.jorgemelo.nimbusfilemanager.shared.util.SecurityUtils;
import jakarta.servlet.http.HttpServletResponse;

@Controller
public class FileExplorerWebController {

	private final FileExplorerService fileExplorerService;
	private final UserPagePreferenceService userPagePreferenceService;
	private final ScanExclusionService scanExclusionService;

	@Autowired
	public FileExplorerWebController(FileExplorerService fileExplorerService,
			UserPagePreferenceService userPagePreferenceService, ScanExclusionService scanExclusionService) {
		this.fileExplorerService = fileExplorerService;
		this.userPagePreferenceService = userPagePreferenceService;
		this.scanExclusionService = scanExclusionService;
	}

	public String files(String path, String view, Integer page, Integer size, Authentication authentication,
			Model model) {
		return files(path, view, page, size, "name", authentication, model);
	}

	@GetMapping("/app/files")
	public String files(@RequestParam(required = false) String path, @RequestParam(required = false) String view,
			@RequestParam(defaultValue = "0") Integer page, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort, Authentication authentication, Model model) {
		String username = username(authentication);

		Map<String, String> preferences = userPagePreferenceService.find(username, SharedConstants.FILES_PAGE_KEY);

		String selectedPath = value(path, preferences.get(FILES_PATH_KEY));
		String selectedView = value(view, preferences.get(FILES_VIEW_KEY));
		Integer selectedSize = value(size, preferences.get(FILES_SIZE_KEY));

		if (path != null) {
			userPagePreferenceService.save(username, SharedConstants.FILES_PAGE_KEY, FILES_PATH_KEY, path);
		}

		if (view != null) {
			userPagePreferenceService.save(username, SharedConstants.FILES_PAGE_KEY, FILES_VIEW_KEY, view);
		}

		if (size != null) {
			userPagePreferenceService.save(username, SharedConstants.FILES_PAGE_KEY, FILES_SIZE_KEY, size.toString());
		}

		String requestedSort = sort == null ? preferences.get(FILES_SORT_KEY) : sort;
		String selectedSort = normalizeSort(requestedSort);

		if (sort != null) {
			userPagePreferenceService.save(username, SharedConstants.FILES_PAGE_KEY, FILES_SORT_KEY, selectedSort);
		}

		model.addAttribute("sizes", FileExplorerService.PAGE_SIZES);
		model.addAttribute("sort", selectedSort);
		model.addAttribute("sortOptions", FileSortOption.values());
		model.addAttribute("drives", fileExplorerService.availableDrives());
		model.addAttribute("explorer",
				"name".equals(selectedSort) ? fileExplorerService.browse(selectedPath, selectedView, page, selectedSize)
						: fileExplorerService.browse(selectedPath, selectedView, page, selectedSize, selectedSort));

		return "app/files";
	}

	/**
	 * Returns just the next page of rows/tiles for the current listing, rendered as
	 * an HTML fragment (no page shell), so files.js can append it to the DOM for
	 * infinite scroll instead of a full "Proxima" page reload. Used both to satisfy
	 * an on-scroll request and to prefetch the next page ahead of time.
	 */
	@GetMapping("/app/files/items")
	public String items(@RequestParam(required = false) String path, @RequestParam(required = false) String view,
			@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
			@RequestParam(required = false) String sort, HttpServletResponse response, Model model) {
		String selectedSort = normalizeSort(sort);

		FileExplorerView explorer = "name".equals(selectedSort) ? fileExplorerService.browse(path, view, page, size)
				: fileExplorerService.browse(path, view, page, size, selectedSort);

		model.addAttribute("sort", selectedSort);
		model.addAttribute("explorer", explorer);

		response.setHeader("X-Has-Next", Boolean.toString(explorer.hasNext()));

		return "app/files :: " + ("details".equals(explorer.viewMode()) ? "rows" : "tiles");
	}

	private String normalizeSort(String sort) {
		return FileSortOption.fromValue(sort == null ? null : sort.toLowerCase()).value();
	}

	@PostMapping("/app/preferences/sidebar")
	@ResponseBody
	public Map<String, Boolean> toggleSidebar(Authentication authentication) {
		String username = username(authentication);

		Map<String, String> preferences = userPagePreferenceService.find(username, SharedConstants.LAYOUT_PAGE_KEY);

		boolean collapsed = !Boolean.parseBoolean(preferences.get(SharedConstants.SIDEBAR_KEY));

		userPagePreferenceService.save(username, SharedConstants.LAYOUT_PAGE_KEY, SharedConstants.SIDEBAR_KEY,
				Boolean.toString(collapsed));

		return Map.of("collapsed", collapsed);
	}

	@GetMapping("/app/files/preview")
	public ResponseEntity<FileSystemResource> preview(@RequestParam String path) throws IOException {
		Path file = PathUtils.normalizePath(path);

		if (isExcluded(file) || !Files.exists(file) || !Files.isRegularFile(file)) {
			return ResponseEntity.notFound().build();
		}

		String contentType = Files.probeContentType(file);

		if (contentType == null || contentType.isBlank()) {
			contentType = MediaType.APPLICATION_OCTET_STREAM_VALUE;
		}

		return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "inline")
				.cacheControl(CacheControl.noCache()).contentLength(Files.size(file))
				.contentType(MediaType.parseMediaType(contentType)).body(new FileSystemResource(file));
	}

	private String value(String requestValue, String preferenceValue) {
		return requestValue == null ? preferenceValue : requestValue;
	}

	private Integer value(Integer requestValue, String preferenceValue) {
		if (requestValue != null) {
			return requestValue;
		}

		if (preferenceValue == null || preferenceValue.isBlank()) {
			return null;
		}

		try {
			return Integer.valueOf(preferenceValue);
		} catch (NumberFormatException _) {
			return null;
		}
	}

	private boolean isExcluded(Path file) {
		return scanExclusionService.isExcluded(file);
	}

	private String username(Authentication authentication) {
		return SecurityUtils.usernameOr(authentication, null);
	}
}