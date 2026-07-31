package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record Inventory(@DefaultValue("false") boolean recursiveWatchDefault,
		@DefaultValue("60000") long reconciliationIntervalMillis) {
}