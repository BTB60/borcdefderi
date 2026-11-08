package az.borcdefteri.common;

/**
 * LIKE patterns for user search text. Wildcards typed by the user (% and _) are escaped, and lower-casing is
 * left to PostgreSQL (use {@code LOWER(column) LIKE LOWER(:q)}) so both sides use the same rules — Java's
 * toLowerCase turns the Azerbaijani "İ" into "i̇", which never matched names such as "İlham".
 */
public final class Search {

    private Search() {}

    /** "%text%" with \, % and _ escaped; "%" (match all) for blank input. */
    public static String contains(String text) {
        if (text == null || text.isBlank()) {
            return "%";
        }
        String escaped = text.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
        return "%" + escaped + "%";
    }
}
