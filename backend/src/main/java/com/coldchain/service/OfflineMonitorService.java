package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 传感器在线状态巡检：最后采样时间距今超过所绑定箱体 offlineSeconds 即标记 OFFLINE。
 * 采样间隔级别的 OFFLINE 异常由 {@link DetectionService} 基于相邻采样间隔识别。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OfflineMonitorService {

    private final DeviceMapper deviceMapper;
    private final ColdBoxMapper boxMapper;

    @Scheduled(fixedDelayString = "60000", initialDelayString = "30000")
    public void refreshStatus() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        List<Device> devices = deviceMapper.selectList(null);
        for (Device device : devices) {
            if (device.getLastSampleTimeUtc() == null) {
                continue;
            }
            ColdBox box = boxMapper.selectOne(Wrappers.<ColdBox>lambdaQuery()
                    .eq(ColdBox::getDeviceId, device.getId()).last("limit 1"));
            int threshold = box == null ? 900 : box.getOfflineSeconds();
            long silence = Duration.between(device.getLastSampleTimeUtc(), now).getSeconds();
            String expected = silence > threshold ? "OFFLINE" : "ONLINE";
            if (!expected.equals(device.getStatus())) {
                Device patch = new Device();
                patch.setId(device.getId());
                patch.setStatus(expected);
                deviceMapper.updateById(patch);
                log.info("设备 {} 状态 -> {}（静默 {}s，阈值 {}s）", device.getDeviceCode(), expected, silence, threshold);
            }
        }
    }
}
