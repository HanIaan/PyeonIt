package com.pyeonit.push;

import java.util.Base64;
import org.bouncycastle.jce.ECNamedCurveTable;
import org.springframework.http.HttpStatus;
import com.pyeonit.config.ApiException;

final class PushValidation {
    static void keys(String p256dh, String auth) {
        try {
            byte[] key = decode(p256dh, 65);
            decode(auth, 16);
            if (key[0] != 4) throw new IllegalArgumentException();
            var point = ECNamedCurveTable.getParameterSpec("secp256r1").getCurve().decodePoint(key);
            if (point.isInfinity() || !point.isValid()) throw new IllegalArgumentException();
        } catch (RuntimeException e) { throw invalid(); }
    }

    private static byte[] decode(String value, int length) {
        if (value == null || value.length() > 100 || !value.matches("[A-Za-z0-9_-]+={0,2}"))
            throw new IllegalArgumentException();
        byte[] bytes = Base64.getUrlDecoder().decode(value);
        if (bytes.length != length) throw new IllegalArgumentException();
        return bytes;
    }
    static ApiException invalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "푸시 구독 입력값을 확인해 주세요");
    }
}
