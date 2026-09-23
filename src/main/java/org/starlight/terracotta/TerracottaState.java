/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta;

import com.google.gson.JsonParseException;
import com.google.gson.annotations.SerializedName;
import javafx.beans.property.ReadOnlyDoubleProperty;
import javafx.beans.property.ReadOnlyDoubleWrapper;
import javafx.beans.value.ObservableDoubleValue;
import org.starlight.terracotta.profile.TerracottaProfile;
import org.starlight.terracotta.provider.AbstractTerracottaProvider;
// HMCL dependencies removed: JsonSubtype, JsonType, TolerableValidationException, Validation

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract sealed class TerracottaState {
    protected TerracottaState() {
    }

    public boolean isUIFakeState() {
        return false;
    }

    public boolean isForkOf(TerracottaState state) {
        return false;
    }

    public static final class Bootstrap extends TerracottaState {
        static final Bootstrap INSTANCE = new Bootstrap();

        private Bootstrap() {
        }
    }

    public static final class Uninitialized extends TerracottaState {
        private final boolean hasLegacy;

        Uninitialized(boolean hasLegacy) {
            this.hasLegacy = hasLegacy;
        }

        public boolean hasLegacy() {
            return hasLegacy;
        }
    }

    public static final class Preparing extends TerracottaState implements AbstractTerracottaProvider.DownloadContext {
        private final ReadOnlyDoubleWrapper progress;

        private final AtomicBoolean hasInstallFence;

        Preparing(ReadOnlyDoubleWrapper progress, boolean hasInstallFence) {
            this.progress = progress;
            this.hasInstallFence = new AtomicBoolean(hasInstallFence);
        }

        public ReadOnlyDoubleProperty progressProperty() {
            return progress.getReadOnlyProperty();
        }

        public boolean requestInstallFence() {
            return hasInstallFence.compareAndSet(true, false);
        }

        public boolean hasInstallFence() {
            return hasInstallFence.get();
        }

        @Override
        public void bindProgress(ObservableDoubleValue progress) {
            this.progress.bind(progress);
        }

        @Override
        public void checkCancellation() {
            if (!hasInstallFence()) {
                throw new CancellationException("User has installed terracotta from local archives.");
            }
        }
    }

    public static final class Launching extends TerracottaState {
        Launching() {
        }
    }

    public static abstract sealed class PortSpecific extends TerracottaState {
        transient int port;

        protected PortSpecific(int port) {
            this.port = port;
        }
    }

    public static abstract sealed class Ready extends PortSpecific {
        @SerializedName("index")
        final int index;

        @SerializedName("state")
        private final String state;

        Ready(int port, int index, String state) {
            super(port);
            this.index = index;
            this.state = state;
        }

        @Override
        public boolean isUIFakeState() {
            return this.index == -1;
        }
    }

    public static final class Unknown extends PortSpecific {
        Unknown(int port) {
            super(port);
        }
    }

    public static final class Waiting extends Ready {
        Waiting(int port, int index, String state) {
            super(port, index, state);
        }
    }

    public static final class HostScanning extends Ready {
        HostScanning(int port, int index, String state) {
            super(port, index, state);
        }
    }

    public static final class HostStarting extends Ready {
        HostStarting(int port, int index, String state) {
            super(port, index, state);
        }
    }

    public static final class HostOK extends Ready {
        @SerializedName("room")
        private final String code;

        @SerializedName("profile_index")
        private final int profileIndex;

        @SerializedName("profiles")
        private final List<TerracottaProfile> profiles;

        HostOK(int port, int index, String state, String code, int profileIndex, List<TerracottaProfile> profiles) {
            super(port, index, state);
            this.code = code;
            this.profileIndex = profileIndex;
            this.profiles = profiles;
        }

        public String getCode() {
            return code;
        }

        public List<TerracottaProfile> getProfiles() {
            return profiles;
        }

        @Override
        public boolean isForkOf(TerracottaState state) {
            return state instanceof HostOK hostOK && this.index - hostOK.index <= profileIndex;
        }
    }

    public static final class GuestConnecting extends Ready {
        GuestConnecting(int port, int index, String state) {
            super(port, index, state);
        }
    }

    public static final class GuestStarting extends Ready {
        public enum Difficulty {
            UNKNOWN,
            EASIEST,
            SIMPLE,
            MEDIUM,
            TOUGH
        }

        @SerializedName("difficulty")
        private final Difficulty difficulty;

        GuestStarting(int port, int index, String state, Difficulty difficulty) {
            super(port, index, state);
            this.difficulty = difficulty;
        }

        public Difficulty getDifficulty() {
            return difficulty;
        }
    }

    public static final class GuestOK extends Ready {
        @SerializedName("url")
        private final String url;

        @SerializedName("profile_index")
        private final int profileIndex;

        @SerializedName("profiles")
        private final List<TerracottaProfile> profiles;

        GuestOK(int port, int index, String state, String url, int profileIndex, List<TerracottaProfile> profiles) {
            super(port, index, state);
            this.url = url;
            this.profileIndex = profileIndex;
            this.profiles = profiles;
        }

        public String getUrl() {
            return url;
        }

        public List<TerracottaProfile> getProfiles() {
            return profiles;
        }

        @Override
        public boolean isForkOf(TerracottaState state) {
            return state instanceof GuestOK guestOK && this.index - guestOK.index <= profileIndex;
        }
    }

    public static final class Exception extends Ready {
        public enum Type {
            PING_HOST_FAIL,
            PING_HOST_RST,
            GUEST_ET_CRASH,
            HOST_ET_CRASH,
            PING_SERVER_RST,
            SCAFFOLDING_INVALID_RESPONSE
        }

        private static final TerracottaState.Exception.Type[] LOOKUP = Type.values();

        @SerializedName("type")
        private final int type;

        Exception(int port, int index, String state, int type) {
            super(port, index, state);
            this.type = type;
        }

        public Type getType() {
            return LOOKUP[type];
        }
    }

    public static final class Fatal extends TerracottaState {
        public enum Type {
            OS,
            NETWORK,
            DOWNLOAD,
            INSTALL,
            TERRACOTTA,
            UNKNOWN;
        }

        private final Type type;

        public Fatal(Type type) {
            this.type = type;
        }

        public Type getType() {
            return type;
        }

        public boolean isRecoverable() {
            return this.type != Type.UNKNOWN;
        }
    }
}
