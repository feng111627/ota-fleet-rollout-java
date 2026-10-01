package com.github.feng111627.otafleetrolloutjava.service;

import com.github.feng111627.otafleetrolloutjava.model.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.RejectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashSet;
import java.util.Set;

/**
 * 兼容性校验。拒绝原因严格区分：
 * 1) MODEL_MISMATCH   型号不符；
 * 2) VERSION_TOO_LOW  当前版本低于固件要求的最低可升级版本；
 * 3) DEPENDENCY_MISSING 缺少依赖能力。
 * 按上述顺序短路判定，保证每类拒绝原因互不混淆。
 */
@Service
public class CompatibilityService {
    private static final Logger log = LoggerFactory.getLogger(CompatibilityService.class);

    public CompatibilityResult check(Device device, FirmwarePackage firmware) {
        log.info("[兼容性校验] deviceId={}, deviceModel={}, deviceVersion={}, deviceCaps={} | firmwareId={}, targetModel={}, minVersion={}, requiredCaps={}",
                device.getId(), device.getModel().getCode(), device.getCurrentVersion(), device.getCapabilities(),
                firmware.getId(), firmware.getTargetModel().getCode(),
                firmware.getMinApplicableVersion() == null ? "无下限" : firmware.getMinApplicableVersion(),
                firmware.getRequiredCapabilities());

        // 规则1：型号必须一致
        if (device.getModel() != firmware.getTargetModel()) {
            String detail = "设备型号=" + device.getModel().getCode()
                    + "，固件目标型号=" + firmware.getTargetModel().getCode();
            log.info("[兼容性校验] deviceId={} => 拒绝 MODEL_MISMATCH，依据：{}", device.getId(), detail);
            return CompatibilityResult.reject(RejectReason.MODEL_MISMATCH, detail);
        }

        // 规则2：当前版本不得低于最低可升级版本
        if (firmware.getMinApplicableVersion() != null
                && device.getCurrentVersion().compareTo(firmware.getMinApplicableVersion()) < 0) {
            String detail = "设备当前版本=" + device.getCurrentVersion()
                    + "，最低要求版本=" + firmware.getMinApplicableVersion();
            log.info("[兼容性校验] deviceId={} => 拒绝 VERSION_TOO_LOW，依据：{}", device.getId(), detail);
            return CompatibilityResult.reject(RejectReason.VERSION_TOO_LOW, detail);
        }

        // 规则3：依赖能力必须全部具备
        if (!firmware.getRequiredCapabilities().isEmpty()) {
            Set<String> missing = new HashSet<>(firmware.getRequiredCapabilities());
            missing.removeAll(device.getCapabilities());
            if (!missing.isEmpty()) {
                String detail = "缺少依赖能力=" + missing + "，设备具备能力=" + device.getCapabilities();
                log.info("[兼容性校验] deviceId={} => 拒绝 DEPENDENCY_MISSING，依据：{}", device.getId(), detail);
                return CompatibilityResult.reject(RejectReason.DEPENDENCY_MISSING, detail);
            }
        }

        log.info("[兼容性校验] deviceId={}, firmwareId={} => 兼容放行", device.getId(), firmware.getId());
        return CompatibilityResult.ok();
    }
}
