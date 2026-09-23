/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta.profile;

import com.google.gson.annotations.SerializedName;

public enum ProfileKind {
    @SerializedName("HOST")
    HOST,
    @SerializedName("LOCAL")
    LOCAL,
    @SerializedName("GUEST")
    GUEST
}
