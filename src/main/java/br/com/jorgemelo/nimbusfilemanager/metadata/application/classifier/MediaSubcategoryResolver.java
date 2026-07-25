package br.com.jorgemelo.nimbusfilemanager.metadata.application.classifier;

import java.nio.file.Path;

import org.springframework.stereotype.Service;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.MediaSubcategory;

/**
 * Classification is delegated to {@link MediaSubcategoryRuleEngine} (a list of
 * pluggable {@link MediaSubcategoryRule} beans, evaluated in order) instead of
 * an if/else chain.
 */
@Service
public class MediaSubcategoryResolver {

	private final MediaSubcategoryRuleEngine ruleEngine;

	public MediaSubcategoryResolver(MediaSubcategoryRuleEngine ruleEngine) {
		this.ruleEngine = ruleEngine;
	}

	public MediaSubcategory resolve(Path file) {
		if (file == null || file.getFileName() == null) {
			return MediaSubcategory.UNKNOWN;
		}

		return ruleEngine.resolve(file.getFileName().toString(), file.toString());
	}
}