package com.google.android.exoplayer2.source.dash.manifest;

import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.gson.Gson;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xml.sax.SAXException;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

/** Unit tests for {@link DocumentToManifestConverter}. */
@RunWith(AndroidJUnit4.class)
public class DocumentToManifestConverterTest {
    private static final String MPD_WITH_PATCH = "manifest_patch/mpd_with_patch_location";
    private static final String MPD_WITH_MULTI_PERIOD_SCTE35 = "manifest_patch/mpd_multi_period_scte35";

    @Test
    public void testManifestWithPatchLocation() throws IOException, SAXException {
        String baseURL = "https://example.com/test.mpd";
        DashManifestParser parser = new DashManifestParser();
        DashManifest manifest = parser.parse(
                Uri.parse(baseURL),
                TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), MPD_WITH_PATCH));

        DashManifestPatchParser patchParser = new DashManifestPatchParser();
        patchParser.setManifestString(TestUtil.getString(ApplicationProvider.getApplicationContext(), MPD_WITH_PATCH));
        DashManifest convertedManifest = DocumentToManifestConverter.convert(patchParser.getDocument(), baseURL);

        Gson gson = new Gson();
        assertThat(gson.toJson(manifest)).isEqualTo(gson.toJson(convertedManifest));
    }

    @Test
    public void testManifestWithMultiPeriodScte35() throws IOException, SAXException {
        String baseURL = "https://example.com/test.mpd";
        DashManifestParser parser = new DashManifestParser();
        DashManifest manifest = parser.parse(
                Uri.parse(baseURL),
                TestUtil.getInputStream(ApplicationProvider.getApplicationContext(), MPD_WITH_MULTI_PERIOD_SCTE35));

        DashManifestPatchParser patchParser = new DashManifestPatchParser();
        patchParser.setManifestString(TestUtil.getString(ApplicationProvider.getApplicationContext(), MPD_WITH_MULTI_PERIOD_SCTE35));
        DashManifest convertedManifest = DocumentToManifestConverter.convert(patchParser.getDocument(), baseURL);

        Gson gson = new Gson();
        assertThat(gson.toJson(manifest)).isEqualTo(gson.toJson(convertedManifest));
    }
}
