package com.google.android.exoplayer2.source.dash.manifest;

import androidx.annotation.Nullable;

/* A parsed PatchLocation element. */
public class PatchLocation {
    public final long ttl;
    public final String url;

    public PatchLocation(long ttl, String url) {
        this.ttl = ttl;
        this.url = url;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        PatchLocation other = (PatchLocation) obj;
        return this.ttl == other.ttl && this.url.equals(other.url);
    }

    @Override
    public int hashCode() {
        int result = 17;
        result = 31 * result + (int)ttl;
        result = 31 * result + url.hashCode();
        return result;
    }
}
