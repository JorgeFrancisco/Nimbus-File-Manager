package br.com.jorgemelo.nimbusfilemanager.organization.application.dto;

import java.util.List;

public record Scan(OrganizationReconcileResponse response, List<PathSync> pathSyncs) {
}