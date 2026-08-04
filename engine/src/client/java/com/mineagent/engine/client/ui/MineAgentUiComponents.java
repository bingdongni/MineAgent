package com.mineagent.engine.client.ui;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.util.List;

/**
 * Shared drawing and layout primitives for the Fabric and NeoForge clients.
 *
 * <p>The UI deliberately uses one opaque graphite surface with a quiet accent
 * line. Earlier screens combined a gold double border, a title band, corner
 * blocks, a dot grid and black edit boxes; at normal GUI scales that read as
 * two stacked dialogs and made the actual controls difficult to scan.</n+ */
public final class MineAgentUiComponents {

    private MineAgentUiComponents() {}

    public static final int COLOR_PANEL_BG = 0xF0181D20;
    public static final int COLOR_PANEL_BG_ALT = 0xF0242B2F;
    public static final int COLOR_BORDER = 0xFF4B565B;
    public static final int COLOR_BORDER_OUTER = 0xFF0E1214;
    public static final int COLOR_ACCENT = 0xFF55C2AA;
    public static final int COLOR_TITLE = 0xFFF1F5F4;
    public static final int COLOR_SUBTITLE = 0xFFAAB4B6;
    public static final int COLOR_MUTED = 0xFF7F8A8D;
    public static final int COLOR_DIVIDER = 0xFF394247;

    public static final int COLOR_BUBBLE_OWNER_BG = 0xE024423A;
    public static final int COLOR_BUBBLE_OWNER_BORDER = 0xFF55A98F;
    public static final int COLOR_BUBBLE_OWNER_TEXT = 0xFFE2FFF6;
    public static final int COLOR_BUBBLE_AI_BG = 0xE022333F;
    public static final int COLOR_BUBBLE_AI_BORDER = 0xFF5B94B2;
    public static final int COLOR_BUBBLE_AI_TEXT = 0xFFE4F4FF;
    public static final int COLOR_BUBBLE_SYS_BG = 0xE03D3824;
    public static final int COLOR_BUBBLE_SYS_BORDER = 0xFF9E8E4C;
    public static final int COLOR_BUBBLE_SYS_TEXT = 0xFFFFF1B8;

    public static final int MARGIN = 10;
    public static final int PANEL_PADDING = 12;
    public static final int TITLE_BAND_HEIGHT = 24;
    public static final int FIELD_LABEL_GAP = 2;
    public static final int BUBBLE_PADDING_X = 7;
    public static final int BUBBLE_PADDING_Y = 5;
    public static final int BUBBLE_CORNER_SIZE = 2;
    private static final int MAX_BUBBLE_LINES = 5;

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /**
     * Fits a rectangle to the current GUI coordinate space.
     *
     * <p>Width and height are clamped before position. The old implementation
     * used the requested size while clamping the position and the fitted size
     * while drawing, which could shift a large dialog away from its true
     * center on small windows.
     */
    public static Rect clampToScreen(int x, int y, int width, int height,
                                     int screenWidth, int screenHeight) {
        int availableWidth = Math.max(1, screenWidth - 2 * MARGIN);
        int availableHeight = Math.max(1, screenHeight - 2 * MARGIN);
        int fittedWidth = Math.max(1, Math.min(width, availableWidth));
        int fittedHeight = Math.max(1, Math.min(height, availableHeight));
        int fittedX = clamp(x, MARGIN,
                Math.max(MARGIN, screenWidth - fittedWidth - MARGIN));
        int fittedY = clamp(y, MARGIN,
                Math.max(MARGIN, screenHeight - fittedHeight - MARGIN));
        return new Rect(fittedX, fittedY, fittedWidth, fittedHeight);
    }

    /** Returns a fitted panel centered in the current GUI coordinate space. */
    public static Rect centeredPanel(int screenWidth, int screenHeight,
                                     int preferredWidth, int preferredHeight) {
        int fittedWidth = Math.min(preferredWidth, Math.max(1, screenWidth - 2 * MARGIN));
        int fittedHeight = Math.min(preferredHeight, Math.max(1, screenHeight - 2 * MARGIN));
        return clampToScreen((screenWidth - fittedWidth) / 2,
                (screenHeight - fittedHeight) / 2,
                fittedWidth, fittedHeight, screenWidth, screenHeight);
    }

