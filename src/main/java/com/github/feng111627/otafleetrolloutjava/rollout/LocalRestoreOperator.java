package com.github.feng111627.otafleetrolloutjava.rollout;

import com.github.feng111627.otafleetrolloutjava.model.Device;

/**
 * 本地恢复实现：把设备当前版本直接写回建批时快照的原版本，不依赖任何外部服务。
 */
public class LocalRestoreOperator implements DeviceRestoreOperator {

    @Override
    public boolean restore(Device device, String targetVersion) {
        device.setCurrentVersion(targetVersion);
        return true;
    }
}
