package com.coldchain.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.coldchain.common.HashUtils;
import com.coldchain.common.TimeUtils;
import com.coldchain.domain.entity.ColdBox;
import com.coldchain.domain.entity.Device;
import com.coldchain.domain.entity.TemperatureSample;
import com.coldchain.domain.enums.SampleSource;
import com.coldchain.mapper.TemperatureSampleMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 采样落库：Redis 设备锁包裹一个 REQUIRES_NEW 独立事务。
 *
 * 关键顺序：进入锁 → 开启事务 → 读取链尾/插入/必要时重链 → 事务提交 → 释放锁。
 * 锁在事务提交之后才释放，杜绝“锁已释放而数据对其它事务尚不可见”的窗口，
 * 保证并发上报时 prevHash 一定接到已提交的最新链尾。
 *
 * 每个采样一个独立事务，单行失败只回滚该行，不连累同一批次的其它行。
 */
@Component
@RequiredArgsConstructor
public class SampleChainWriter {

    private final TemperatureSampleMapper sampleMapper;
    private final RedisLockService lockService;
    private final AuditService auditService;
    private final PlatformTransactionManager txManager;

    /**
     * 幂等地写入一条采样并维护哈希链。
     * @return 新增采样 id；幂等命中（同 设备+序号 或 设备+时间+序号）返回 null
     */
    public Long writeLocked(Device device, ColdBox box, long seq, Instant instant,
                            BigDecimal temp, String source, String idemKey) {
        try (RedisLockService.AutoCloseableLock ignored =
                     lockService.tryLock("device-chain:" + device.getId())) {
            TransactionTemplate tx = new TransactionTemplate(txManager);
            tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            return tx.execute(status -> doInsert(device, box, seq, instant, temp, source, idemKey));
        } catch (DuplicateKeyException e) {
            // 并发下唯一约束兜底
            return null;
        }
    }

    private Long doInsert(Device device, ColdBox box, long seq, Instant instant,
                          BigDecimal temp, String source, String idemKey) {
        TemperatureSample existing = sampleMapper.selectOne(Wrappers.<TemperatureSample>lambdaQuery()
                .and(w -> w.eq(TemperatureSample::getIdemKey, idemKey)
                        .or(o -> o.eq(TemperatureSample::getDeviceId, device.getId())
                                .eq(TemperatureSample::getSeq, seq)))
                .last("limit 1"));
        if (existing != null) {
            return null;
        }

        TemperatureSample predecessor = sampleMapper.selectOne(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, device.getId())
                .lt(TemperatureSample::getSeq, seq)
                .orderByDesc(TemperatureSample::getSeq)
                .last("limit 1"));

        TemperatureSample sample = new TemperatureSample();
        sample.setDeviceId(device.getId());
        sample.setBoxId(box.getId());
        sample.setSeq(seq);
        sample.setSampleTimeUtc(TimeUtils.toUtc(instant));
        sample.setSampleTimeLocal(LocalDateTime.ofInstant(instant, ZoneId.of(device.getTimezone())));
        sample.setTemperatureC(temp);
        sample.setSource(source);
        String contentHash = HashUtils.contentHash(device.getId(), seq,
                TimeUtils.hashFormat(instant), temp.toPlainString());
        String prevHash = predecessor == null ? HashUtils.GENESIS : predecessor.getChainHash();
        sample.setContentHash(contentHash);
        sample.setPrevHash(prevHash);
        sample.setChainHash(HashUtils.chainHash(prevHash, contentHash));
        sample.setIdemKey(idemKey);
        sample.setReceivedAt(LocalDateTime.now(ZoneOffset.UTC));
        sampleMapper.insert(sample);

        // 无论实时还是补传，只要新采样后面还有更大 seq（非链尾插入），就衔接其后各环
        Long laterCount = sampleMapper.selectCount(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, device.getId())
                .gt(TemperatureSample::getSeq, seq));
        if (laterCount != null && laterCount > 0) {
            int relinked = relinkChainFrom(device.getId(), seq);
            auditService.log("SAMPLE", sample.getId(),
                    SampleSource.BACKFILL.name().equals(source) ? "BACKFILL_RELINK" : "OUT_OF_ORDER_RELINK",
                    java.util.Map.of("deviceId", device.getId(), "seq", seq,
                            "relinkedRows", relinked, "idemKey", idemKey));
        }
        return sample.getId();
    }

    /**
     * 从 fromSeq 起按 seq 顺序修正 prevHash/chainHash。
     * 老采样的 contentHash 直接取库中值，绝不重算——载荷指纹不变。
     */
    private int relinkChainFrom(Long deviceId, long fromSeq) {
        List<TemperatureSample> tail = sampleMapper.selectList(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, deviceId)
                .ge(TemperatureSample::getSeq, fromSeq)
                .orderByAsc(TemperatureSample::getSeq));
        TemperatureSample predecessor = sampleMapper.selectOne(Wrappers.<TemperatureSample>lambdaQuery()
                .eq(TemperatureSample::getDeviceId, deviceId)
                .lt(TemperatureSample::getSeq, fromSeq)
                .orderByDesc(TemperatureSample::getSeq)
                .last("limit 1"));
        String expectedPrev = predecessor == null ? HashUtils.GENESIS : predecessor.getChainHash();
        int updates = 0;
        for (TemperatureSample s : tail) {
            String expectedChain = HashUtils.chainHash(expectedPrev, s.getContentHash());
            if (!expectedPrev.equals(s.getPrevHash()) || !expectedChain.equals(s.getChainHash())) {
                TemperatureSample patch = new TemperatureSample();
                patch.setId(s.getId());
                patch.setPrevHash(expectedPrev);
                patch.setChainHash(expectedChain);
                sampleMapper.updateById(patch);
                updates++;
            }
            expectedPrev = expectedChain;
        }
        return updates;
    }
}
