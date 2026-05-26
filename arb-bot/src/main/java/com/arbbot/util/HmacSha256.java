package com.arbbot.util;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

public final class HmacSha256 {

  private HmacSha256() {}

  public static String hex(String secret, String data) {
    return HexFormat.of().formatHex(compute(secret, data));
  }

  public static String base64(String secret, String data) {
    return Base64.getEncoder().encodeToString(compute(secret, data));
  }

  private static byte[] compute(String secret, String data) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new RuntimeException("HMAC-SHA256 failed", e);
    }
  }
}
