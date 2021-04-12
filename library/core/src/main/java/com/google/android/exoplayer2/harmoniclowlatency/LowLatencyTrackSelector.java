package com.google.android.exoplayer2.harmoniclowlatency;

import com.google.android.exoplayer2.trackselection.AdaptiveTrackSelection.Factory;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;

import org.checkerframework.checker.nullness.qual.NonNull;

import java.util.concurrent.atomic.AtomicBoolean;

public final class LowLatencyTrackSelector extends DefaultTrackSelector {
    private final AtomicBoolean isAuto;

    public final boolean isAuto() {
        return this.isAuto.get();
    }

    public LowLatencyTrackSelector(Factory aTSF) {
        super(aTSF);
        this.isAuto = new AtomicBoolean(true);
    }

    public void setParameters(Parameters parameters) {
        super.setParameters(parameters);
        MappedTrackInfo info = super.getCurrentMappedTrackInfo();
        if (info != null) {
            this.isAuto.set(!super.getParameters().hasSelectionOverride(0, info.getTrackGroups(0)));
        }
    }

    public void setParameters(ParametersBuilder parametersBuilder) {
        super.setParameters(parametersBuilder);
        MappedTrackInfo info = super.getCurrentMappedTrackInfo();
        if (info != null) {
            this.isAuto.set(!super.getParameters().hasSelectionOverride(0, info.getTrackGroups(0)));
        }
    }

    public final void setAutoBitrateSwitchingParameters(Parameters parameters) {
        if (this.isAuto.get()) {
            super.setParameters(parameters);
        }
    }

    @Override
    @NonNull
    public Parameters getParameters() {
        Parameters rv = super.getParameters();
        if (this.isAuto.get()) {
            rv = rv.buildUpon().clearSelectionOverrides(0).build();
        }
        return rv;
    }
}
