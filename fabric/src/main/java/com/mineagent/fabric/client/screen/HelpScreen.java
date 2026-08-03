package com.mineagent.fabric.client.screen;

import com.mineagent.fabric.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Help screen — displays all available commands and usage tips
 * in a beautifully formatted in-game page.
 *
 * <p><b>UI Design</b>: Uses {@link MineAgentUiComponents} for a styled
 * panel background with dot grid texture, gold-accented borders, tilted
 * corner decorations, and section headers. All content is boundary-clamped
 * to ensure it never exceeds the visible screen area.
 */
public class HelpScreen extends Screen {

    private static final int BTN_WIDTH = 200;
    private static final int BTN_HEIGHT = 20;
    private static final int MARGIN = 10;
    private static final int PANEL_PADDING = 8;
    private static final int LINE_HEIGHT = 11;

    /** Computed panel bounds (clamped to screen in init). */
    private int panelX, panelY, panelWidth, panelHeight;
    private int contentStartY;

    public HelpScreen() {
        super(Component.literal("Help"));
    }

    @Override
    protected void init() {
        super.init();

        int centerX = this.width / 2;

        // ── Compute panel dimensions ──
        // Help content: 4 sections with multiple lines each
        // Total ~25 lines * LINE_HEIGHT + section headers + padding
        int contentHeight = 28 * LINE_HEIGHT + 16;
        panelWidth = Math.min(280,
                this.width - 2 * MARGIN);
        panelHeight = Math.min(contentHeight + MineAgentUiComponents.TITLE_BAND_HEIGHT
                        + PANEL_PADDING * 2 + 16,
                this.height - 2 * MARGIN);

        panelX = MineAgentUiComponents.clamp(centerX - panelWidth / 2, MARGIN,
                Math.max(MARGIN, this.width - panelWidth - MARGIN));
        panelY = MineAgentUiComponents.clamp((this.height - panelHeight) / 2, MARGIN,
                Math.max(MARGIN, this.height - panelHeight - MARGIN));

        contentStartY = panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT + PANEL_PADDING;

        // --- Back Button (boundary-clamped) ---
        int backX = MineAgentUiComponents.clamp(centerX - BTN_WIDTH / 2, MARGIN,
                Math.max(MARGIN, this.width - BTN_WIDTH - MARGIN));
        int backY = MineAgentUiComponents.clamp(this.height - 30, MARGIN,
                this.height - BTN_HEIGHT - MARGIN);
        this.addRenderableWidget(Button.builder(
                Component.literal("§7Back"),
                btn -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen())
        ).bounds(backX, backY, BTN_WIDTH, BTN_HEIGHT).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        int centerX = this.width / 2;

        // ── Styled panel background ──
        MineAgentUiComponents.drawPanel(
                graphics,
                panelX, panelY, panelWidth, panelHeight,
                Component.literal("§6§lMineAgent Help"),
                true,
                this.width, this.height
        );

        // ── Tilted decorations at title corners ──
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, MineAgentUiComponents.COLOR_CORNER, -2.0f);
        MineAgentUiComponents.drawTiltedDecoration(
                graphics,
                panelX + panelWidth - 8,
                panelY + MineAgentUiComponents.TITLE_BAND_HEIGHT / 2,
                4, MineAgentUiComponents.COLOR_CORNER, 2.0f);

        // ── Content area ──
        int leftX = panelX + PANEL_PADDING;
        int contentWidth = panelWidth - PANEL_PADDING * 2;
        // Boundary-safe: clamp Y to panel bounds
        int y = MineAgentUiComponents.clamp(contentStartY, MARGIN,
                this.height - LINE_HEIGHT - MARGIN);
        int lineH = LINE_HEIGHT;

        // Section: Getting Started
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§e§nGetting Started",
                leftX, y, contentWidth, MineAgentUiComponents.COLOR_TITLE);
        y += lineH + 2;
        y = drawHelpLine(graphics, "§71. Edit §fconfig/mineagent.json §7with your API key", leftX, y);
        y = drawHelpLine(graphics, "§72. Run §e/mineagent reload §7in chat", leftX, y);
        y = drawHelpLine(graphics, "§73. Run §e/mineagent quick §7or use the GUI", leftX, y);
        y = drawHelpLine(graphics, "§74. Talk to your companion in chat (T key)", leftX, y);
        y += 4;

        // Section: Commands
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§e§nCommands",
                leftX, y, contentWidth, MineAgentUiComponents.COLOR_TITLE);
        y += lineH + 2;
        y = drawHelpLine(graphics, "§e/mineagent quick §7[name] §8- Quick spawn", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent spawn §7<name> <provider> <model> <key>", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent remove §8- Remove companion", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent respawn §8- Respawn dead companion", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent list §8- List companions", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent providers §8- List LLM providers", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent models §7[provider] §8- List models", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent config §8- Show config", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent reload §8- Reload config file", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent setskin §7<name> §8- Set skin from player", leftX, y);
        y = drawHelpLine(graphics, "§e/mineagent resetskin §8- Reset to Steve/Alex", leftX, y);
        y += 4;

        // Section: Key Bindings
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§e§nKey Bindings",
                leftX, y, contentWidth, MineAgentUiComponents.COLOR_TITLE);
        y += lineH + 2;
        y = drawHelpLine(graphics, "§eM §8- Open MineAgent control panel", leftX, y);
        y = drawHelpLine(graphics, "§eC §8- Open companion chat screen", leftX, y);
        y = drawHelpLine(graphics, "§eH §8- Toggle status HUD overlay", leftX, y);
        y = drawHelpLine(graphics, "§eP §8- Toggle path debug rendering", leftX, y);
        y += 4;

        // Section: Tips
        MineAgentUiComponents.drawSectionHeader(
                graphics, "§e§nTips",
                leftX, y, contentWidth, MineAgentUiComponents.COLOR_TITLE);
        y += lineH + 2;
        y = drawHelpLine(graphics, "§7- DeepSeek §7is recommended for testing (cheap & fast)", leftX, y);
        y = drawHelpLine(graphics, "§7- Use §fClaude Opus 5 §7or §fGPT-5.6 §7for best intelligence", leftX, y);
        y = drawHelpLine(graphics, "§7- The companion has survival instincts (auto-eat, fight)", leftX, y);
        y = drawHelpLine(graphics, "§7- Toggle reflexes in the chat screen (C key)", leftX, y);

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * Draw a single help line, boundary-clamped to panel bounds.
     * Returns the Y position for the next line.
     */
    private int drawHelpLine(GuiGraphics graphics, String text, int x, int y) {
        // Boundary-safe: clamp Y to visible area
        y = MineAgentUiComponents.clamp(y, MARGIN,
                this.height - LINE_HEIGHT - MARGIN);
        graphics.drawString(this.minecraft.font, text, x, y, 0xCCCCCC, false);
        return y + LINE_HEIGHT;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}
