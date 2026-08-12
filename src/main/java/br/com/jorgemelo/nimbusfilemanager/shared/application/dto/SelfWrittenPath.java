package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.nio.file.Path;

import br.com.jorgemelo.nimbusfilemanager.shared.domain.enums.SelfWriteRole;

/**
 * A path together with what this product is doing to it - the unit both halves
 * of self-write speak in, whether announcing an effect or asking whether one
 * was announced.
 */
public record SelfWrittenPath(Path path, SelfWriteRole role) {
}