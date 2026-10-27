package karte;

import client.api.AddonConfig;
import client.api.Draw;
import com.mojang.blaze3d.platform.NativeImage;
import java.util.ArrayList;
import java.util.Comparator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;

// Welt ↔ Bildschirm für Minimap und Weltkarte: Mitte (cx, cz), Maßstab in GUI-Pixeln je Block, optional gedreht.
// Dazu das gemeinsame Zeichnen von Wegpunkten, Entity-Radar und Spielerpfeil.
final class Ansicht {
    double cx, cz, scale = 1;
    float ox, oy;                       // Bildschirmmitte
    float schrift = 1;                  // Textgröße – in echten Bildschirmpixeln (Minimap) = GUI-Skalierung, damit Namen lesbar bleiben

    /** Scharfes Rechteck. Draw.box ist ein abgerundetes Sprite – bei 1–4 Pixeln bleibt davon nichts sichtbar. */
    static void rechteck(GuiGraphicsExtractor g, int x, int y, int w, int h, int argb) {
        g.fill(x, y, x + w, y + h, argb);
    }
    double fx = 0, fz = -1, rx = 1, rz = 0;  // „oben“ (vorwärts) und „rechts“ als Weltvektoren – Norden oben

    /** Norden oben (yaw = 180) oder Blickrichtung oben. */
    void drehung(float yawGrad) {
        double y = Math.toRadians(yawGrad);
        fx = -Math.sin(y);
        fz = Math.cos(y);
        rx = -Math.cos(y);
        rz = -Math.sin(y);
    }

    float sx(double wx, double wz) {
        return (float) (ox + ((wx - cx) * rx + (wz - cz) * rz) * scale);
    }

    float sy(double wx, double wz) {
        return (float) (oy - ((wx - cx) * fx + (wz - cz) * fz) * scale);
    }

    double wx(double sx, double sy) {
        double a = (sx - ox) / scale, b = (oy - sy) / scale;
        return cx + a * rx + b * fx;
    }

    double wz(double sx, double sy) {
        double a = (sx - ox) / scale, b = (oy - sy) / scale;
        return cz + a * rz + b * fz;
    }

    /** Drehwinkel (Bogenmaß) für etwas, das in Weltrichtung yaw zeigt. */
    float winkel(float yawGrad) {
        double y = Math.toRadians(yawGrad);
        double dx = -Math.sin(y), dz = Math.cos(y);
        return (float) Math.atan2(dx * rx + dz * rz, dx * fx + dz * fz);
    }

    /** Begrenzung der Karte: Kreis (Radius) oder Rechteck (halbe Breite/Höhe). Punkte außerhalb an den Rand schieben. */
    record Rand(boolean kreis, float hw, float hh) {
        boolean innen(float dx, float dy) {
            return kreis ? dx * dx + dy * dy <= hw * hw : Math.abs(dx) <= hw && Math.abs(dy) <= hh;
        }

        float[] klemmen(float dx, float dy) {
            if (innen(dx, dy)) return new float[] {dx, dy};
            float f = kreis ? hw / (float) Math.hypot(dx, dy) : Math.min(hw / Math.abs(dx), hh / Math.abs(dy));
            return new float[] {dx * f, dy * f};
        }
    }

    // ───── Entity-Radar ─────
    enum Art {
        MONSTER(0xFFFF4040), TIER(0xFFE8E8E8), HAUSTIER(0xFF40C8FF), DORF(0xFF50E050), SPIELER(0xFFFFE040);

        final int argb;

        Art(int argb) {
            this.argb = argb;
        }

        String text() {
            return T.t("art." + name().toLowerCase());
        }

        static Art von(Entity e) {
            if (e instanceof Player) return SPIELER;
            if (e instanceof Enemy) return MONSTER;
            if (e instanceof AbstractVillager) return DORF;
            if (e instanceof TamableAnimal t && t.isTame()) return HAUSTIER;
            var c = e.getType().getCategory();
            return c == MobCategory.MISC ? null : TIER;   // Items, Pfeile, Boote … nicht zeigen
        }
    }

    /** Welche Arten die Karten zeigen – ein gemeinsamer Filter für Minimap und Weltkarte (Präfix "fs_"). */
    static final class Filter {
        private final AddonConfig cfg;
        private final String prefix;
        private final boolean[] an = new boolean[Art.values().length];
        boolean wegpunkte, namen, koepfe;
        // Kreaturen, die dieser Filter zeigt – je Spiel-Tick einmal gesucht (Positionen ändern sich nur je Tick)
        private final ArrayList<Entity> kandidaten = new ArrayList<>();
        private long kandidatenTick = Long.MIN_VALUE;

