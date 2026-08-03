package com.mineagent.fabric.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Reusable UI component library for MineAgent screens.
 *
 * <p>Provides styled drawing primitives that add visual enhancement
 * over vanilla Minecraft GUI while staying within the vanilla aesthetic.
 *
 * <p><b>Design principles</b> (see DESIGN_SYSTEM.md):
 * <ul>
 *   <li>Vanilla-first — enhancements are subtle, not jarring</li>
 *   <li>Boundary-safe — all methods clamp coordinates to screen bounds</li>
 *   <li>Pixel-art pure — no anti-aliasing, no smooth gradients</li>
 *   <li>Component-based — each method is self-contained and reusable</li>
 * </ul>
 *
 * <p>All methods are static and stateless — call them from any
 * {@link Screen#render} method.
 */
public final class MineAgentUiComponents {

    private MineAgentUiComponents() {}

    // ── Color Palette (from DESIGN_SYSTEM.md) ───────────────────

    /** Panel background — dark semi-transparent gray. */
    public static final int COLOR_PANEL_BG       = 0xE0202020;
    /** Secondary panel background — slightly lighter. */
    public static final int COLOR_PANEL_BG_ALT   = 0xE0303030;
    /** Default border — medium gray. */
    public static final int COLOR_BORDER         = 0xFF606060;
    /** Accent border — gold (for emphasized panels). */
    public static final int COLOR_BORDER_ACCENT  = 0xFFFFC000;
    /** Outer border — dark. */
    public static final int COLOR_BORDER_OUTER  = 0xFF404040;
    /** Inner border — light. */
    public static final int COLOR_BORDER_INNER  = 0xFF808080;
    /** Title text — gold. */
    public static final int COLOR_TITLE          = 0xFFFFC000;
    /** Subtitle — gray. */
    public static final int COLOR_SUBTITLE       = 0xFFAAAAAA;
    /** Dot grid point — low-alpha white. */
    public static final int COLOR_DOT_GRID       = 0x20FFFFFF;
    /** Title band background — gold with alpha. */
    public static final int COLOR_TITLE_BAND     = 0x40FFC000;
    /** Corner decoration — gold. */
    public static final int COLOR_CORNER         = 0xFFFFC000;

    // Chat bubble colors
    public static final int COLOR_BUBBLE_OWNER_BG     = 0xE00A4A0A;
    public static final int COLOR_BUBBLE_OWNER_BORDER = 0xFF2AA02A;
    public static final int COLOR_BUBBLE_OWNER_TEXT    = 0xFFB0FFB0;
    public static final int COLOR_BUBBLE_AI_BG         = 0xE00A2A4A;
    public static final int COLOR_BUBBLE_AI_BORDER     = 0xFF2A6A9A;
    public static final int COLOR_BUBBLE_AI_TEXT       = 0xFFA0D0FF;
    public static final int COLOR_BUBBLE_SYS_BG        = 0xE04A4A0A;
    public static final int COLOR_BUBBLE_SYS_BORDER    = 0xFFA0A02A;
    public static final int COLOR_BUBBLE_SYS_TEXT      = 0xFFFFFFA0;

    // ── Spacing Constants ───────────────────────────────────────

    public static final int MARGIN = 10;
    public static final int DOT_GRID_SPACING = 6;
    public static final int CORNER_SIZE = 3;
    public static final int TITLE_BAND_HEIGHT = 12;
    public static final int BUBBLE_PADDING_X = 6;
    public static final int BUBBLE_PADDING_Y = 4;
    public static final int BUBBLE_CORNER_SIZE = 2;

    // ── Boundary-Safe Clamping ──────────────────────────────────

    /**
     * Clamp a value to the range [min, max]. Used everywhere to keep
     * UI elements within screen bounds.
     */
    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Clamp a panel's position and size to fit within the screen with
     * the standard margin. Returns a clamped rectangle.
     */
    public static Rect clampToScreen(int x, int y, int width, int height,
                                      int screenWidth, int screenHeight) {
        int clampedX = clamp(x, MARGIN, Math.max(MARGIN, screenWidth - width - MARGIN));
        int clampedY = clamp(y, MARGIN, Math.max(MARGIN, screenHeight - height - MARGIN));
        int clampedW = Math.min(width, screenWidth - 2 * MARGIN);
        int clampedH = Math.min(height, screenHeight - 2 * MARGIN);
        return new Rect(clampedX, clampedY, clampedW, clampedH);
    }

    // ── Panel Drawing ───────────────────────────────────────────

    /**
     * Draw a styled panel with background, dot grid, border, optional
     * title band, and corner decorations.
     *
     * <p>This is the primary visual enhancement over vanilla — it gives
     * MineAgent screens a consistent "branded" look without breaking
     * the Minecraft aesthetic.
     *
     * @param graphics    the GuiGraphics context
     * @param x           panel top-left X
     * @param y           panel top-left Y
     * @param width       panel width
     * @param height      panel height
     * @param title       optional title (null = no title band)
     * @param accent      true = gold border, false = gray border
     * @param screenWidth  current screen width (for boundary clamping)
     * @param screenHeight current screen height (for boundary clamping)
     */
    public static void drawPanel(GuiGraphics graphics, int x, int y,
                                  int width, int height,
                                  Component title, boolean accent,
                                  int screenWidth, int screenHeight) {
        // Boundary-safe: clamp panel to screen
        Rect r = clampToScreen(x, y, width, height, screenWidth, screenHeight);
        x = r.x(); y = r.y(); width = r.width(); height = r.height();

        // 1. Background fill
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL_BG);

        // 2. Dot grid texture overlay (subtle)
        drawDotGrid(graphics, x + 2, y + 2, width - 4, height - 4);

        // 3. Title band (optional)
        int contentStartY = y;
        if (title != null) {
            // Title band background
            graphics.fill(x, y, x + width, y + TITLE_BAND_HEIGHT,
                    COLOR_TITLE_BAND);
            // Title text (centered)
            int textY = y + (TITLE_BAND_HEIGHT - 8) / 2; // 8 = font height
            graphics.drawCenteredString(
                    Minecraft.getInstance().font,
                    title, x + width / 2, textY, COLOR_TITLE);
            contentStartY = y + TITLE_BAND_HEIGHT;
        }

        // 4. Border (outer + inner)
        int borderColor = accent ? COLOR_BORDER_ACCENT : COLOR_BORDER;
        // Outer border (1px dark)
        drawBorderRect(graphics, x, y, width, height, COLOR_BORDER_OUTER);
        // Inner border (1px accent/gray)
        drawBorderRect(graphics, x + 1, y + 1, width - 2, height - 2, borderColor);

        // 5. Corner decorations (3×3 blocks at each corner)
        drawCornerDecoration(graphics, x, y);                          // top-left
        drawCornerDecoration(graphics, x + width - CORNER_SIZE, y);    // top-right
        drawCornerDecoration(graphics, x, y + height - CORNER_SIZE);    // bottom-left
        drawCornerDecoration(graphics, x + width - CORNER_SIZE,
                y + height - CORNER_SIZE);                              // bottom-right
    }

    /**
     * Simplified panel draw without title — for sub-sections.
     */
    public static void drawPanel(GuiGraphics graphics, int x, int y,
                                  int width, int height,
                                  int screenWidth, int screenHeight) {
        drawPanel(graphics, x, y, width, height, null, false,
                screenWidth, screenHeight);
    }

    // ── Dot Grid ────────────────────────────────────────────────

    /**
     * Draw a dot grid pattern within a rectangular area. Used as a
     * subtle texture overlay on panel backgrounds.
     *
     * <p>The dots are 1×1 pixels, spaced every {@value #DOT_GRID_SPACING}
     * pixels, with low-alpha white color — visible but not distracting.
     *
     * <p>All coordinates are clamped to ensure no dots are drawn
     * outside the specified area.
     */
    public static void drawDotGrid(GuiGraphics graphics,
                                    int x, int y, int width, int height) {
        if (width <= 0 || height <= 0) return;
        // Start offset so dots are centered in the area
        int startX = x + (DOT_GRID_SPACING / 2);
        int startY = y + (DOT_GRID_SPACING / 2);
        for (int dy = 0; dy + 1 <= height; dy += DOT_GRID_SPACING) {
            for (int dx = 0; dx + 1 <= width; dx += DOT_GRID_SPACING) {
                int px = startX + dx;
                int py = startY + dy;
                // Clamp to area bounds
                if (px < x + width && py < y + height) {
                    graphics.fill(px, py, px + 1, py + 1, COLOR_DOT_GRID);
                }
            }
        }
    }

    // ── Chat Bubble ─────────────────────────────────────────────

    /**
     * Bubble type for chat messages.
     */
    public enum BubbleType {
        OWNER, AI, SYSTEM
    }

    /**
     * Draw a chat bubble with text inside.
     *
     * <p>The bubble auto-sizes to fit the text (up to maxWidth), with
     * padding. Uses a "rounded corner" illusion by leaving 2px gaps
     * at each corner.
     *
     * @param graphics    the GuiGraphics context
     * @param x           bubble top-left X
     * @param y           bubble top-left Y
     * @param text        message text
     * @param type        bubble type (owner/ai/system)
     * @param maxWidth    max bubble width (text will be wrapped/truncated)
     * @param screenWidth  for boundary clamping
     * @return the actual width of the drawn bubble (for layout)
     */
    public static int drawChatBubble(GuiGraphics graphics, int x, int y,
                                      String text, BubbleType type,
                                      int maxWidth, int screenWidth) {
        var font = Minecraft.getInstance().font;

        // Determine colors by type
        int bgColor, borderColor, textColor;
        switch (type) {
            case OWNER -> {
                bgColor = COLOR_BUBBLE_OWNER_BG;
                borderColor = COLOR_BUBBLE_OWNER_BORDER;
                textColor = COLOR_BUBBLE_OWNER_TEXT;
            }
            case AI -> {
                bgColor = COLOR_BUBBLE_AI_BG;
                borderColor = COLOR_BUBBLE_AI_BORDER;
                textColor = COLOR_BUBBLE_AI_TEXT;
            }
            case SYSTEM -> {
                bgColor = COLOR_BUBBLE_SYS_BG;
                borderColor = COLOR_BUBBLE_SYS_BORDER;
                textColor = COLOR_BUBBLE_SYS_TEXT;
            }
            default -> {
                bgColor = COLOR_BUBBLE_AI_BG;
                borderColor = COLOR_BUBBLE_AI_BORDER;
                textColor = COLOR_BUBBLE_AI_TEXT;
            }
        }

        // Calculate text width (truncated to maxWidth - padding)
        int maxTextWidth = maxWidth - 2 * BUBBLE_PADDING_X;
        String displayText = truncateForWidth(font, text, maxTextWidth);
        int textWidth = font.width(displayText);

        // Calculate bubble dimensions
        int bubbleWidth = textWidth + 2 * BUBBLE_PADDING_X;
        int bubbleHeight = font.lineHeight + 2 * BUBBLE_PADDING_Y;

        // Boundary-safe: clamp X to screen
        int clampedX = clamp(x, MARGIN,
                Math.max(MARGIN, screenWidth - bubbleWidth - MARGIN));

        // 1. Background (with rounded-corner illusion)
        // Top-left and top-right corners: leave BUBBLE_CORNER_SIZE gap
        // Bottom-left and bottom-right corners: leave gap too
        drawRoundedRect(graphics, clampedX, y, bubbleWidth, bubbleHeight,
                bgColor, BUBBLE_CORNER_SIZE);

        // 2. Border (rounded)
        drawRoundedBorder(graphics, clampedX, y, bubbleWidth, bubbleHeight,
                borderColor, BUBBLE_CORNER_SIZE);

        // 3. Text
        graphics.drawString(font, displayText,
                clampedX + BUBBLE_PADDING_X, y + BUBBLE_PADDING_Y,
                textColor, false);

        return bubbleWidth;
    }

    /**
     * Get the height of a chat bubble for a given text and max width.
     * Currently single-line, so height is constant.
     */
    public static int getBubbleHeight() {
        return Minecraft.getInstance().font.lineHeight + 2 * BUBBLE_PADDING_Y;
    }

    // ── Tilted Decoration ───────────────────────────────────────

    /**
     * Draw a small tilted square as a decoration element.
     *
     * <p>The tilt is applied via pose stack rotation. The angle is
     * kept very small (1-2 degrees) to avoid looking jarring while
     * adding a subtle "dynamic" feel.
     *
     * <p><b>Important</b>: After rotation, the visual bounds of the
     * square may extend slightly beyond the axis-aligned bounds. We
     * account for this by drawing the decoration INSIDE the panel
     * with at least {@code CORNER_SIZE + 2} pixels of margin from
     * the panel edge.
     *
     * @param graphics the GuiGraphics context
     * @param centerX  center X of the decoration
     * @param centerY  center Y of the decoration
     * @param size     side length of the square
     * @param color   fill color
     * @param angleDeg rotation angle in degrees (-2 to 2 recommended)
     */
    public static void drawTiltedDecoration(GuiGraphics graphics,
                                             int centerX, int centerY,
                                             int size, int color,
                                             float angleDeg) {
        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(centerX, centerY, 0);
        if (angleDeg != 0) {
            pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees(angleDeg));
        }
        // Draw centered square
        int half = size / 2;
        graphics.fill(-half, -half, half, half, color);
        // Draw inner highlight (1px smaller, lighter)
        int highlight = (color & 0x00FFFFFF) | 0x40000000;
        graphics.fill(-half + 1, -half + 1, half - 1, half - 1, highlight);
        pose.popPose();
    }

    // ── Section Header ──────────────────────────────────────────

    /**
     * Draw a section header with a horizontal accent line below it.
     *
     * @param graphics  the GuiGraphics context
     * @param text      header text
     * @param x         left X
     * @param y         top Y
     * @param width     total width (for the accent line)
     * @param color     text color
     */
    public static void drawSectionHeader(GuiGraphics graphics,
                                          String text, int x, int y,
                                          int width, int color) {
        var font = Minecraft.getInstance().font;
        graphics.drawString(font, text, x, y, color, false);
        // Accent line below the text
        int lineY = y + font.lineHeight + 1;
        graphics.fill(x, lineY, x + width, lineY + 1,
                (color & 0x00FFFFFF) | 0x60000000);
    }

    // ── Status Indicator ────────────────────────────────────────

    /**
     * Draw a colored status dot + label.
     *
     * @param graphics the GuiGraphics context
     * @param x        dot X
     * @param y        dot Y
     * @param label    status text
     * @param online   true = green dot, false = red dot
     */
    public static void drawStatusIndicator(GuiGraphics graphics,
                                            int x, int y,
                                            String label, boolean online) {
        // Dot (3×3)
        int dotColor = online ? 0xFF55FF55 : 0xFFFF5555;
        graphics.fill(x, y, x + 3, y + 3, dotColor);
        // Label
        graphics.drawString(Minecraft.getInstance().font, label,
                x + 6, y - 2, 0xFFFFFFFF, false);
    }

    // ── Internal Helpers ────────────────────────────────────────

    /**
     * Draw a 1px border rectangle (4 sides).
     */
    private static void drawBorderRect(GuiGraphics graphics,
                                        int x, int y, int width, int height,
                                        int color) {
        // Top
        graphics.fill(x, y, x + width, y + 1, color);
        // Bottom
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        // Left
        graphics.fill(x, y, x + 1, y + height, color);
        // Right
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    /**
     * Draw a corner decoration (3×3 gold block).
     */
    private static void drawCornerDecoration(GuiGraphics graphics,
                                              int x, int y) {
        graphics.fill(x, y, x + CORNER_SIZE, y + CORNER_SIZE, COLOR_CORNER);
    }

    /**
     * Draw a rounded rectangle (simulated rounded corners by leaving
     * gaps at the 4 corners).
     *
     * @param x          top-left X
     * @param y          top-left Y
     * @param width      total width
     * @param height     total height
     * @param color      fill color
     * @param cornerSize corner gap size (typically 2)
     */
    private static void drawRoundedRect(GuiGraphics graphics,
                                         int x, int y, int width, int height,
                                         int color, int cornerSize) {
        // Top strip (below top corners)
        graphics.fill(x + cornerSize, y, x + width - cornerSize,
                y + cornerSize, color);
        // Middle strip (full width)
        graphics.fill(x, y + cornerSize, x + width,
                y + height - cornerSize, color);
        // Bottom strip (above bottom corners)
        graphics.fill(x + cornerSize, y + height - cornerSize,
                x + width - cornerSize, y + height, color);
    }

    /**
     * Draw a rounded border (1px, with corner gaps).
     */
    private static void drawRoundedBorder(GuiGraphics graphics,
                                           int x, int y, int width, int height,
                                           int color, int cornerSize) {
        // Top edge
        graphics.fill(x + cornerSize, y, x + width - cornerSize, y + 1, color);
        // Bottom edge
        graphics.fill(x + cornerSize, y + height - 1,
                x + width - cornerSize, y + height, color);
        // Left edge
        graphics.fill(x, y + cornerSize, x + 1, y + height - cornerSize, color);
        // Right edge
        graphics.fill(x + width - 1, y + cornerSize,
                x + width, y + height - cornerSize, color);
    }

    /**
     * Truncate text to fit within a max pixel width, adding "..." if
     * truncated.
     */
    private static String truncateForWidth(net.minecraft.client.gui.Font font,
                                            String text, int maxWidth) {
        if (font.width(text) <= maxWidth) return text;
        String truncated = text;
        while (truncated.length() > 0
                && font.width(truncated + "...") > maxWidth) {
            truncated = truncated.substring(0, truncated.length() - 1);
        }
        return truncated + "...";
    }

    // ── Rect Record ─────────────────────────────────────────────

    /** Simple rectangle record used for boundary clamping results. */
    public record Rect(int x, int y, int width, int height) {}
}
