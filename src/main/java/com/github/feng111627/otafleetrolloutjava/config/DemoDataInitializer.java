package com.github.feng111627.otafleetrolloutjava.config;

import com.github.feng111627.otafleetrolloutjava.api.RegisterDeviceRequest;
import com.github.feng111627.otafleetrolloutjava.api.RegisterFirmwareRequest;
import com.github.feng111627.otafleetrolloutjava.service.DeviceService;
import com.github.feng111627.otafleetrolloutjava.service.FirmwareService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** 本地启动时灌入演示数据，全部为内存数据，不依赖任何外部服务。 */
@Configuration
public class DemoDataInitializer {
    private static final Logger log = LoggerFactory.getLogger(DemoDataInitializer.class);

    @Bean
    public ApplicationRunner demoData(DeviceService deviceService, FirmwareService firmwareService) {
        return args -> {
            firmwareService.register(new RegisterFirmwareRequest(
                    "fw-sensor-2.0.0", "2.0.0", "model-a", "1.0.0",
                    List.of("secure-boot")));
            deviceService.register(new RegisterDeviceRequest(
                    "dev-a1", "model-a", "1.2.0", List.of("secure-boot")));
            deviceService.register(new RegisterDeviceRequest(
                    "dev-a2", "model-a", "0.9.0", List.of("secure-boot"))); // 版本过低
            deviceService.register(new RegisterDeviceRequest(
                    "dev-b1", "model-b", "1.0.0", List.of("secure-boot"))); // 型号不符
            deviceService.register(new RegisterDeviceRequest(
                    "dev-a3", "model-a", "1.1.0", List.of()));              // 缺依赖能力
            log.info("[演示数据] 已登记 1 个固件包与 4 台设备（其中 3 台用于演示不同兼容性拒绝原因）");
        };
    }
}
