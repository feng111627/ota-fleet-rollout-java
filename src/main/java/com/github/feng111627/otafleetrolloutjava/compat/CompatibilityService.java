package com.github.feng111627.otafleetrolloutjava.compat;

import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;

import java.util.Map;

/**
 * 设备与固件包兼容性校验。
 *
 * <p>判定顺序固定，拒绝原因互斥可区分：
 * <ol>
 *   <li>{@link RejectReason#MODEL_MISMATCH} 型号不一致；</li>
 *   <li>{@link RejectReason#CURRENT_VERSION_TOO_LOW} 设备当前版本低于固件要求的最低可升级版本；</li>
 *   <li>{@link RejectReason#DEPENDENCY_NOT_SATISFIED} 设备属性不满足固件依赖条件。</li>
 * </ol>
 * 任一条件满足即拒绝，detail 中记录实际值与期望值，作为判定依据。
 */
public class CompatibilityService {

    /**
     * 校验设备能否升级到目标固件包。
     */
    public CompatibilityResult check(Device device, FirmwarePackage firmware) {
        // 1. 型号
        if (!device.getModel().equals(firmware.getTargetModel())) {
            return CompatibilityResult.reject(RejectReason.MODEL_MISMATCH,
                    "device model='" + device.getModel()
                            + "' does not match firmware targetModel='" + firmware.getTargetModel() + "'");
        }

        // 2. 最低可升级版本
        String minFrom = firmware.getMinFromVersion();
        if (minFrom != null && !minFrom.isBlank()) {
            String current = device.getCurrentVersion();
            if (current == null || VersionComparator.isLessThan(current, minFrom)) {
                return CompatibilityResult.reject(RejectReason.CURRENT_VERSION_TOO_LOW,
                        "device currentVersion='" + current
                                + "' is below firmware minFromVersion='" + minFrom + "'");
            }
        }

        // 3. 依赖属性：固件要求的每个属性键，设备必须存在且值相等
        for (Map.Entry<String, String> required : firmware.getRequiredAttributes().entrySet()) {
            String key = required.getKey();
            String expected = required.getValue();
            String actual = device.getAttributes().get(key);
            if (actual == null) {
                return CompatibilityResult.reject(RejectReason.DEPENDENCY_NOT_SATISFIED,
                        "missing required attribute '" + key + "' (expected='" + expected + "')");
            }
            if (!expected.equals(actual)) {
                return CompatibilityResult.reject(RejectReason.DEPENDENCY_NOT_SATISFIED,
                        "attribute '" + key + "'='" + actual + "' does not satisfy required='" + expected + "'");
            }
        }

        return CompatibilityResult.ok();
    }
}
