package karte;

import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;

// Texte in der Sprache des Spiels: assets/minimap/lang/<sprache>.json (de_de, en_us; andere Sprachen fallen auf Englisch zurück).
// Immer beim Anzeigen holen, nicht in Feldern merken – so wirkt ein Sprachwechsel sofort.
final class T {
    static String t(String key, Object... args) {
        return I18n.get(Karte.ID + "." + key, args);
    }

    /** Zahlenformat der Spielsprache (Deutsch: 458,9 – Englisch: 458.9). */
    static Locale locale() {
        String code = Minecraft.getInstance().getLanguageManager().getSelected();
        return Locale.forLanguageTag(code.replace('_', '-'));
    }
}
