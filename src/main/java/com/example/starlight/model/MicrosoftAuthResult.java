/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/** 微软登录结果 */
public class MicrosoftAuthResult {
    public final String accessToken;
    public final String refreshToken;
    public final String uuid;
    public final String username;
    public final String deviceCode;
    public final String verificationUri;

    public MicrosoftAuthResult(String accessToken, String refreshToken, String uuid, String username) {
        this(accessToken, refreshToken, uuid, username, null, null);
    }

    public MicrosoftAuthResult(String accessToken, String refreshToken, String uuid, String username,
                                String deviceCode, String verificationUri) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.uuid = uuid;
        this.username = username;
        this.deviceCode = deviceCode;
        this.verificationUri = verificationUri;
    }
}
