package br.com.jorgemelo.nimbusfilemanager.organization.domain.repository.projection;

public interface MediaLocationReconcileProjection {

	Long getCatalogFileId();

	String getFileKey();

	String getCurrentPath();
}