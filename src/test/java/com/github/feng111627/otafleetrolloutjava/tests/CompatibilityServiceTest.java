package com.github.feng111627.otafleetrolloutjava.tests;

import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityResult;
import com.github.feng111627.otafleetrolloutjava.compat.CompatibilityService;
import com.github.feng111627.otafleetrolloutjava.compat.RejectReason;
import com.github.feng111627.otafleetrolloutjava.compat.VersionComparator;
import com.github.feng111627.otafleetrolloutjava.model.Device;
import com.github.feng111627.otafleetrolloutjava.model.FirmwarePackage;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 兼容性校验测试：验证三类拒绝原因可区分，以及最低版本比较规则。
 */
class CompatibilityServiceTest {

    private static final Logger log = LoggerFactory.getLogger(CompatibilityServiceTest.class);

    private final CompatibilityService service = new CompatibilityService();

    private FirmwarePackage firmware(String minFrom, Map<String, String> requires) {
        return new FirmwarePackage("fw-thermo-2.0", "THERMO-X1", "2.0.0", minFrom, requires);
    }

    private Device device(String model, String version, Map<String, String> attrs) {
        return new Device("dev-1", model, version, attrs);
    }

    @Test
    void shouldPassWhenModelVersionAndDependenciesAllMatch() {
        Device d = device("THERMO-X1", "1.5.0", Map.of("region", "cn", "hw", "revC"));
        FirmwarePackage fw = firmware("1.0.0", Map.of("region", "cn"));

        log.info("[CASE pass] input model={} version={} attrs={} | targetModel={} minFrom={} requires={}",
                d.getModel(), d.getCurrentVersion(), d.getAttributes(),
                fw.getTargetModel(), fw.getMinFromVersion(), fw.getRequiredAttributes());

        CompatibilityResult result = service.check(d, fw);
        log.info("[CASE pass] verdict compatible={} reason={} detail={}",
                result.compatible(), result.reason(), result.detail());

        assertTrue(result.compatible(), result.detail());
    }

    @Test
    void shouldRejectWithModelMismatch() {
        Device d = device("THERMO-X2", "1.5.0", Map.of());
        FirmwarePackage fw = firmware("1.0.0", Map.of());

        log.info("[CASE model] input deviceModel={} firmwareTargetModel={}",
                d.getModel(), fw.getTargetModel());

        CompatibilityResult result = service.check(d, fw);
        log.info("[CASE model] verdict compatible={} reason={} detail={}",
                result.compatible(), result.reason(), result.detail());

        assertFalse(result.compatible());
        assertEquals(RejectReason.MODEL_MISMATCH, result.reason());
    }

    @Test
    void shouldRejectWhenCurrentVersionBelowMinimum() {
        Device d = device("THERMO-X1", "0.9.3", Map.of());
        FirmwarePackage fw = firmware("1.0.0", Map.of());

        log.info("[CASE version] input currentVersion={} minFromVersion={}",
                d.getCurrentVersion(), fw.getMinFromVersion());

        CompatibilityResult result = service.check(d, fw);
        log.info("[CASE version] verdict compatible={} reason={} detail={}",
                result.compatible(), result.reason(), result.detail());

        assertFalse(result.compatible());
        assertEquals(RejectReason.CURRENT_VERSION_TOO_LOW, result.reason());
    }

    @Test
    void shouldTreatNumericSegmentsNumericallySo1100NotLowerThan190() {
        log.info("[CASE version-compare] compare 1.10.0 vs 1.9.0 => {}",
                VersionComparator.compare("1.10.0", "1.9.0"));
        assertTrue(VersionComparator.isLessThan("1.9.0", "1.10.0"));
        assertFalse(VersionComparator.isLessThan("1.10.0", "1.9.0"));
    }

    @Test
    void shouldRejectWhenDependencyAttributeMissing() {
        Device d = device("THERMO-X1", "1.5.0", Map.of("region", "cn"));
        FirmwarePackage fw = firmware("1.0.0", Map.of("region", "cn", "hw", "revC"));

        log.info("[CASE dep-missing] input attrs={} requires={}",
                d.getAttributes(), fw.getRequiredAttributes());

        CompatibilityResult result = service.check(d, fw);
        log.info("[CASE dep-missing] verdict compatible={} reason={} detail={}",
                result.compatible(), result.reason(), result.detail());

        assertFalse(result.compatible());
        assertEquals(RejectReason.DEPENDENCY_NOT_SATISFIED, result.reason());
    }

    @Test
    void shouldRejectWhenDependencyAttributeValueDiffers() {
        Device d = device("THERMO-X1", "1.5.0", Map.of("region", "eu"));
        FirmwarePackage fw = firmware("1.0.0", Map.of("region", "cn"));

        log.info("[CASE dep-value] input attrs={} requires={}",
                d.getAttributes(), fw.getRequiredAttributes());

        CompatibilityResult result = service.check(d, fw);
        log.info("[CASE dep-value] verdict compatible={} reason={} detail={}",
                result.compatible(), result.reason(), result.detail());

        assertFalse(result.compatible());
        assertEquals(RejectReason.DEPENDENCY_NOT_SATISFIED, result.reason());
    }
}