    /** Draws the single primary surface used by every full-screen MineAgent dialog. */
    public static void drawPanel(GuiGraphics graphics, int x, int y,
                                 int width, int height, Component title,
                                 boolean accent, int screenWidth, int screenHeight) {
        Rect panel = clampToScreen(x, y, width, height, screenWidth, screenHeight);
        x = panel.x();
        y = panel.y();
        width = panel.width();
        height = panel.height();

        // A two-pixel shadow separates the modal from the moving world without
        // adding another visible frame around it.
        graphics.fill(x + 2, y + 2, x + width + 2, y + height + 2, 0x70000000);
        graphics.fill(x, y, x + width, y + height, COLOR_PANEL_BG);
        drawBorderRect(graphics, x, y, width, height, COLOR_BORDER_OUTER);
        drawBorderRect(graphics, x + 1, y + 1, width - 2, height - 2, COLOR_BORDER);

        if (title != null && height >= TITLE_BAND_HEIGHT) {
            graphics.fill(x + 2, y + 2, x + width - 2,
                    y + TITLE_BAND_HEIGHT, COLOR_PANEL_BG_ALT);
            graphics.fill(x + 2, y + TITLE_BAND_HEIGHT - 1,
                    x + width - 2, y + TITLE_BAND_HEIGHT, COLOR_DIVIDER);
            graphics.fill(x + 2, y + 2, x + width - 2, y + (accent ? 4 : 3),
                    accent ? COLOR_ACCENT : COLOR_BORDER);

            Font font = Minecraft.getInstance().font;
            int titleY = y + 5 + Math.max(0, (TITLE_BAND_HEIGHT - 5 - font.lineHeight) / 2);
            graphics.drawString(font, title, x + PANEL_PADDING, titleY,
                    COLOR_TITLE, false);
        }
    }

    public static void drawPanel(GuiGraphics graphics, int x, int y,
                                 int width, int height,
                                 int screenWidth, int screenHeight) {
        drawPanel(graphics, x, y, width, height, null, false,
                screenWidth, screenHeight);
    }

    /** Draws a compact section label and divider without creating a nested card. */
    public static void drawSectionHeader(GuiGraphics graphics, Component text,
                                         int x, int y, int width) {
        Font font = Minecraft.getInstance().font;
        graphics.drawString(font, text, x, y, COLOR_SUBTITLE, false);
        int lineY = y + font.lineHeight + 2;
        graphics.fill(x, lineY, x + Math.max(0, width), lineY + 1, COLOR_DIVIDER);
    }

    /** Draws the visible label required for an edit box. Hints are supplementary only. */
    public static void drawFieldLabel(GuiGraphics graphics, Component label, int x, int y) {
        graphics.drawString(Minecraft.getInstance().font, label, x, y,
                COLOR_SUBTITLE, false);
    }

    public static void drawStatusIndicator(GuiGraphics graphics, int x, int y,
                                           Component label, boolean online) {
        int dotColor = online ? 0xFF61D095 : 0xFFE06C75;
        graphics.fill(x, y + 2, x + 4, y + 6, dotColor);
        graphics.drawString(Minecraft.getInstance().font, label,
                x + 8, y, COLOR_TITLE, false);
    }

    public enum BubbleType {
        OWNER, AI, SYSTEM
    }

    /** Measures a wrapped chat bubble using the same rules as {@link #drawChatBubble}. */
    public static BubbleMetrics measureChatBubble(String text, BubbleType type,
                                                   int maxWidth) {
        BubbleLayout layout = layoutBubble(text, maxWidth);
        return new BubbleMetrics(layout.width(), layout.height());
    }

