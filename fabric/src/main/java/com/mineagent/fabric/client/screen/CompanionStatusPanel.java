package com.mineagent.fabric.client.screen;

import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * HUD overlay showing companion status - rendered on the game HUD
 * (not a full screen). Shows health, food, air, current task, and position.
 *
 * <p>The panel is small and unobtrusive, drawn in the top-right corner.
 * Toggle visibility with the {@code H} key binding.
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for styled panel
 * background with dot grid texture, gold-accented borders, and tilted
 * corner decorations. The panel is boundary-clamped to ensure it stays
 * within the visible screen area even on small GUIscales.
 *
 * <p>This is NOT a Screen - it renders directly on the HUD via
 * Fabric's {@code HudRenderCallback}.
 */
public final class CompanionStatusPanel {

    /** Whether the HUD is currently visible. */
    private static boolean visible = true;

    /** Panel width in pixels. */
    private static final int PANEL_WIDTH = 140;

    /** Panel padding from the screen edge. */
    private static final int EDGE_PADDING = 6;

    /** Inner padding inside the panel. */
    private static final int INNER_PAD = 4;

    /** Bar width in pixels. */
    private static final int BAR_WIDTH = 100;

    /** Bar height in pixels. */
    private static final int BAR_HEIGHT = 8;

    /** Line height for text. */
    private static final int LINE_HEIGHT = 11;

    // --- Companion state (updated from server packets) ---

    /** Companion health (0.0 - max). */
    private static float health = 20.0f;

    /** Companion max health. */
    private static float maxHealth = 20.0f;

    /** Companion food level (0 - 20). */
    private static int foodLevel = 20;

    /** Companion air supply (0 - 300). */
    private static int airSupply = 300;

    /** Companion max air supply. */
    private static int maxAirSupply = 300;

    /** Current task description. */
    private static String currentTask = "Idle";

    /** Get the current task string (for label renderer). */
    public static String getCurrentTask() { return currentTask; }

    /** Companion X position. */
    private static double posX;

    /** Companion Y position. */
    private static double posY;

    /** Companion Z position. */
    private static double posZ;

    /** Whether the companion is spawned. */
    private static boolean spawned = false;

    private CompanionStatusPanel() {
        // utility class - no instances
    }

    /**
     * Toggle the HUD visibility.
     *
     * @return the new visibility state
     */
    public static boolean toggleVisible() {
        visible = !visible;
        return visible;
    }

    /**
     * Check if the HUD is currently visible.
     */
    public static boolean isVisible() {
        return visible;
    }

    /**
     * Render the companion status panel on the HUD.
     * Called from the Fabric HudRenderCallback.
     *
     * @param graphics    the GuiGraphics context
     * @param deltaTracker the frame delta tracker
     */
    public static void render(GuiGraphics graphics, DeltaTracker deltaTracker) {
        if (!visible || !spawned) return;

        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;

        int screenWidth = mc.getWindow().getGuiScaledWidth();
        int screenHeight = mc.getWindow().getGuiScaledHeight();

        // Calculate total panel height
        // Each bar consumes a label line plus the bar and its 2px gap. The old
        // formula counted only three lines total, so task/position overflowed
        // the panel background and could overlap following HUD content.
        int panelHeight = INNER_PAD * 2 + LINE_HEIGHT
                + 3 * (LINE_HEIGHT + BAR_HEIGHT + 2)
                + LINE_HEIGHT * 2;

        // Boundary-safe: clamp panel position to visible screen
        int panelX = MineAgentUiComponents.clamp(
                screenWidth - PANEL_WIDTH - EDGE_PADDING,
                MineAgentUiComponents.MARGIN,
                Math.max(MineAgentUiComponents.MARGIN,
                        screenWidth - PANEL_WIDTH - MineAgentUiComponents.MARGIN));
        int panelY = MineAgentUiComponents.clamp(
                EDGE_PADDING,
                MineAgentUiComponents.MARGIN,
                Math.max(MineAgentUiComponents.MARGIN,
                        screenHeight - panelHeight - MineAgentUiComponents.MARGIN));

        // Draw styled panel background with dot grid, border, and corner decorations
        // Using a non-accent (gray) border for HUD panel since it's always visible
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, PANEL_WIDTH, panelHeight,
                null, // no title band for HUD
                false, // gray border (less obtrusive)
                screenWidth, screenHeight
        );

        int textX = panelX + INNER_PAD;
        int currentY = panelY + INNER_PAD;

