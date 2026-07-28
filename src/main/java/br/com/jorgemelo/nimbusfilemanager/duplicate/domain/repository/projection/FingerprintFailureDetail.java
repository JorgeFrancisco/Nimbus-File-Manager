package br.com.jorgemelo.nimbusfilemanager.duplicate.domain.repository.projection;

import br.com.jorgemelo.nimbusfilemanager.duplicate.domain.enums.FingerprintFailureReason;

/** One exhausted visual-fingerprint failure shown in the duplicates modal. */
public record FingerprintFailureDetail(String path, FingerprintFailureReason reason, String error) {
}