    /**
     * Draws a wrapped chat bubble and returns its actual dimensions.
     *
     * <p>The previous implementation truncated every message to a single line,
     * which made longer LLM replies unreadable. Bubbles now wrap up to five
     * lines; the chat view scrolls by complete messages.
     */
    public static BubbleMetrics drawChatBubble(GuiGraphics graphics, int x, int y,
                                               String text, BubbleType type,
                                               int maxWidth, int screenWidth) {
        BubbleLayout layout = layoutBubble(text, maxWidth);
        int clampedX = clamp(x, MARGIN,
                Math.max(MARGIN, screenWidth - layout.width() - MARGIN));

        int bgColor;
        int borderColor;
        int textColor;
        switch (type) {
            case OWNER -> {
                bgColor = COLOR_BUBBLE_OWNER_BG;
                borderColor = COLOR_BUBBLE_OWNER_BORDER;
                textColor = COLOR_BUBBLE_OWNER_TEXT;
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

        drawRoundedRect(graphics, clampedX, y, layout.width(), layout.height(),
                bgColor, BUBBLE_CORNER_SIZE);
        drawRoundedBorder(graphics, clampedX, y, layout.width(), layout.height(),
                borderColor, BUBBLE_CORNER_SIZE);

        Font font = Minecraft.getInstance().font;
        int textY = y + BUBBLE_PADDING_Y;
        for (FormattedCharSequence line : layout.lines()) {
            graphics.drawString(font, line, clampedX + BUBBLE_PADDING_X,
                    textY, textColor, false);
            textY += font.lineHeight;
        }
        if (layout.truncated()) {
            graphics.drawString(font, "...", clampedX + BUBBLE_PADDING_X,
                    y + layout.height() - BUBBLE_PADDING_Y - font.lineHeight,
                    textColor, false);
        }
        return new BubbleMetrics(layout.width(), layout.height());
    }

    private static BubbleLayout layoutBubble(String text, int maxWidth) {
        Font font = Minecraft.getInstance().font;
        int fittedMaxWidth = Math.max(2 * BUBBLE_PADDING_X + 12, maxWidth);
        int textWidth = Math.max(12, fittedMaxWidth - 2 * BUBBLE_PADDING_X);
        List<FormattedCharSequence> wrapped = font.split(
                Component.literal(text == null ? "" : text), textWidth);
        boolean truncated = wrapped.size() > MAX_BUBBLE_LINES;
        List<FormattedCharSequence> visible = truncated
                ? wrapped.subList(0, MAX_BUBBLE_LINES) : wrapped;
        int widestLine = 0;
        for (FormattedCharSequence line : visible) {
            widestLine = Math.max(widestLine, font.width(line));
        }
        int width = Math.min(fittedMaxWidth,
                Math.max(28, widestLine + 2 * BUBBLE_PADDING_X));
        int lineCount = Math.max(1, visible.size());
        int height = lineCount * font.lineHeight + 2 * BUBBLE_PADDING_Y;
        return new BubbleLayout(List.copyOf(visible), width, height, truncated);
    }

    private static void drawBorderRect(GuiGraphics graphics, int x, int y,
                                       int width, int height, int color) {
        if (width <= 1 || height <= 1) return;
        graphics.fill(x, y, x + width, y + 1, color);
        graphics.fill(x, y + height - 1, x + width, y + height, color);
        graphics.fill(x, y, x + 1, y + height, color);
        graphics.fill(x + width - 1, y, x + width, y + height, color);
    }

    private static void drawRoundedRect(GuiGraphics graphics, int x, int y,
                                        int width, int height,
                                        int color, int cornerSize) {
        graphics.fill(x + cornerSize, y, x + width - cornerSize,
                y + cornerSize, color);
        graphics.fill(x, y + cornerSize, x + width,
                y + height - cornerSize, color);
        graphics.fill(x + cornerSize, y + height - cornerSize,
                x + width - cornerSize, y + height, color);
    }

    private static void drawRoundedBorder(GuiGraphics graphics, int x, int y,
                                          int width, int height,
                                          int color, int cornerSize) {
        graphics.fill(x + cornerSize, y, x + width - cornerSize, y + 1, color);
        graphics.fill(x + cornerSize, y + height - 1,
                x + width - cornerSize, y + height, color);
        graphics.fill(x, y + cornerSize, x + 1, y + height - cornerSize, color);
        graphics.fill(x + width - 1, y + cornerSize,
                x + width, y + height - cornerSize, color);
    }

    public record Rect(int x, int y, int width, int height) {}

    public record BubbleMetrics(int width, int height) {}

    private record BubbleLayout(List<FormattedCharSequence> lines, int width,
                                int height, boolean truncated) {}
}
