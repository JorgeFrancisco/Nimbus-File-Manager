package br.com.jorgemelo.nimbusfilemanager.shared.application.dto;

import java.util.List;

import org.springframework.data.domain.Page;

/**
 * Stable JSON envelope for paginated API responses. Returning Spring Data's
 * {@code PageImpl} straight from a controller works, but its JSON shape is an
 * internal detail with no cross-version stability guarantee (Spring logs a
 * warning about it). This record owns the contract instead, mirroring the exact
 * fields clients already read (notably {@code content} and {@code last}), so
 * the front-end is untouched while the warning goes away.
 */
public record PagedResponse<T>(List<T> content, int number, int size, long totalElements, int totalPages,
		boolean first, boolean last, int numberOfElements, boolean empty) {

	public static <T> PagedResponse<T> from(Page<T> page) {
		return new PagedResponse<>(page.getContent(), page.getNumber(), page.getSize(), page.getTotalElements(),
				page.getTotalPages(), page.isFirst(), page.isLast(), page.getNumberOfElements(), page.isEmpty());
	}
}