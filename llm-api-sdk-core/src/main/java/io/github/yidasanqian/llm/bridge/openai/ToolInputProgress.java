package io.github.yidasanqian.llm.bridge.openai;

import io.github.yidasanqian.llm.error.ProtocolConversionException;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Tracks a streamed prefix without retaining its contents. Hash UTF-16 units so delta boundaries are irrelevant. */
final class ToolInputProgress {
    private MessageDigest digest = sha256();
    private byte[] completedDigest;
    private final byte[] buffer = new byte[1024];
    private long length;

    void append(String delta) {
        if (completedDigest != null) {
            throw ProtocolConversionException.of("Tool input delta received after its final snapshot", 502);
        }
        update(digest, delta, delta.length(), buffer);
        length = Math.addExact(length, delta.length());
    }

    /** Null means the final snapshot disagrees with the prefix already delivered to the client. */
    String missingSuffix(String snapshot) {
        if (completedDigest == null) {
            completedDigest = digest.digest();
            digest = null;
        }
        if (snapshot.length() < length) return null;
        int prefixLength = (int) length;
        MessageDigest prefix = sha256();
        update(prefix, snapshot, prefixLength, buffer);
        byte[] expected = prefix.digest();
        if (!MessageDigest.isEqual(expected, completedDigest)) return null;
        update(prefix, snapshot, snapshot.length(), buffer);
        completedDigest = prefix.digest();
        length = snapshot.length();
        return snapshot.substring(prefixLength);
    }

    private static void update(MessageDigest digest, String text, int end, byte[] buffer) {
        int size = 0;
        for (int i = 0; i < end; i++) {
            char value = text.charAt(i);
            buffer[size++] = (byte) (value >>> 8);
            buffer[size++] = (byte) value;
            if (size == buffer.length) {
                digest.update(buffer);
                size = 0;
            }
        }
        digest.update(buffer, 0, size);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Java runtime does not provide SHA-256", impossible);
        }
    }
}
