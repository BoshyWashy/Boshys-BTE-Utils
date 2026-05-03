package com.boshys.bteutils.keybind;

import net.minecraft.client.util.InputUtil;

public enum Keybind {

    TPLL("tpll", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode()),
    ADD_MARKER("addmarker", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode()),
    CLEAR_MARKERS("clearmarkers", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode()),
    SELECT_MARKER("selectmarker", InputUtil.Type.MOUSE, InputUtil.GLFW_MOUSE_BUTTON_RIGHT),
    DELETE_MARKER("deletemarker", InputUtil.Type.KEYSYM, InputUtil.GLFW_KEY_DELETE),
    TOGGLE_OVERLAY_MARKERS("toggleoverlaymarkers", InputUtil.Type.KEYSYM, InputUtil.UNKNOWN_KEY.getCode());

    public final String name;
    public final InputUtil.Type type;
    public final int defaultKey;

    Keybind(String name, InputUtil.Type type, int defaultKey) {
        this.name = name;
        this.type = type;
        this.defaultKey = defaultKey;
    }
}
