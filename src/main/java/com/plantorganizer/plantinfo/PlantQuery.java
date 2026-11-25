package com.plantorganizer.plantinfo;

/**
 * A request for care information about one species.
 *
 * @param scientificName the species the user confirmed (e.g. "Monstera deliciosa");
 *                       results must match it
 * @param commonName     optional common name (e.g. "Swiss cheese plant")
 * @param searchTerm     the text actually sent to the search API; normally the
 *                       scientific name, the common name on a retry
 */
public record PlantQuery(String scientificName, String commonName, String searchTerm) {

    public PlantQuery {
        if (scientificName == null || scientificName.isBlank()) {
            throw new IllegalArgumentException("A scientific name is required");
        }
        scientificName = scientificName.trim();
        commonName = (commonName == null || commonName.isBlank()) ? null : commonName.trim();
        searchTerm = (searchTerm == null || searchTerm.isBlank()) ? scientificName : searchTerm.trim();
    }

    public static PlantQuery of(String scientificName, String commonName) {
        return new PlantQuery(scientificName, commonName, scientificName);
    }

    public PlantQuery withSearchTerm(String term) {
        return new PlantQuery(scientificName, commonName, term);
    }

    /**
     * True when a page with this title and text is about this species: the
     * title is the species' name, or the text names it. A page about the whole
     * genus (or another species) is not.
     */
    public static boolean isAbout(String title, String text, String scientificName) {
        String species = binomial(scientificName);
        return !species.isEmpty() && (binomial(title).equals(species)
                || (text != null && text.toLowerCase().contains(species)));
    }

    /**
     * Genus + species epithet in lower case ("monstera deliciosa"), used to
     * check that a search hit really is the confirmed species and not just a
     * plant with a similar name. Hybrid markers ("x", "×") are ignored.
     */
    public static String binomial(String name) {
        if (name == null) {
            return "";
        }
        String[] words = name.toLowerCase().replace("×", " ").trim().split("\\s+");
        StringBuilder result = new StringBuilder();
        int taken = 0;
        for (String word : words) {
            if (word.equals("x") || word.isEmpty()) {
                continue;
            }
            if (taken > 0) {
                result.append(' ');
            }
            result.append(word);
            if (++taken == 2) {
                break;
            }
        }
        return result.toString();
    }
}
