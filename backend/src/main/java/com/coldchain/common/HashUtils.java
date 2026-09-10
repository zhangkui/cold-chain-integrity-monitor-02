package com.coldchain.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 哈希链工具。contentHash / chainHash 一旦写入不再变化，任何对历史载荷的篡改
 * 都会使后续环的 prevHash 校验失配。
 */
public final class HashUtils {
    public static final String GENESIS = "0".repeat(64);

    private HashUtils() {
    }

    public static String sha256(String input) {
        return sha256(input.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(input);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** 采样载荷哈希：deviceId|seq|utcIso|temperature（canonical，温度保留两位小数） */
    public static String contentHash(long deviceId, long seq, String utcIso, String temperature) {
        return sha256(deviceId + "|" + seq + "|" + utcIso + "|" + temperature);
    }

    public static String chainHash(String prevHash, String contentHash) {
        return sha256(prevHash + "|" + contentHash);
    }
}
