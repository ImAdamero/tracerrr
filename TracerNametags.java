package pl.tracer.nametags;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.Camera;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.util.InputUtil;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.glfw.GLFW;

import it.unimi.dsi.fastutil.objects.Object2IntMap;

import java.util.ArrayList;
import java.util.List;

public class TracerNametags implements ClientModInitializer {

    /** Włącznik: domyślnie klawisz R (zmienisz w Ustawienia -> Sterowanie). */
    private static KeyBinding toggleKey;
    private static boolean enabled = false;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.tracernametags.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.tracernametags"));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (toggleKey.wasPressed()) {
                enabled = !enabled;
                if (client.player != null) {
                    client.player.sendMessage(
                            Text.literal("Tracery: " + (enabled ? "WŁĄCZONE" : "WYŁĄCZONE")), true);
                }
            }
        });

        HudRenderCallback.EVENT.register(TracerNametags::render);
    }

    private static void render(DrawContext g, RenderTickCounter tickCounter) {
        if (!enabled) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.player == null || mc.options.hudHidden) return;

        float tickDelta = tickCounter.getTickDelta(true);
        Camera camera = mc.gameRenderer.getCamera();
        if (camera == null) return;

        int sw = g.getScaledWindowWidth();
        int sh = g.getScaledWindowHeight();
        float aspect = (float) sw / (float) sh;
        double fovRad = Math.toRadians(mc.options.getFov().getValue());
        float f = (float) (1.0 / Math.tan(fovRad / 2.0));

        Quaternionf inv = new Quaternionf(camera.getRotation()).conjugate();
        Vec3d camPos = camera.getPos();

        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;

            Vec3d base = p.getLerpedPos(tickDelta);
            float[] body = project(base.add(0, p.getHeight() * 0.5, 0), camPos, inv, f, aspect, sw, sh);
            float[] head = project(base.add(0, p.getHeight() + 0.4, 0), camPos, inv, f, aspect, sw, sh);
            if (body == null || head == null) continue; // za plecami

            // Linia tracera od dołu ekranu do ciała gracza
            drawLine(g, sw / 2f, sh, body[0], body[1], 0xFF00FFFF);

            drawTag(g, mc, p, Math.round(head[0]), Math.round(head[1]));
        }
    }

    /** Rzut punktu świata na współrzędne ekranu (GUI). Zwraca null, gdy punkt jest za kamerą. */
    private static float[] project(Vec3d world, Vec3d camPos, Quaternionf inv,
                                   float f, float aspect, int sw, int sh) {
        Vector3f v = new Vector3f(
                (float) (world.x - camPos.x),
                (float) (world.y - camPos.y),
                (float) (world.z - camPos.z));
        inv.transform(v);
        if (v.z >= -0.05f) return null; // kamera patrzy w -Z
        float ndcX = (v.x / -v.z) * f / aspect;
        float ndcY = (v.y / -v.z) * f;
        return new float[]{(ndcX * 0.5f + 0.5f) * sw, (0.5f - ndcY * 0.5f) * sh};
    }

    private static void drawLine(DrawContext g, float x1, float y1, float x2, float y2, int color) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        int steps = (int) Math.min(4000, Math.max(Math.abs(dx), Math.abs(dy)));
        if (steps < 1) return;
        for (int i = 0; i <= steps; i++) {
            float t = (float) i / steps;
            int x = Math.round(x1 + dx * t);
            int y = Math.round(y1 + dy * t);
            g.fill(x, y, x + 2, y + 2, color);
        }
    }

    private static void drawTag(DrawContext g, MinecraftClient mc, AbstractClientPlayerEntity p, int cx, int bottom) {
        ItemStack[] items = {
                p.getMainHandStack(),
                p.getEquippedStack(EquipmentSlot.HEAD),
                p.getEquippedStack(EquipmentSlot.CHEST),
                p.getEquippedStack(EquipmentSlot.LEGS),
                p.getEquippedStack(EquipmentSlot.FEET),
                p.getOffHandStack()
        };

        final int slot = 20;
        final float scale = 0.6f;
        final int lineH = 6;

        int maxLines = 0;
        for (ItemStack s : items) {
            if (!s.isEmpty()) maxLines = Math.max(maxLines, enchantLines(s).size());
        }

        int startX = cx - (items.length * slot) / 2;
        int itemsY = bottom - 4 - maxLines * lineH - 18;

        // nick + serca
        float hp = p.getHealth() + p.getAbsorptionAmount();
        String nick = p.getName().getString();
        String hearts = String.format("❤ %.1f", hp / 2f);
        int hpColor = hp <= 6 ? 0xFFFF5555 : hp <= 14 ? 0xFFFFAA00 : 0xFF55FF55;
        int nickW = mc.textRenderer.getWidth(nick);
        int heartsW = mc.textRenderer.getWidth(hearts);
        int totalW = nickW + 6 + heartsW;
        int tx = cx - totalW / 2;
        int ty = itemsY - 12;
        g.fill(tx - 2, ty - 2, tx + totalW + 2, ty + 10, 0x80000000);
        g.drawText(mc.textRenderer, nick, tx, ty, 0xFFFFFFFF, true);
        g.drawText(mc.textRenderer, hearts, tx + nickW + 6, ty, hpColor, true);

        // itemy + enchanty
        for (int i = 0; i < items.length; i++) {
            ItemStack s = items[i];
            if (s.isEmpty()) continue;
            int ix = startX + i * slot;
            g.drawItem(s, ix, itemsY);
            g.drawStackOverlay(mc.textRenderer, s, ix, itemsY);

            List<String> lines = enchantLines(s);
            for (int l = 0; l < lines.size(); l++) {
                String line = lines.get(l);
                int lw = mc.textRenderer.getWidth(line);
                g.getMatrices().push();
                g.getMatrices().translate(ix + 8, itemsY + 18 + l * lineH, 0);
                g.getMatrices().scale(scale, scale, 1f);
                g.drawText(mc.textRenderer, line, -lw / 2, 0, 0xFFAAAAFF, true);
                g.getMatrices().pop();
            }
        }
    }

    /** Np. "Prot IV", "Unbr III", "Mend I". */
    private static List<String> enchantLines(ItemStack stack) {
        List<String> out = new ArrayList<>();
        ItemEnchantmentsComponent ench = stack.getEnchantments();
        for (Object2IntMap.Entry<RegistryEntry<Enchantment>> e : ench.getEnchantmentEntries()) {
            String name = Enchantment.getName(e.getKey(), e.getIntValue()).getString();
            String[] parts = name.split(" ");
            String base = parts[0];
            if (base.length() > 4) base = base.substring(0, 4);
            String lvl = parts.length > 1 ? parts[parts.length - 1] : "";
            out.add((base + " " + lvl).trim());
        }
        return out;
    }
}
