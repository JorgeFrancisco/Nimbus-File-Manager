package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "nimbus-file-manager")
public record NimbusFileManagerProperties(

		String workspace,

		Tools tools, Inventory inventory, Api api, Security security, @DefaultValue Email email) {
}