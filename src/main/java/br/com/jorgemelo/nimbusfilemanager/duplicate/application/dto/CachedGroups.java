package br.com.jorgemelo.nimbusfilemanager.duplicate.application.dto;

import java.util.List;

/**
 * A cached similarity grouping tagged with the fingerprint signature it was
 * computed from, so it is served only while that signature still holds. Generic
 * over the group response type ({@code T}) so photo and video share the same
 * cache.
 */
public record CachedGroups<T>(String signature, List<T> groups) {
}