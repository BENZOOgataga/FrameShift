package com.benzoogataga.frameshift.command;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

// Centralizes chat formatting so /schem output stays consistent and easy to scan.
public final class SchemMessages {

    private static final ChatFormatting PREFIX_BRACKET = ChatFormatting.DARK_GRAY;
    private static final ChatFormatting PREFIX_TEXT = ChatFormatting.AQUA;
    private static final ChatFormatting LABEL = ChatFormatting.GRAY;
    private static final ChatFormatting PRIMARY = ChatFormatting.WHITE;
    private static final ChatFormatting ACCENT = ChatFormatting.AQUA;
    private static final ChatFormatting POSITIVE = ChatFormatting.GREEN;
    private static final ChatFormatting WARNING = ChatFormatting.YELLOW;
    private static final ChatFormatting DANGER = ChatFormatting.RED;
    private static final ChatFormatting MUTED = ChatFormatting.DARK_GRAY;

    private SchemMessages() {
    }

    public static MutableComponent prefix() {
        return Component.literal("[")
            .withStyle(PREFIX_BRACKET)
            .append(Component.literal("FrameShift").withStyle(PREFIX_TEXT))
            .append(Component.literal("] ").withStyle(PREFIX_BRACKET));
    }

    public static MutableComponent info(String message) {
        return prefix().append(Component.literal(message).withStyle(PRIMARY));
    }

    public static MutableComponent success(String message) {
        return prefix().append(Component.literal(message).withStyle(POSITIVE));
    }

    public static MutableComponent warning(String message) {
        return prefix().append(Component.literal(message).withStyle(WARNING));
    }

    public static MutableComponent error(String summary, String detail) {
        return prefix()
            .append(Component.literal(summary).withStyle(DANGER))
            .append(Component.literal(detail).withStyle(ChatFormatting.GRAY));
    }

    public static MutableComponent listEntry(String name, String dimensions, String fileSize) {
        return prefix()
            .append(Component.literal(name).withStyle(ACCENT))
            .append(Component.literal("  ").withStyle(MUTED))
            .append(Component.literal(dimensions).withStyle(PRIMARY))
            .append(Component.literal("  ").withStyle(MUTED))
            .append(Component.literal(fileSize).withStyle(WARNING));
    }

    public static MutableComponent field(String label, String value, ChatFormatting valueColor) {
        return prefix()
            .append(Component.literal(label + ": ").withStyle(LABEL))
            .append(Component.literal(value).withStyle(valueColor));
    }

    public static MutableComponent mutedField(String label, int value, int highlightValue, ChatFormatting highlightColor) {
        ChatFormatting valueColor = value == highlightValue ? MUTED : highlightColor;
        boolean italic = value == highlightValue;
        MutableComponent valueComponent = Component.literal(value == highlightValue ? "unknown" : Integer.toString(value))
            .withStyle(valueColor);
        if (italic) {
            valueComponent.withStyle(ChatFormatting.ITALIC);
        }
        return prefix()
            .append(Component.literal(label + ": ").withStyle(LABEL))
            .append(valueComponent);
    }

    public static MutableComponent counts(int skipped, int failed) {
        return prefix()
            .append(Component.literal("Skipped: ").withStyle(LABEL))
            .append(Component.literal(Integer.toString(skipped)).withStyle(PRIMARY))
            .append(Component.literal("  Failed: ").withStyle(LABEL))
            .append(Component.literal(Integer.toString(failed)).withStyle(failed > 0 ? DANGER : PRIMARY));
    }

    public static MutableComponent nextPage(String cursor) {
        return prefix()
            .append(Component.literal("More results available. ").withStyle(WARNING))
            .append(Component.literal("Use ").withStyle(LABEL))
            .append(Component.literal("/schem list ").withStyle(PRIMARY))
            .append(Component.literal(cursor).withStyle(ACCENT));
    }
}
