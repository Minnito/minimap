package karte;

import client.api.Draw;
import client.api.HudColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;

// Aussehen von Minimap, Weltkarte und Menüs an einer Stelle: eine Schrift (die der Client-Menüs, Draw.ui – keine Mischung
// aus Pixel- und Menüschrift), dunkle Flächen mit klarem Kontrast, Akzent = HUD-Farbe des Clients.
final class Stil {
    static final int FLAECHE = 0xF017171F;   // Panels
    static final int RAHMEN = 0xFF17171F;    // Minimap-Rahmen, deckend
    static final int ERHOBEN = 0xFF2C2C37;   // Hover
    static final int LINIE = 0xFF3B3B47;     // Trenner, Kontur, Slider-Spur
    static final int TEXT = 0xFFF4F4F7;      // 16,2:1 auf FLAECHE
    static final int LEISE = 0xFFA9A9B5;     // 7,7:1 auf FLAECHE, 5,9:1 auf ERHOBEN
    static final int RADIUS = 4;
    private static final int PINK = 0xE0679F;   // wenn die HUD-Farbe grau/weiß ist: Akzent der Client-Menüs

    /** HUD-Farbe des Clients als Akzent; ohne Farbe (weiß, grau, Verlauf) das Pink der Client-Menüs. */
    static int akzent() {
        int rgb = HudColor.rgb;
        return 0xFF000000 | (HudColor.chroma || HudColor.toHsv(rgb)[1] < 0.25f ? PINK : rgb);
    }

    /** Schrift auf dem Akzent: die mit dem höheren Kontrast (WCAG) – auf dem Pink dunkel, 5,6:1 statt 2,9:1 mit Weiß. */
    static int aufAkzent() {
        int a = akzent();
        return kontrast(TEXT, a) >= kontrast(RAHMEN, a) ? TEXT : RAHMEN;
    }

    /** Kontrast nach WCAG zwischen zwei Farben (1 … 21). */
    static double kontrast(int a, int b) {
        double x = hell(a), y = hell(b);
        return (Math.max(x, y) + 0.05) / (Math.min(x, y) + 0.05);
    }

    private static double hell(int c) {
        return 0.2126 * lin(c >> 16) + 0.7152 * lin(c >> 8) + 0.0722 * lin(c);
    }

    private static double lin(int kanal) {
        double v = (kanal & 0xFF) / 255.0;
        return v <= 0.04045 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static Component ui(String text) {
        return Draw.ui(text);
    }

    static int breite(String text) {
        return Minecraft.getInstance().font.width(Draw.ui(text));
    }

    /** Text in der Menüschrift ohne Schatten; liefert die Breite. */
    static int text(GuiGraphicsExtractor g, String text, int x, int y, int argb) {
        var c = Draw.ui(text);
        g.text(Minecraft.getInstance().font, c, x, y, argb, false);
        return Minecraft.getInstance().font.width(c);
    }

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        Draw.roundRect(g, x, y, w, h, RADIUS, FLAECHE);
    }

    /** Kleines Textschild (Name eines Wegpunkts, Tooltip), mittig über x. */
    static void schild(GuiGraphicsExtractor g, String text, int mitte, int y) {
        int w = breite(text);
        Draw.roundRect(g, mitte - w / 2 - 3, y, w + 6, 11, 3, FLAECHE);
        text(g, text, mitte - w / 2, y + 2, TEXT);
    }
}
