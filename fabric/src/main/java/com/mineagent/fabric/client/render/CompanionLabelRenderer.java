package com.mineagent.fabric.client.render;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.GuiSpriteManager;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Renders a compact floating status label above AI companions.
 *
 * <p><b>Design</b>: Draws the companion name + <b>vanilla HUD heart and food
 * icons</b> above the companion's head. The hearts and food icons use the
 * exact same texture sprites as the player's own HUD
 * ({@code hud/heart/container}, {@code hud/heart/full}, {@code hud/food/full},
 * etc.), so the appearance is identical to the player's status bar — same
 * 9x9 icons, same colors, same half/full/container states.
 *
 * <p><b>Sprite access</b>: Vanilla HUD heart/food sprites live in the GUI
 * texture atlas, accessed via {@link GuiSpriteManager#getSprite(ResourceLocation)}.
 * The {@code Minecraft.guiAtlasManager} field is private final, so we use
 * reflection (cached on first successful lookup) to retrieve the manager.
 * This avoids requiring an access widener entry while still supporting
 * multiple mappings versions (dev uses Mojang names, production uses
 * Fabric intermediary names).
 *
 * <p><b>Layout (matches vanilla HUD style)</b>:
 * <ul>
 *   <li>Line 1: Companion name (cyan, centered)</li>
 *   <li>Line 2: 10 hearts (vanilla sprites, 9x9 each, 1px spacing)</li>
 *   <li>Line 3: 10 food icons (vanilla sprites, 9x9 each, 1px spacing)</li>
 *   <li>Line 4: HP/food numeric values (small text)</li>
 * </ul>
 *
 * <p><b>Visibility rules</b>:
 * <ul>
 *   <li>Max render distance: 12 blocks (like vanilla name tags)</li>
 *   <li>Auto-show within 4 blocks (no aiming required)</li>
 *   <li>Beyond 4 blocks, only shows when player aims crosshair at companion</li>
 *   <li>Raycast blocks labels occluded by walls</li>
 *   <li>Fade alpha based on distance and aim angle</li>
 * </ul>
 *
 * <p>Toggle visibility with the N key.
 *
 * <p><b>Heart rendering logic</b>: Each heart represents 2 HP. A half heart
 * represents 1 HP. With max HP = 20 (typical), the row shows 10 hearts. If
 * HP is 15, the row shows 7 full hearts, 1 half heart, 2 container (empty)
 * hearts — exactly like the player's own HUD.
 *
 * <p><b>Food rendering logic</b>: Each food icon represents 2 food points.
 * A half icon represents 1 point. With max food = 20, the row shows 10 icons.
 */
public final class CompanionLabelRenderer {

    private static boolean enabled = true;

    /** Max distance (in blocks) at which the label appears. */
    private static final double MAX_RENDER_DISTANCE = 12.0;

    /** Within this distance the label shows even without aiming at the companion. */
    private static final double AUTO_SHOW_DISTANCE = 4.0;

    /** Vertical offset above the companion's feet (in blocks). */
    private static final double LABEL_HEIGHT_OFFSET = 2.4;

    /** Half-angle (degrees) of the view cone for "aimed at" detection. */
    private static final double AIM_CONE_HALF_ANGLE = 15.0;

    // ── Icon dimensions (vanilla heart/food icons are 9x9 pixels) ──
    private static final int ICON_SIZE = 9;
    private static final int ICON_SPACING = 1;
    private static final int ICONS_PER_ROW = 10;
    /** Total width of one row of 10 icons (10*9 + 9*1 = 99). */
    private static final int ICONS_ROW_WIDTH =
            ICONS_PER_ROW * ICON_SIZE + (ICONS_PER_ROW - 1) * ICON_SPACING;

    // ── Layout constants ──
    private static final int PADDING = 3;
    private static final int ROW_SPACING = 2;

    // ── Colors (ARGB) ──
    private static final int PANEL_BG = 0xB0000000;       // 70% alpha black
    // Include an opaque alpha byte. applyAlpha() scales the existing alpha;
    // 0x00B0FFFF therefore made every companion name fully transparent.
    private static final int NAME_COLOR = 0xFFB0FFFF;      // cyan
    private static final int HP_TEXT_COLOR = 0xFFFF5555;   // red text
    private static final int FOOD_TEXT_COLOR = 0xFFC19A6B; // tan text
    private static final int LOW_HP_COLOR = 0xFFFFFF44;   // yellow warning

    // ── Vanilla HUD sprite paths (1.21.1) ──
    // These match the sprites used by the player's own HUD, ensuring the
    // appearance is identical to the player's health/food bars.
    // Texture paths: assets/minecraft/textures/gui/sprites/hud/heart/*.png
    //                 assets/minecraft/textures/gui/sprites/hud/food_*.png
    private static final ResourceLocation HEART_CONTAINER =
            ResourceLocation.withDefaultNamespace("hud/heart/container");
    private static final ResourceLocation HEART_FULL =
            ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF =
            ResourceLocation.withDefaultNamespace("hud/heart/half");
    private static final ResourceLocation FOOD_EMPTY =
            ResourceLocation.withDefaultNamespace("hud/food_empty");
    private static final ResourceLocation FOOD_FULL =
            ResourceLocation.withDefaultNamespace("hud/food_full");
    private static final ResourceLocation FOOD_HALF =
            ResourceLocation.withDefaultNamespace("hud/food_half");

    // ── Cached GuiSpriteManager (loaded once via reflection) ──
    private static GuiSpriteManager cachedSpriteManager = null;
    private static boolean spriteManagerLookupFailed = false;

    private CompanionLabelRenderer() {}

    public static boolean toggleEnabled() {
        enabled = !enabled;
        return enabled;
    }

    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * Get the vanilla {@link GuiSpriteManager} via reflection.
     *
     * <p>In Mojang official mappings the field is {@code guiAtlasManager}
     * (private final in {@link Minecraft}). We try multiple candidate names
     * for safety across mappings versions: dev environment uses Mojang names,
     * production environment uses Fabric intermediary names.
     *
     * <p>The lookup result is cached: the first successful access stores the
     * manager in {@link #cachedSpriteManager}; the first total failure sets
     * {@link #spriteManagerLookupFailed} to avoid repeated reflection overhead.
     */
    private static GuiSpriteManager getGuiSpriteManager(Minecraft mc) {
        if (cachedSpriteManager != null) return cachedSpriteManager;
        if (spriteManagerLookupFailed) return null;

        // Try multiple field names for cross-mapping compatibility
        // - "guiAtlasManager": Mojang official (used in dev environment)
        // - "guiSprites":       Yarn named mapping
        // - "field_45293":      Yarn intermediary (used in production)
        for (String fieldName : new String[]{
                "guiAtlasManager",
                "guiSprites",
                "field_45293"
        }) {
            try {
                Field f = Minecraft.class.getDeclaredField(fieldName);
                f.setAccessible(true);
                Object obj = f.get(mc);
                if (obj instanceof GuiSpriteManager) {
                    cachedSpriteManager = (GuiSpriteManager) obj;
                    return cachedSpriteManager;
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {
                // try next name
            }
        }
        spriteManagerLookupFailed = true;
        return null;
    }

    /**
     * Get a vanilla {@link TextureAtlasSprite} by its resource location.
     * Returns null if the sprite cannot be found (e.g. resource pack
     * overrides the GUI atlas, or sprite manager unavailable).
     */
    private static TextureAtlasSprite getSprite(Minecraft mc, ResourceLocation loc) {
        GuiSpriteManager mgr = getGuiSpriteManager(mc);
        if (mgr == null) return null;
        try {
            return mgr.getSprite(loc);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Render a single HUD icon (heart or food) at the given position.
     *
     * <p>Uses {@code GuiGraphics.blit(int x, int y, int z, int width, int height,
     * TextureAtlasSprite sprite, float r, float g, float b, float a)} to
     * render the sprite with an alpha tint. The RGB tint is white (1,1,1)
     * so the sprite's original colors are preserved.
     *
     * <p>Falls back to a colored rectangle if the sprite cannot be resolved
     * (should not happen in normal gameplay, but keeps the UI from breaking
     * if a resource pack overrides the GUI atlas or the sprite manager fails
     * to load). The fallback color is red for hearts, tan for food icons.
     */
    private static void renderIcon(GuiGraphics g, Minecraft mc,
                                    ResourceLocation spriteLoc,
                                    int x, int y, float alpha) {
        TextureAtlasSprite sprite = getSprite(mc, spriteLoc);
        if (sprite == null) {
            // Fallback: draw a simple colored square
            int color = spriteLoc.getPath().contains("heart")
                    ? applyAlpha(0xFFE53935, alpha)
                    : applyAlpha(0xFFC19A6B, alpha);
            g.fill(x, y, x + ICON_SIZE, y + ICON_SIZE, color);
            return;
        }
        // blit(int x, int y, int z, int w, int h, TextureAtlasSprite, r, g, b, a)
        // Apply alpha via the color tint (RGB=1 to keep original colors)
        g.blit(x, y, 0, ICON_SIZE, ICON_SIZE, sprite, 1.0f, 1.0f, 1.0f, alpha);
    }

    /**
     * Render a row of hearts matching the vanilla player HUD style.
     *
     * <p>Each heart represents 2 HP. A half heart represents 1 HP.
     * Layout: up to 10 hearts per row, 9x9 px each, 1px spacing — same as
     * the player's own HUD.
     *
     * <p>Example: HP=15, maxHp=20 → 7 full hearts, 1 half heart, 2 containers.
     *
     * @param startX   left x coordinate of the first heart
     * @param y        top y coordinate of the row
     * @param hp       current health
     * @param maxHp    max health
     * @param alpha    alpha (0-1) to apply to all icons
     */
    private static void renderHeartsRow(GuiGraphics g, Minecraft mc,
                                         int startX, int y,
                                         float hp, float maxHp, float alpha) {
        int totalHearts = Math.min(ICONS_PER_ROW, (int) Math.ceil(maxHp / 2.0f));
        int fullHearts = (int) Math.floor(hp / 2.0f);
        boolean hasHalf = (hp - fullHearts * 2) >= 0.5f;

        for (int i = 0; i < totalHearts; i++) {
            ResourceLocation sprite;
            if (i < fullHearts) {
                sprite = HEART_FULL;
            } else if (i == fullHearts && hasHalf) {
                sprite = HEART_HALF;
            } else {
                sprite = HEART_CONTAINER;
            }
            int iconX = startX + i * (ICON_SIZE + ICON_SPACING);
            renderIcon(g, mc, sprite, iconX, y, alpha);
        }
    }

    /**
     * Render a row of food icons matching the vanilla player HUD style.
     *
     * <p>Each icon represents 2 food points. A half icon represents 1 point.
     * Layout: 10 icons per row, 9x9 px each, 1px spacing — same as vanilla HUD.
     *
     * <p>Example: food=15 → 7 full icons, 1 half icon, 2 empty icons.
     */
    private static void renderFoodRow(GuiGraphics g, Minecraft mc,
                                        int startX, int y,
                                        int food, float alpha) {
        int fullIcons = food / 2;
        boolean hasHalf = (food % 2) == 1;

        for (int i = 0; i < ICONS_PER_ROW; i++) {
            ResourceLocation sprite;
            if (i < fullIcons) {
                sprite = FOOD_FULL;
            } else if (i == fullIcons && hasHalf) {
                sprite = FOOD_HALF;
            } else {
                sprite = FOOD_EMPTY;
            }
            int iconX = startX + i * (ICON_SIZE + ICON_SPACING);
            renderIcon(g, mc, sprite, iconX, y, alpha);
        }
    }

    /**
     * Render floating status label above each companion.
     * Called from HudRenderCallback.
     */
    public static void renderHud(GuiGraphics guiGraphics,
                                   net.minecraft.client.DeltaTracker deltaTracker) {
        if (!enabled) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.cameraEntity == null) return;

        // Use IDs explicitly announced by the server. Rendering every
        // non-local Player leaked MineAgent labels onto humans in multiplayer.
        List<Player> companions = new ArrayList<>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (entity instanceof Player p && entity != mc.player
                    && com.mineagent.fabric.client.MineAgentClient
                            .isKnownCompanion(p.getUUID())) {
                companions.add(p);
            }
        }
        if (companions.isEmpty()) return;

        Window window = mc.getWindow();
        int screenWidth = window.getGuiScaledWidth();
        int screenHeight = window.getGuiScaledHeight();
        float partialTick = deltaTracker.getGameTimeDeltaPartialTick(false);

        for (Player companion : companions) {
            renderLabelForCompanion(guiGraphics, mc, companion,
                    screenWidth, screenHeight, partialTick);
        }
    }

    private static void renderLabelForCompanion(GuiGraphics guiGraphics,
                                                  Minecraft mc,
                                                  Player companion,
                                                  int screenWidth,
                                                  int screenHeight,
                                                  float partialTick) {
        // ── Distance check ──
        double distSq = companion.distanceToSqr(mc.player);
        if (distSq > MAX_RENDER_DISTANCE * MAX_RENDER_DISTANCE) return;
        double dist = Math.sqrt(distSq);

        // ── Aim / closeness check ──
        Vec3 playerEye = mc.player.getEyePosition(partialTick);
        Vec3 companionHead = companion.position().add(0, 1.0, 0);
        Vec3 toCompanion = companionHead.subtract(playerEye);
        Vec3 lookDir = mc.player.getLookAngle();

        double dot = lookDir.dot(toCompanion.normalize());
        double angle = Math.toDegrees(Math.acos(Math.max(-1.0, Math.min(1.0, dot))));

        boolean aimedAt = angle <= AIM_CONE_HALF_ANGLE;
        boolean veryClose = dist <= AUTO_SHOW_DISTANCE;
        if (!aimedAt && !veryClose) return;

        // ── Line-of-sight (don't show through walls) ──
        if (!veryClose) {
            HitResult hit = mc.level.clip(new net.minecraft.world.level.ClipContext(
                    playerEye, companionHead,
                    net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE,
                    mc.player));
            if (hit.getType() == HitResult.Type.BLOCK) return;
        }

        // ── Alpha (fade based on distance and aim) ──
        float alpha = 1.0f;
        if (dist > 10.0) {
            alpha = (float) Math.max(0, 1.0 - (dist - 10.0) / 2.0);
        }
        if (!veryClose && aimedAt) {
            float aimFactor = (float) (1.0 - (angle / AIM_CONE_HALF_ANGLE) * 0.4);
            alpha *= aimFactor;
        }

        // ── Project 3D head position to 2D screen coordinates ──
        Vec3 labelPos3d = companion.position().add(0, LABEL_HEIGHT_OFFSET, 0);
        Vector3f screenPos = projectToScreen(mc, labelPos3d, partialTick);
        if (screenPos == null) return; // behind camera

        int screenX = (int) screenPos.x();
        int screenY = (int) screenPos.y();

        // Skip if off-screen
        if (screenX < -ICONS_ROW_WIDTH || screenX > screenWidth + ICONS_ROW_WIDTH
                || screenY < -50 || screenY > screenHeight + 50) return;

        // ── Draw the status panel ──
        String name = companion.getCustomName() != null
                ? companion.getCustomName().getString()
                : companion.getName().getString();

        float hp = companion.getHealth();
        float maxHp = companion.getMaxHealth();
        int food = companion.getFoodData().getFoodLevel();

        // ── Layout calculation ──
        // Row 1: name (centered)
        // Row 2: hearts row (vanilla HUD sprites)
        // Row 3: food row (vanilla HUD sprites)
        // Row 4: HP/food numeric text
        int nameHeight = mc.font.lineHeight;
        int iconsRowHeight = ICON_SIZE; // 9px
        int textRowHeight = mc.font.lineHeight;
        int totalHeight = nameHeight + ROW_SPACING
                + iconsRowHeight + ROW_SPACING
                + iconsRowHeight + ROW_SPACING
                + textRowHeight
                + PADDING * 2;
        int panelWidth = ICONS_ROW_WIDTH + PADDING * 2; // 99 + 6 = 105
        int panelX = screenX - panelWidth / 2;
        int panelY = screenY - totalHeight / 2;

        // ── Draw panel background ──
        guiGraphics.fill(panelX, panelY,
                panelX + panelWidth, panelY + totalHeight,
                applyAlpha(PANEL_BG, alpha));

        // ── Draw companion name (centered) ──
        int nameWidth = mc.font.width(name);
        int nameX = panelX + (panelWidth - nameWidth) / 2;
        int nameY = panelY + PADDING;
        guiGraphics.drawString(mc.font, name, nameX, nameY,
                applyAlpha(NAME_COLOR, alpha), false);

        // ── Draw health bar (vanilla hearts, identical to player HUD) ──
        int heartsY = nameY + nameHeight + ROW_SPACING;
        int heartsX = panelX + PADDING;
        renderHeartsRow(guiGraphics, mc, heartsX, heartsY, hp, maxHp, alpha);

        // ── Draw food bar (vanilla food icons, identical to player HUD) ──
        int foodY = heartsY + iconsRowHeight + ROW_SPACING;
        renderFoodRow(guiGraphics, mc, heartsX, foodY, food, alpha);

        // ── Draw HP and food numeric values (small text below icons) ──
        String hpText = String.format("%.0f/%.0f", Math.ceil(hp), maxHp);
        String foodTextStr = String.format("%d/20", food);
        int hpTextColor = hp < maxHp * 0.3f
                ? applyAlpha(LOW_HP_COLOR, alpha)
                : applyAlpha(HP_TEXT_COLOR, alpha);
        int foodTextColor = food < 6
                ? applyAlpha(LOW_HP_COLOR, alpha)
                : applyAlpha(FOOD_TEXT_COLOR, alpha);

        int textY = foodY + iconsRowHeight + ROW_SPACING;
        guiGraphics.drawString(mc.font, hpText,
                panelX + PADDING, textY, hpTextColor, false);
        int foodTextWidth = mc.font.width(foodTextStr);
        guiGraphics.drawString(mc.font, foodTextStr,
                panelX + panelWidth - PADDING - foodTextWidth, textY,
                foodTextColor, false);
    }

    /**
     * Apply alpha (0-1) to an ARGB color, scaling the existing alpha channel.
     */
    private static int applyAlpha(int argb, float alpha) {
        int a = (int) (((argb >> 24) & 0xFF) * alpha) & 0xFF;
        return (a << 24) | (argb & 0x00FFFFFF);
    }

    /**
     * Project a 3D world position to 2D screen coordinates.
     * Returns null if the point is behind the camera.
     *
     * <p><b>Why we build our own perspective matrix instead of using
     * {@code RenderSystem.getProjectionMatrix()}:</b> This method is called
     * from {@code HudRenderCallback}, which runs AFTER the world render pass
     * has finished. By the time HUD rendering starts, Minecraft has already
     * swapped the projection matrix from the world's perspective matrix to
     * an orthographic matrix for HUD rendering. If we used
     * {@code RenderSystem.getProjectionMatrix()} here, we would get the
     * orthographic HUD matrix, and our 3D-to-2D projection would produce
     * garbage coordinates (typically landing in the top-left corner of the
     * screen or off-screen entirely, regardless of where the companion
     * actually is in the world).
     *
     * <p>Instead, we reconstruct the perspective projection from the player's
     * FOV setting ({@code mc.options.fov().get()}) and the window aspect
     * ratio. This produces the same projection that was used during the
     * world render pass (modulo view-bobbing, which is acceptable for a
     * floating status label).
     */
    private static Vector3f projectToScreen(Minecraft mc, Vec3 worldPos, float partialTick) {
        var cam = mc.gameRenderer.getMainCamera();
        Vec3 cameraPos = cam.getPosition();
        double relX = worldPos.x - cameraPos.x;
        double relY = worldPos.y - cameraPos.y;
        double relZ = worldPos.z - cameraPos.z;

        // Apply camera rotation: rotate the relative point by the camera's
        // quaternion conjugate (inverse rotation) to get view-space coords.
        org.joml.Quaternionf rotation = new org.joml.Quaternionf(cam.rotation());
        rotation.conjugate();

        Vector3f point = new Vector3f((float) relX, (float) relY, (float) relZ);
        rotation.transform(point);

        // In Minecraft's camera space, -Z is forward (into the screen).
        // If point.z >= 0, the point is behind the camera.
        if (point.z() >= 0) return null;

        // Build our own perspective projection matrix.
        // mc.options.fov().get() returns the vertical FOV in degrees (30-110).
        // Aspect ratio comes from the actual window dimensions, not the
        // GUI-scaled dimensions, because the projection operates on raw pixels.
        float fovY = (float) Math.toRadians(mc.options.fov().get());
        Window window = mc.getWindow();
        float aspect = (float) window.getWidth() / (float) window.getHeight();
        float near = 0.05f;
        float far = 1000.0f;

        org.joml.Matrix4f proj = new org.joml.Matrix4f();
        proj.perspective(fovY, aspect, near, far);

        Vector3f result = new Vector3f();
        proj.transformProject(point, result);

        // Convert NDC [-1, 1] to screen coordinates (GUI-scaled space)
        int screenWidth = window.getGuiScaledWidth();
        int screenHeight = window.getGuiScaledHeight();

        float screenX = (result.x() + 1.0f) * 0.5f * screenWidth;
        float screenY = (1.0f - (result.y() + 1.0f) * 0.5f) * screenHeight;

        return new Vector3f(screenX, screenY, result.z());
    }
}