        Filter(AddonConfig cfg, String prefix) {
            this.cfg = cfg;
            this.prefix = prefix;
            for (Art a : Art.values()) an[a.ordinal()] = cfg.bool(prefix + a.name().toLowerCase(), true);
            wegpunkte = cfg.bool(prefix + "wegpunkte", true);
            namen = cfg.bool(prefix + "namen", true);
            koepfe = cfg.bool(prefix + "koepfe", true);
        }

        void setzeKoepfe(boolean v) {
            cfg.set(prefix + "koepfe", koepfe = v);
        }

        boolean zeigt(Art a) {
            return an[a.ordinal()];
        }

        void setze(Art a, boolean v) {
            an[a.ordinal()] = v;
            cfg.set(prefix + a.name().toLowerCase(), v);
        }

        void setzeWegpunkte(boolean v) {
            cfg.set(prefix + "wegpunkte", wegpunkte = v);
        }

        void setzeNamen(boolean v) {
            cfg.set(prefix + "namen", namen = v);
        }
    }

    /** Kreaturen auf der Karte: als Gesicht (Kantenlänge kopf, wie bei Xaero's Minimap) oder als Punkt. */
    void radar(GuiGraphicsExtractor g, Filter f, Rand rand, int kopf) {
        var mc = Minecraft.getInstance();
        if (mc.level == null) return;
        long tick = mc.level.getGameTime();
        if (f.kandidatenTick != tick) {
            f.kandidatenTick = tick;
            f.kandidaten.clear();
            double meinY = mc.player == null ? 0 : mc.player.getY();
            for (Entity e : mc.level.entitiesForRendering()) {
                if (e == mc.player || !e.isAlive() || !inHoehe(e.getY() - meinY)) continue;   // weit über/unter dir: weglassen
                Art a = Art.von(e);
                if (a != null && f.zeigt(a)) f.kandidaten.add(e);
            }
        }
        var sichtbar = new ArrayList<Entity>();
        for (Entity e : f.kandidaten)
            if (rand.innen(sx(e.getX(), e.getZ()) - ox, sy(e.getX(), e.getZ()) - oy)) sichtbar.add(e);
        sichtbar.sort(Comparator.comparingDouble(e -> (e.getX() - cx) * (e.getX() - cx) + (e.getZ() - cz) * (e.getZ() - cz)));
        // von hinten nach vorn: die nächsten liegen oben
        double meinY = mc.player == null ? 0 : mc.player.getY();
        for (int i = sichtbar.size() - 1; i >= 0; i--) {
            Entity e = sichtbar.get(i);
            Art a = Art.von(e);
            int x = Math.round(sx(e.getX(), e.getZ())), y = Math.round(sy(e.getX(), e.getZ()));
            int schleier = schleier(e.getY() - meinY);
            int h = kopf / 2;
            if (f.koepfe) {
                boolean gemalt;
                if (a == Art.SPIELER) {
                    Ansicht.rechteck(g, x - h - 1, y - h - 1, kopf + 2, kopf + 2, 0xFF000000);
                    gemalt = Draw.head(g, e.getName().getString(), x - h, y - h, kopf);
                } else gemalt = Gesicht.zeichne(g, e, x, y, kopf);
                if (gemalt) {
                    if (schleier != 0) Ansicht.rechteck(g, x - h - 1, y - h - 1, kopf + 2, kopf + 2, schleier);
                    continue;
                }
            }
            // Punkt in der Farbe der Art mit dunklem Rand, wie bei Xaero: Minimap (echte Pixel, kopf 12) 3×3, Weltkarte 2×2
            int p = kopf >= 12 ? 3 : 2;
            Ansicht.rechteck(g, x - p / 2 - 1, y - p / 2 - 1, p + 2, p + 2, 0xFF000000);
            Ansicht.rechteck(g, x - p / 2, y - p / 2, p, p, a.argb);
            if (schleier != 0) Ansicht.rechteck(g, x - p / 2 - 1, y - p / 2 - 1, p + 2, p + 2, schleier);
        }
    }

    /** Kreaturen mehr als so viele Blöcke über oder unter dir zeigt das Radar gar nicht (Höhle unter dir, Meeresgrund unter dem Boot). */
    static final int HOEHE_SICHT = 20;

