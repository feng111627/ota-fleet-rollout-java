package com.github.feng111627.otafleetrolloutjava.rollout;

import com.github.feng111627.otafleetrolloutjava.model.Device;

/**
 * 设备版本恢复操作。抽象出来便于模拟远程恢复报错/超时；
 * 默认实现 {@link LocalRestoreOperator} 直接在内存里写回原版本，全程本地可跑。
 */
public interface DeviceRestoreOperator {

    /**
     * 将设备恢复到目标（原）版本。
     *
     * @return true 恢复成功；false 表示恢复失败（报错/超时/中断），需人工介入
     */
    boolean restore(Device device, String targetVersion);
}
