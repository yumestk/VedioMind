package com.example.server.security;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class AuthCrypto {
    private static final SecureRandom RANDOM = new SecureRandom();
    private final byte[] material;

    public AuthCrypto(Path path) {
        try {
            material = Base64.getDecoder().decode(Files.readString(path).trim());
            if (material.length != 64) throw new IllegalArgumentException();
        } catch (Exception ex) {
            throw new IllegalStateException(
                    "Auth key missing or invalid; run scripts/auth-admin.sh init-key first");
        }
    }

    public SecretKey signingKey() {
        return new SecretKeySpec(Arrays.copyOfRange(material, 0, 32), "HmacSHA256");
    }

    public static String randomToken() {
        byte[] b = new byte[32];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    public static String hash(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    public String encrypt(String value, String context) {
        try {
            byte[] iv = new byte[12];
            RANDOM.nextBytes(iv);
            Cipher cipher = cipher(Cipher.ENCRYPT_MODE, iv, context);
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ciphertext, 0, out, iv.length, ciphertext.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to protect refresh receipt", ex);
        }
    }

    public String decrypt(String value, String context) {
        try {
            byte[] b = Base64.getDecoder().decode(value);
            return new String(
                    cipher(Cipher.DECRYPT_MODE, Arrays.copyOfRange(b, 0, 12), context)
                            .doFinal(Arrays.copyOfRange(b, 12, b.length)),
                    StandardCharsets.UTF_8);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to recover refresh receipt", ex);
        }
    }

    private Cipher cipher(int mode, byte[] iv, String context) throws Exception {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(
                mode,
                new SecretKeySpec(Arrays.copyOfRange(material, 32, 64), "AES"),
                new GCMParameterSpec(128, iv));
        c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
        return c;
    }
}
