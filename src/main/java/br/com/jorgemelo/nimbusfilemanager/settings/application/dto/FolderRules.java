package br.com.jorgemelo.nimbusfilemanager.settings.application.dto;

import java.util.List;

import br.com.jorgemelo.nimbusfilemanager.settings.application.FolderMatcher;

public record FolderRules(String raw, List<String> patterns, List<FolderMatcher> matchers) {

	public static final FolderRules EMPTY = new FolderRules(null, List.of(), List.of());
}