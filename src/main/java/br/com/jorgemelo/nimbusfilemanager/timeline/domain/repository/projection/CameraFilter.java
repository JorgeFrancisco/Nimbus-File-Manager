package br.com.jorgemelo.nimbusfilemanager.timeline.domain.repository.projection;

/**
 * Which camera took it, as the metadata records it.
 *
 * <p>
 * Both halves are matched case-insensitively and by prefix, because what a
 * person types is "canon" and what EXIF stores is "Canon EOS 5D Mark III" -
 * demanding the exact string would be a filter nobody could use.
 *
 * @param manufacturer the maker, or {@code null} for any
 * @param model the model, or {@code null} for any
 */
public record CameraFilter(String manufacturer, String model) {

	public static final CameraFilter ANY = new CameraFilter(null, null);
}