    static boolean inHoehe(double dy) {
        return Math.abs(dy) <= HOEHE_SICHT;
    }

    /** Dunkler Schleier über Kreaturen über oder unter dir (ARGB, 0 = keiner): ab 6 Blöcken zunehmend, bei HOEHE_SICHT am stärksten. */
    static int schleier(double dy) {
        double d = Math.abs(dy) - 6;
        if (d <= 0) return 0;
        return (int) (Math.min(1, d / (HOEHE_SICHT - 6)) * 0xA0) << 24;
    }

    void wegpunkte(GuiGraphicsExtractor g, Filter f, Rand rand) {
        if (!f.wegpunkte) return;
        for (var w : Wegpunkte.sichtbarHier()) {
            double wx = w.pos().getX() + 0.5, wz = w.pos().getZ() + 0.5;
            float dx = sx(wx, wz) - ox, dy = sy(wx, wz) - oy;
            boolean innen = rand.innen(dx, dy);
            float[] p = rand.klemmen(dx, dy);
            int x = Math.round(ox + p[0]), y = Math.round(oy + p[1]);
            if (schrift == 1) Wegpunkte.symbolKasten(g, w, x, y);        // Weltkarte: Quadrat mit Symbol wie in der Welt
            else {                                                        // Minimap (echte Pixel): schlichtes Farbquadrat
                rechteck(g, x - 4, y - 4, 8, 8, 0xFF000000);
                rechteck(g, x - 3, y - 3, 6, 6, w.argb() | 0xFF000000);
            }
            if (innen && f.namen) {
                g.pose().pushMatrix();
                g.pose().translate(x, y + (schrift == 1 ? 8 : 5));   // unter dem (größeren) Symbolquadrat der Weltkarte
                g.pose().scale(schrift, schrift);
                Stil.schild(g, w.name(), 0, 0);
                g.pose().popMatrix();
            }
        }
    }

    // Spielerpfeil: einmal als Textur gemalt, beim Zeichnen gedreht
    private static Identifier pfeil;

    void spieler(GuiGraphicsExtractor g, int size) {
        var p = Minecraft.getInstance().player;
        if (p == null) return;
        if (pfeil == null) pfeil = Draw.texture(Karte.ID, "pfeil", pfeilBild());
        g.pose().pushMatrix();
        g.pose().translate(sx(p.getX(), p.getZ()), sy(p.getX(), p.getZ()));
        g.pose().rotate(winkel(p.getYRot()));
        Draw.image(g, pfeil, -size / 2, -size / 2, size, size, 32, 32);
        g.pose().popMatrix();
    }

    // Pfeil nach oben: weiß mit schwarzem Rand, unten eingekerbt
    private static NativeImage pfeilBild() {
        var img = new NativeImage(32, 32, true);
        for (int y = 0; y < 32; y++)
            for (int x = 0; x < 32; x++) {
                if (inPfeil(x + 0.5, y + 0.5, 0)) img.setPixel(x, y, 0xFFFFFFFF);
                else if (inPfeil(x + 0.5, y + 0.5, 2.5)) img.setPixel(x, y, 0xFF000000);
            }
        return img;
    }

    private static boolean inPfeil(double x, double y, double grow) {
        double dx = Math.abs(x - 16);
        if (y < 3 - grow || y > 28 + grow) return false;
        double half = (y - 3) * 0.48 + grow;          // Flanken
        return dx <= half && y <= 21 + dx * 0.6 + grow; // Kerbe unten in der Mitte
    }

    // Chunk-Gitter (nur ungedreht)
    void gitter(GuiGraphicsExtractor g, int x0, int y0, int w, int h) {
        if (scale < 1) return;
        int a = (int) Math.floor(wx(x0, 0) / 16), b = (int) Math.ceil(wx(x0 + w, 0) / 16);
        for (int c = a; c <= b; c++) {
            int x = Math.round(sx(c * 16, 0));
            Ansicht.rechteck(g, x, y0, 1, h, c % 32 == 0 ? 0x50FFFFFF : 0x28FFFFFF);   // Regionen (512 Blöcke) kräftiger
        }
        a = (int) Math.floor(wz(0, y0) / 16);
        b = (int) Math.ceil(wz(0, y0 + h) / 16);
        for (int c = a; c <= b; c++) {
            int y = Math.round(sy(0, c * 16));
            Ansicht.rechteck(g, x0, y, w, 1, c % 32 == 0 ? 0x50FFFFFF : 0x28FFFFFF);
        }
    }
}
