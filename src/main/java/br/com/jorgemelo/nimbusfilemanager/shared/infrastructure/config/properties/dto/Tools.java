package br.com.jorgemelo.nimbusfilemanager.shared.infrastructure.config.properties.dto;

import org.springframework.boot.context.properties.bind.DefaultValue;

public record Tools(String ffprobe, String ffmpeg, @DefaultValue("true") boolean autoInstall) {
}