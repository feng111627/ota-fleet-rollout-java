package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.model.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.DeviceModel;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import com.github.feng111627.otafleetrolloutjava.model.ModelVersion;
import com.github.feng111627.otafleetrolloutjava.model.RejectReason;
import com.github.feng111627.otafleetrolloutjava.service.CompatibilityService;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 兼容性校验测试：型号 / 最低版本 / 依赖能力三类拒绝原因必须可区分。 */
class CompatibilityServiceTest {
    private static final Logger log = LoggerFactory.getLogger(CompatibilityServiceTest.class);

    private final CompatibilityService service = new CompatibilityService();

    private FirmwarePackage firmware(DeviceModel model, String minVersion, Set<String> caps) {
        return new FirmwarePackage("fw-1", new ModelVersion(2, 0, 0), model,
                minVersion == null ? null : ModelVersion.parse(minVersion), caps);
    }

    private Device device(String id, DeviceModel model, String version, Set<String> caps) {
        return new Device(id, model, ModelVersion.parse(version), caps);
    }

    @Test
    void compatible_whenAllConditionsMatch() {
        Device d = device("d1", DeviceModel.MODEL_A, "1.5.0", Set.of("secure-boot"));
        FirmwarePackage fw = firmware(DeviceModel.MODEL_A, "1.0.0", Set.of("secure-boot"));

        CompatibilityResult result = service.check(d, fw);
        log.info("[测试输入] 设备=model-a/1.5.0/[secure-boot], 固件=model-a/min1.0.0/需要[secure-boot] => 判定={}",
                result.compatible());

        assertTrue(result.compatible());
        assertNull(result.reason());
    }

    @Test
    void reject_modelMismatch_distinctReason() {
        Device d = device("d2", DeviceModel.MODEL_B, "1.5.0", Set.of());
        FirmwarePackage fw = firmware(DeviceModel.MODEL_A, "1.0.0", Set.of());

        CompatibilityResult result = service.check(d, fw);
        log.info("[测试输入] 设备型号=model-b, 固件型号=model-a => 判定原因={}", result.reason());

        assertFalse(result.compatible());
        assertEquals(RejectReason.MODEL_MISMATCH, result.reason());
    }

    @Test
    void reject_versionTooLow_distinctReason() {
        Device d = device("d3", DeviceModel.MODEL_A, "0.9.0", Set.of());
        FirmwarePackage fw = firmware(DeviceModel.MODEL_A, "1.0.0", Set.of());

        CompatibilityResult result = service.check(d, fw);
        log.info("[测试输入] 设备版本=0.9.0, 最低要求=1.0.0 => 判定原因={}", result.reason());

        assertFalse(result.compatible());
        assertEquals(RejectReason.VERSION_TOO_LOW, result.reason());
    }

    @Test
    void reject_dependencyMissing_distinctReason() {
        Device d = device("d4", DeviceModel.MODEL_A, "1.5.0", Set.of("secure-boot"));
        FirmwarePackage fw = firmware(DeviceModel.MODEL_A, "1.0.0",
                Set.of("secure-boot", "delta-update"));

        CompatibilityResult result = service.check(d, fw);
        log.info("[测试输入] 设备能力=[secure-boot], 固件依赖=[secure-boot,delta-update] => 判定原因={}, 明细={}",
                result.reason(), result.detail());

        assertFalse(result.compatible());
        assertEquals(RejectReason.DEPENDENCY_MISSING, result.reason());
        assertTrue(result.detail().contains("delta-update"));
    }

    @Test
    void rejectReasons_areAllDistinct() {
        RejectReason model = service.check(
                device("d5", DeviceModel.MODEL_B, "1.0.0", Set.of()),
                firmware(DeviceModel.MODEL_A, null, Set.of())).reason();
        RejectReason version = service.check(
                device("d6", DeviceModel.MODEL_A, "0.1.0", Set.of()),
                firmware(DeviceModel.MODEL_A, "1.0.0", Set.of())).reason();
        RejectReason dependency = service.check(
                device("d7", DeviceModel.MODEL_A, "1.0.0", Set.of()),
                firmware(DeviceModel.MODEL_A, null, Set.of("x"))).reason();

        log.info("[测试输入] 三种不兼容场景 => 原因分别为 {}, {}, {}", model, version, dependency);
        assertEquals(RejectReason.MODEL_MISMATCH, model);
        assertEquals(RejectReason.VERSION_TOO_LOW, version);
        assertEquals(RejectReason.DEPENDENCY_MISSING, dependency);
    }
}
