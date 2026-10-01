package com.github.feng111627.otafleetrolloutjava.model;

/** 设备型号枚举。 */
public enum DeviceModel {
    MODEL_A("model-a"),
    MODEL_B("model-b"),
    MODEL_X("model-x");

    private final String code;

    DeviceModel(String code) { this.code = code; }

    public String getCode() { return code; }

    public static DeviceModel fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("型号编码不能为空");
        }
        for (DeviceModel model : values()) {
            if (model.code.equals(code.trim())) {
                return model;
            }
        }
        throw new IllegalArgumentException("未知设备型号: " + code);
    }
}
