package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.BusinessException;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ReferenceService {

    private final DeviceMapper deviceMapper;
    private final ColdBoxMapper coldBoxMapper;

    public Device requireDeviceByCode(String code) {
        if (code == null || code.isBlank()) {
            throw new BusinessException("deviceCode 不能为空");
        }
        Device device = deviceMapper.selectOne(Wrappers.<Device>lambdaQuery()
                .eq(Device::getDeviceCode, code.trim()));
        if (device == null) {
            throw new BusinessException(404, "设备不存在: " + code);
        }
        return device;
    }

    public Device requireDevice(Long id, String code) {
        if (code != null && !code.isBlank()) {
            return requireDeviceByCode(code);
        }
        if (id == null) {
            throw new BusinessException("必须提供 deviceCode 或 deviceId");
        }
        Device device = deviceMapper.selectById(id);
        if (device == null) {
            throw new BusinessException(404, "设备不存在: id=" + id);
        }
        return device;
    }

    public ColdBox requireBoxByCode(String code) {
        ColdBox box = coldBoxMapper.selectOne(Wrappers.<ColdBox>lambdaQuery()
                .eq(ColdBox::getBoxCode, code.trim()));
        if (box == null) {
            throw new BusinessException(404, "冷链箱不存在: " + code);
        }
        return box;
    }

    /** boxCode 优先；否则取设备当前绑定箱体 */
    public ColdBox resolveBox(Device device, String boxCode) {
        if (boxCode != null && !boxCode.isBlank()) {
            ColdBox box = requireBoxByCode(boxCode);
            if (box.getDeviceId() != null && !box.getDeviceId().equals(device.getId())) {
                throw new BusinessException("箱体 " + box.getBoxCode() + " 未绑定到设备 " + device.getDeviceCode());
            }
            return box;
        }
        if (device.getId() == null) {
            throw new BusinessException("设备未绑定箱体且未提供 boxCode");
        }
        ColdBox box = coldBoxMapper.selectOne(Wrappers.<ColdBox>lambdaQuery()
                .eq(ColdBox::getDeviceId, device.getId())
                .last("limit 1"));
        if (box == null) {
            throw new BusinessException(404, "设备 " + device.getDeviceCode() + " 未绑定冷链箱");
        }
        return box;
    }
}
