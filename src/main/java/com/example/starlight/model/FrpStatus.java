/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.model;

/** FRP 隧道状态 */
public class FrpStatus {
    public final String state;
    public final int tunnelPort;
    public final String publicAddress;
    public FrpStatus(String state, int tunnelPort, String publicAddress) {
        this.state = state;
        this.tunnelPort = tunnelPort;
        this.publicAddress = publicAddress;
    }
}
