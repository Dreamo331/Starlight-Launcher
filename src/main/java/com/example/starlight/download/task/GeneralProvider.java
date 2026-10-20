/** (C) Copyright 2026 Starlight. All rights reserved. */
package com.example.starlight.download.task;

import com.example.starlight.download.engine.TerracottaBundleStub;

import java.nio.file.Path;
import java.util.List;

public final class GeneralProvider extends AbstractTerracottaProvider {
    private final Path executable;

    public GeneralProvider(TerracottaBundleStub bundle, Path executable) {
        super(bundle);
        this.executable = executable;
    }

    @Override
    public List<String> ofCommandLine(Path portTransfer) {
        return List.of(executable.toString(), "--hmcl", portTransfer.toString());
    }
}