        // --- Title (with status indicator dot) ---
        MineAgentUiComponents.drawStatusIndicator(
                graphics, textX, currentY + 2,
                "§fCompanion", true);
        currentY += LINE_HEIGHT;

        // --- Health bar ---
        currentY = drawBar(
                graphics, mc, textX, currentY,
                "HP", health, maxHealth,
                0xFF3333, 0x551111
        );

        // --- Food bar ---
        currentY = drawBar(
                graphics, mc, textX, currentY,
                "Food", (float) foodLevel, 20.0f,
                0xFFAA33, 0x553311
        );

        // --- Air bar ---
        currentY = drawBar(
                graphics, mc, textX, currentY,
                "Air", (float) airSupply, (float) maxAirSupply,
                0x33AAFF, 0x112255
        );

        // --- Current task ---
        String taskDisplay = "Task: " + currentTask;
        graphics.drawString(mc.font, taskDisplay, textX, currentY, 0xFFFF55, true);
        currentY += LINE_HEIGHT;

        // --- Position ---
        String posDisplay = String.format("Pos: %.0f, %.0f, %.0f", posX, posY, posZ);
        graphics.drawString(mc.font, posDisplay, textX, currentY, 0xAAAAAA, false);
    }

    /**
     * Draw a labeled bar (health/food/air style).
     *
     * @param graphics   the GuiGraphics context
     * @param mc         the Minecraft instance
     * @param x          the left X position
     * @param y          the top Y position
     * @param label      the bar label (e.g. "HP")
     * @param current    the current value
     * @param maximum    the maximum value
     * @param fillColor  the fill color (ARGB)
     * @param bgColor    the background color (ARGB)
     * @return the Y position below the bar
     */
    private static int drawBar(GuiGraphics graphics, Minecraft mc,
                               int x, int y, String label,
                               float current, float maximum,
                               int fillColor, int bgColor) {
        // Label
        String labelText = label + ": " + (int) current + "/" + (int) maximum;
        graphics.drawString(mc.font, labelText, x, y, 0xCCCCCC, false);
        y += LINE_HEIGHT;

        // Bar background
        int barX = x + 2;
        graphics.fill(barX, y, barX + BAR_WIDTH, y + BAR_HEIGHT, bgColor);

        // Bar fill
        if (maximum > 0) {
            int fillWidth = Math.max(0, Math.min(BAR_WIDTH,
                    (int) ((current / maximum) * BAR_WIDTH)));
            if (fillWidth > 0) {
                graphics.fill(barX, y, barX + fillWidth, y + BAR_HEIGHT, fillColor);
            }
        }

        // Bar border
        graphics.renderOutline(barX - 1, y - 1, BAR_WIDTH + 2, BAR_HEIGHT + 2, 0xFF333333);

        return y + BAR_HEIGHT + 2;
    }

    // --- State update methods (called from network packet handlers) ---

    /** Update companion health. */
    public static void setHealth(float current, float max) {
        health = current;
        maxHealth = max;
    }

    /** Update companion food level. */
    public static void setFoodLevel(int food) {
        foodLevel = Math.max(0, Math.min(20, food));
    }

    /** Update companion air supply. */
    public static void setAirSupply(int air, int max) {
        airSupply = air;
        maxAirSupply = max;
    }

    /** Update the current task display. */
    public static void setCurrentTask(String task) {
        currentTask = task;
    }

    /** Update the companion position. */
    public static void setPosition(double x, double y, double z) {
        posX = x;
        posY = y;
        posZ = z;
    }

    /** Set whether the companion is spawned. */
    public static void setSpawned(boolean isSpawned) {
        spawned = isSpawned;
    }

    /** Check if a companion is spawned. */
    public static boolean isSpawned() {
        return spawned;
    }

    /**
     * Bulk update from server data.
     *
     * @param hp     current health
     * @param maxHp  max health
     * @param food   food level
     * @param air    air supply
     * @param maxAir max air supply
     * @param task   current task
     * @param x      position X
     * @param y      position Y
     * @param z      position Z
     */
    public static void updateAll(float hp, float maxHp, int food,
                                  int air, int maxAir, String task,
                                  double x, double y, double z) {
        health = hp;
        maxHealth = maxHp;
        foodLevel = food;
        airSupply = air;
        maxAirSupply = maxAir;
        currentTask = task;
        posX = x;
        posY = y;
        posZ = z;
        spawned = true;
    }
}
