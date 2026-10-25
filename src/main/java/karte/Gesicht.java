package karte;

import client.api.Draw;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.LivingEntityRenderState;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;

// Gesicht einer Kreatur wie bei Xaero's Minimap: die Vorderseite des Kopf-Würfels aus ihrer eigenen Textur.
// Kopf = Modellteil "head" (auch verschachtelt), davon der größte Würfel, davon die Fläche nach Norden (= vorn).
// Funktioniert so für alle Kreaturen und Varianten ohne eigene Bilder; ohne Kopf bleibt es beim Punkt.
final class Gesicht {
    private static final int RASTER = 4096;   // Texturkoordinaten sind 0…1 – auf ein feines Raster umrechnen

    private record Flaeche(Identifier tex, int u, int v, int uw, int vh, float seite) {}   // seite = Breite / Höhe

    private static final Map<Object, Flaeche> CACHE = new HashMap<>();
    // je Entity gemerkt, damit nicht jedes Bild ein Render-Zustand entsteht; ponytail: Variantenwechsel derselben Kreatur erst nach Leeren
    private static final Map<Integer, Flaeche> JE_ENTITY = new HashMap<>();
    private static final Flaeche KEINE = new Flaeche(null, 0, 0, 0, 0, 1);
    private static Field cubes;

    /** Beim Verlassen einer Welt: Entity-IDs beginnen in jeder Welt neu – sonst bekäme Zombie #57 das Gesicht von Kuh #57. */
    static void leeren() {
        JE_ENTITY.clear();
    }

    /** Zeichnet das Gesicht mittig auf (x, y), längste Seite = s. false = kein Gesicht gefunden. */
    static boolean zeichne(GuiGraphicsExtractor g, Entity e, int x, int y, int s) {
        if (JE_ENTITY.size() > 4096) JE_ENTITY.clear();
        Flaeche f = JE_ENTITY.computeIfAbsent(e.getId(), id -> flaeche(e));
        if (f == KEINE) return false;
        int w = f.seite >= 1 ? s : Math.max(2, Math.round(s * f.seite));
        int h = f.seite >= 1 ? Math.max(2, Math.round(s / f.seite)) : s;
        Ansicht.rechteck(g, x - w / 2 - 1, y - h / 2 - 1, w + 2, h + 2, 0xFF000000);
        Draw.image(g, f.tex, x - w / 2, y - h / 2, w, h, f.u, f.v, f.uw, f.vh, RASTER, RASTER, 0xFFFFFFFF);
        return true;
    }

    /** Für den Selbsttest: Ausschnitt in 1/64 der Textur ("u v breite höhe"), null = kein Gesicht. */
    static String ausschnitt64(Entity e) {
        Flaeche f = flaeche(e);
        int k = RASTER / 64;
        return f == KEINE ? null : f.u / k + " " + f.v / k + " " + f.uw / k + " " + f.vh / k;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Flaeche flaeche(Entity e) {
        try {
            var r = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(e);
            if (!(r instanceof LivingEntityRenderer lr)) return KEINE;
            LivingEntityRenderer roh = lr;   // ohne Generics: Renderer und Zustand passen zur Laufzeit zusammen
            Identifier tex = roh.getTextureLocation((LivingEntityRenderState) roh.createRenderState(e, 0));
            // Schlüssel: Art + Textur (Varianten wie Kuh-Arten oder Dorfbewohner-Berufe haben eigene Texturen)
            return CACHE.computeIfAbsent(List.of(e.getType(), String.valueOf(tex)), k -> suche(lr.getModel().root(), tex));
        } catch (RuntimeException ex) {
            System.err.println("[minimap] Kein Gesicht für " + e.getType().toShortString() + ": " + ex);
            return KEINE;
        }
    }

    private static Flaeche suche(ModelPart root, Identifier tex) {
        ModelPart kopf = null;
        for (ModelPart p : root.getAllParts())
            if (p.hasChild("head")) {
                kopf = p.getChild("head");
                break;
            }
        if (kopf == null) return KEINE;
        ModelPart.Cube best = null;
        for (ModelPart p : kopf.getAllParts())     // Kopf selbst und Unterteile (z. B. Wolf: head → real_head)
            for (ModelPart.Cube c : wuerfel(p))
                if (best == null || volumen(c) > volumen(best)) best = c;
        if (best == null) return KEINE;
        for (var poly : best.polygons) {
            if (poly.normal().z() > -0.5f) continue;   // nur die Fläche nach Norden = Gesicht
            float u0 = 1, v0 = 1, u1 = 0, v1 = 0;
            for (var vert : poly.vertices()) {
                u0 = Math.min(u0, vert.u());
                v0 = Math.min(v0, vert.v());
                u1 = Math.max(u1, vert.u());
                v1 = Math.max(v1, vert.v());
            }
            float seite = (best.maxX - best.minX) / Math.max(0.01f, best.maxY - best.minY);
            return new Flaeche(tex, Math.round(u0 * RASTER), Math.round(v0 * RASTER),
                Math.max(1, Math.round((u1 - u0) * RASTER)), Math.max(1, Math.round((v1 - v0) * RASTER)), seite);
        }
        return KEINE;
    }

    private static float volumen(ModelPart.Cube c) {
        return (c.maxX - c.minX) * (c.maxY - c.minY) * (c.maxZ - c.minZ);
    }

    // Die Würfel eines Modellteils sind privat – per Reflection lesen (Addons sind normales Java)
    @SuppressWarnings("unchecked")
    private static List<ModelPart.Cube> wuerfel(ModelPart p) {
        try {
            if (cubes == null) {
                cubes = ModelPart.class.getDeclaredField("cubes");
                cubes.setAccessible(true);
            }
            return (List<ModelPart.Cube>) cubes.get(p);
        } catch (ReflectiveOperationException ex) {
            return List.of();
        }
    }
}
