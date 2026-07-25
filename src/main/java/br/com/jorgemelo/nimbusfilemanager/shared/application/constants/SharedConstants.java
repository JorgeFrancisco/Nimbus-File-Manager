package br.com.jorgemelo.nimbusfilemanager.shared.application.constants;

/**
 * Contract data constants shared across the application UI. The cross-page
 * preference page keys ({@code layout} for the shell theme/sidebar, {@code
 * files} for the Arquivos defaults, {@code app} for the shell locale) are
 * read/written by more than one domain, so they live here instead of in any
 * single controller. The theme preference key and its values are read both when
 * rendering the shell (AppViewModelAdvice) and when the theme is edited from the
 * Preferencias tab. The flash/model feedback keys and the settings redirect are
 * reused by the several web controllers that back the Configuracoes screen. The
 * page-size preference key is written by every server-paginated screen
 * (Duplicados, Quarentena, ...), so it lives here rather than duplicated inline
 * in each controller.
 */
public final class SharedConstants {

	public static final String LAYOUT_PAGE_KEY = "layout";
	public static final String FILES_PAGE_KEY = "files";
	public static final String APP_PAGE_KEY = "app";
	public static final String THEME_PREFERENCE_KEY = "theme";
	public static final String THEME_DARK = "dark";
	public static final String THEME_LIGHT = "light";
	public static final String SIDEBAR_KEY = "sidebar-collapsed";
	public static final String ATTR_ERROR = "error";
	public static final String ATTR_SUCCESS = "success";
	public static final String REDIRECT_SETTINGS = "redirect:/app/settings";
	public static final String PAGE_SIZE_KEY = "pageSize";

	private SharedConstants() {
	}
}