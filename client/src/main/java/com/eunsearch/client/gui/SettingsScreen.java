package com.eunsearch.client.gui;

import com.eunsearch.client.config.ClientConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public class SettingsScreen extends Screen {
    private final Screen parent;
    private EditBox rangeField;
    private EditBox requestRateField;
    private ClientConfig config;

    public SettingsScreen(Screen parent) {
        super(Component.literal("EunSearch Client Settings"));
        this.parent = parent;
        this.config = ClientConfig.get();
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int y = this.height / 2 - 60;

        this.rangeField = new EditBox(this.font, centerX - 100, y, 200, 20, Component.literal("Range"));
        this.rangeField.setValue(String.valueOf(config.scanRangeBlocks));
        this.addRenderableWidget(this.rangeField);

        this.requestRateField = new EditBox(this.font, centerX - 100, y + 34, 200, 20, Component.literal("Requests/tick"));
        this.requestRateField.setValue(String.valueOf(config.maxRequestsPerTick));
        this.addRenderableWidget(this.requestRateField);

        this.addRenderableWidget(Button.builder(Component.literal("Save"), button -> this.saveAndClose())
                .bounds(centerX - 102, y + 70, 100, 20).build());
        this.addRenderableWidget(Button.builder(Component.literal("Cancel"), button -> this.minecraft.gui.setScreen(this.parent))
                .bounds(centerX + 2, y + 70, 100, 20).build());
    }

    private void saveAndClose() {
        try {
            config.scanRangeBlocks = Math.max(4, Integer.parseInt(this.rangeField.getValue().trim()));
        } catch (NumberFormatException ignored) { }
        try {
            config.maxRequestsPerTick = Math.max(1, Math.min(100, Integer.parseInt(this.requestRateField.getValue().trim())));
        } catch (NumberFormatException ignored) { }
        config.save();
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(guiGraphics, mouseX, mouseY, partialTick);
        int centerX = this.width / 2;
        int y = this.height / 2 - 60;
        guiGraphics.text(this.font, "Scan range (blocks)", centerX - 70, y - 12, 0xFFFFFFFF);
        guiGraphics.text(this.font, "Max requests per tick", centerX - 70, y + 22, 0xFFFFFFFF);
    }
}
