package com.mineagent.engine.client.screen;

import com.mineagent.engine.client.ui.MineAgentUiComponents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.lwjgl.glfw.GLFW;

import java.util.List;

/** Scrollable command and key reference. */
public class HelpScreen extends Screen {

    private static final int BUTTON_HEIGHT = 20;
    private static final int LINE_HEIGHT = 11;
    private static final int HEADER_HEIGHT = 16;
    private static final int SECTION_GAP = 6;
    private static final int SCROLL_STEP = 33;

    private static final List<HelpSection> SECTIONS = List.of(
            new HelpSection("screen.mineagent.help.start.title", List.of(
                    "screen.mineagent.help.start.1",
                    "screen.mineagent.help.start.2",
                    "screen.mineagent.help.start.3",
                    "screen.mineagent.help.start.4")),
            new HelpSection("screen.mineagent.help.commands.title", List.of(
                    "screen.mineagent.help.commands.quick",
                    "screen.mineagent.help.commands.remove",
                    "screen.mineagent.help.commands.respawn",
                    "screen.mineagent.help.commands.list",
                    "screen.mineagent.help.commands.providers",
                    "screen.mineagent.help.commands.models",
                    "screen.mineagent.help.commands.config",
                    "screen.mineagent.help.commands.reload",
                    "screen.mineagent.help.commands.skin",
                    "screen.mineagent.help.commands.resetskin")),
            new HelpSection("screen.mineagent.help.keys.title", List.of(
                    "screen.mineagent.help.keys.menu",
                    "screen.mineagent.help.keys.chat",
                    "screen.mineagent.help.keys.hud",
                    "screen.mineagent.help.keys.path",
                    "screen.mineagent.help.keys.vision",
                    "screen.mineagent.help.keys.label")),
            new HelpSection("screen.mineagent.help.behavior.title", List.of(
                    "screen.mineagent.help.behavior.1",
                    "screen.mineagent.help.behavior.2",
                    "screen.mineagent.help.behavior.3"))
    );

    private MineAgentUiComponents.Rect panel;
    private int contentX;
    private int contentWidth;
    private int viewportTop;
    private int viewportBottom;
    private int scrollOffset;
    private int maxScroll;
    private Button scrollUpButton;
    private Button scrollDownButton;

    public HelpScreen() {
        super(Component.translatable("screen.mineagent.help.title"));
    }

    @Override
    protected void init() {
        super.init();
        panel = MineAgentUiComponents.centeredPanel(
                this.width, this.height, 440, 360);
        contentX = panel.x() + MineAgentUiComponents.PANEL_PADDING;
        contentWidth = panel.width() - MineAgentUiComponents.PANEL_PADDING * 2;
        viewportTop = panel.y() + MineAgentUiComponents.TITLE_BAND_HEIGHT + 8;
        int actionY = panel.y() + panel.height()
                - MineAgentUiComponents.PANEL_PADDING - BUTTON_HEIGHT;
        viewportBottom = actionY - 8;

        int totalHeight = measureContentHeight(Math.max(40, contentWidth - 8));
        maxScroll = Math.max(0, totalHeight - Math.max(1, viewportBottom - viewportTop));
        scrollOffset = MineAgentUiComponents.clamp(scrollOffset, 0, maxScroll);

        int smallButton = BUTTON_HEIGHT;
        int gap = 4;
        int backWidth = Math.max(40, contentWidth - smallButton * 2 - gap * 2);
        this.addRenderableWidget(Button.builder(Component.translatable("gui.back"),
                ignored -> Minecraft.getInstance().setScreen(new MineAgentMainMenuScreen()))
                .bounds(contentX, actionY, backWidth, BUTTON_HEIGHT).build());
        scrollUpButton = this.addRenderableWidget(Button.builder(Component.literal("▲"),
                ignored -> scrollBy(-SCROLL_STEP))
                .bounds(contentX + backWidth + gap, actionY,
                        smallButton, BUTTON_HEIGHT).build());
        scrollDownButton = this.addRenderableWidget(Button.builder(Component.literal("▼"),
                ignored -> scrollBy(SCROLL_STEP))
                .bounds(contentX + backWidth + gap + smallButton + gap, actionY,
                        smallButton, BUTTON_HEIGHT).build());
        refreshScrollButtons();
    }

    private int measureContentHeight(int textWidth) {
        int height = 0;
        for (HelpSection section : SECTIONS) {
            height += HEADER_HEIGHT;
            for (String lineKey : section.lineKeys()) {
                height += Math.max(1, this.font.split(
                        Component.translatable(lineKey), textWidth).size()) * LINE_HEIGHT;
            }
            height += SECTION_GAP;
        }
        return height;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        MineAgentUiComponents.drawPanel(graphics, panel.x(), panel.y(),
                panel.width(), panel.height(), this.title, true,
                this.width, this.height);

        int textWidth = Math.max(40, contentWidth - 8);
        int y = viewportTop - scrollOffset;
        graphics.enableScissor(contentX, viewportTop,
                contentX + contentWidth, viewportBottom);
        for (HelpSection section : SECTIONS) {
            if (y + HEADER_HEIGHT >= viewportTop && y < viewportBottom) {
                MineAgentUiComponents.drawSectionHeader(graphics,
                        Component.translatable(section.titleKey()),
                        contentX, y, textWidth);
            }
            y += HEADER_HEIGHT;
            for (String lineKey : section.lineKeys()) {
                List<FormattedCharSequence> lines = this.font.split(
                        Component.translatable(lineKey), textWidth);
                for (FormattedCharSequence line : lines) {
                    if (y + LINE_HEIGHT >= viewportTop && y < viewportBottom) {
                        graphics.drawString(this.font, line, contentX, y,
                                0xFFD2D9DA, false);
                    }
                    y += LINE_HEIGHT;
                }
            }
            y += SECTION_GAP;
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int viewportHeight = viewportBottom - viewportTop;
            int totalHeight = viewportHeight + maxScroll;
            int thumbHeight = Math.max(12,
                    viewportHeight * viewportHeight / Math.max(1, totalHeight));
            int thumbTravel = viewportHeight - thumbHeight;
            int thumbY = viewportTop + thumbTravel * scrollOffset / maxScroll;
            int barX = contentX + contentWidth - 3;
            graphics.fill(barX, viewportTop, barX + 2, viewportBottom,
                    MineAgentUiComponents.COLOR_DIVIDER);
            graphics.fill(barX, thumbY, barX + 2, thumbY + thumbHeight,
                    MineAgentUiComponents.COLOR_ACCENT);
        }

        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY,
                                 double scrollX, double scrollY) {
        if (scrollY != 0.0) {
            scrollBy(scrollY > 0 ? -SCROLL_STEP : SCROLL_STEP);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP || keyCode == GLFW.GLFW_KEY_UP) {
            scrollBy(-SCROLL_STEP);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN || keyCode == GLFW.GLFW_KEY_DOWN) {
            scrollBy(SCROLL_STEP);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_HOME) {
            scrollOffset = 0;
            refreshScrollButtons();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_END) {
            scrollOffset = maxScroll;
            refreshScrollButtons();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void scrollBy(int amount) {
        scrollOffset = MineAgentUiComponents.clamp(scrollOffset + amount, 0, maxScroll);
        refreshScrollButtons();
    }

    private void refreshScrollButtons() {
        if (scrollUpButton != null) scrollUpButton.active = scrollOffset > 0;
        if (scrollDownButton != null) scrollDownButton.active = scrollOffset < maxScroll;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }

    private record HelpSection(String titleKey, List<String> lineKeys) {}
}
