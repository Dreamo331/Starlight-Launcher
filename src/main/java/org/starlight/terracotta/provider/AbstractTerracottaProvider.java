/** (C) Copyright 2026 Starlight. All rights reserved. */
package org.starlight.terracotta.provider;

import javafx.beans.value.ObservableDoubleValue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CancellationException;

public abstract class AbstractTerracottaProvider {
    public enum Status {
        NOT_EXIST,
        LEGACY_VERSION,
        READY
    }

    public interface DownloadContext {
        void bindProgress(ObservableDoubleValue progress);
        void checkCancellation() throws CancellationException;
    }

    protected final TerracottaBundleStub bundle;

    protected AbstractTerracottaProvider(TerracottaBundleStub bundle) {
        this.bundle = bundle;
    }

    public Status status() throws IOException {
        return bundle.status();
    }

    public Path download(DownloadContext progress) throws IOException {
        return bundle.download(progress);
    }

    public void install(Path pkg) throws IOException {
        bundle.install(pkg);
    }

    public abstract List<String> ofCommandLine(Path portTransfer);
}
