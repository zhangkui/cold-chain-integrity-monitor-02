package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.BusinessException;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.dto.SamplePoint;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.mapper.ColdBoxMapper;
import com.coldchain.mapper.DeviceMapper;
import com.coldchain.mapper.TemperatureSampleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class SampleQueryService {

    private final TemperatureSampleMapper sampleMapper;
    private final ColdBoxMapper boxMapper;
    private final DeviceMapper deviceMapper;

    /**
     * 箱体温差采样序列。
     * @param timezone UTC（默认）或 LOCAL（按设备时区渲染，跨日曲线由前端按时间轴自然展开）
     */
    public List<SamplePoint> boxSamples(Long boxId, String from, String to, String timezone) {
        ColdBox box = boxMapper.selectById(boxId);
        if (box == null) {
            throw new BusinessException(404, "冷链箱不存在: " + boxId);
        }
        Device device = box.getDeviceId() == null ? null : deviceMapper.selectById(box.getDeviceId());
        ZoneId zone = "LOCAL".equalsIgnoreCase(timezone) && device != null
                ? ZoneId.of(device.getTimezone()) : ZoneOffset.UTC;

        var query = Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getBoxId, boxId)
                .orderByAsc(TemperatureSample::getSampleTimeUtc);
        if (from != null && !from.isBlank()) {
            query.ge(TemperatureSample::getSampleTimeUtc,
                    TimeUtils.toUtc(TimeUtils.parseToInstant(from, null)));
        }
        if (to != null && !to.isBlank()) {
            query.le(TemperatureSample::getSampleTimeUtc,
                    TimeUtils.toUtc(TimeUtils.parseToInstant(to, null)));
        }
        List<TemperatureSample> samples = sampleMapper.selectList(query);

        List<SamplePoint> points = new ArrayList<>(samples.size());
        TemperatureSample prev = null;
        for (TemperatureSample s : samples) {
            SamplePoint p = new SamplePoint();
            p.setId(s.getId());
            p.setSeq(s.getSeq());
            p.setTimeUtc(s.getSampleTimeUtc());
            Instant instant = s.getSampleTimeUtc().toInstant(ZoneOffset.UTC);
            p.setTimeLocal(java.time.LocalDateTime.ofInstant(instant, zone));
            p.setTemperature(s.getTemperatureC());
            p.setSource(s.getSource());
            p.setContentHash(s.getContentHash());
            p.setPrevHash(s.getPrevHash());
            p.setChainHash(s.getChainHash());
            if (prev != null) {
                p.setGapSeconds(Duration.between(prev.getSampleTimeUtc(), s.getSampleTimeUtc()).getSeconds());
            }
            BigDecimal t = s.getTemperatureC();
            p.setInRange(t.compareTo(box.getTempMin()) >= 0 && t.compareTo(box.getTempMax()) <= 0);
            points.add(p);
            prev = s;
        }
        return points;
    }
}
