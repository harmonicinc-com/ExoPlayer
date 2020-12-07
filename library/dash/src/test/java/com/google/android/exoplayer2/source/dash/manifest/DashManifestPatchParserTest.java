package com.google.android.exoplayer2.source.dash.manifest;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.exoplayer2.testutil.TestUtil;
import com.google.android.exoplayer2.util.Util;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.IOException;
import static com.google.common.truth.Truth.assertThat;

/** Unit tests for {@link DashManifestPatchParser}. */
@RunWith(AndroidJUnit4.class)
public class DashManifestPatchParserTest {
    private static final String SAMPLE_MPD_PATCH = "manifest_patch/mpd_patch";
    private static final String SAMPLE_MPD_PATCH_ADD_SEGMENTS = "manifest_patch/mpd_patch_add_segments";
    private static final String SAMPLE_MPD_PATCH_ADD_PERIOD = "manifest_patch/mpd_patch_add_period";
    private static final String SAMPLE_MPD_PATCH_ADD_ATTRIBUTE = "manifest_patch/mpd_patch_add_attribute";
    private static final String SAMPLE_MPD_PATCH_REPLACE_ATTRIBUTE = "manifest_patch/mpd_patch_replace_attribute";
    private static final String SAMPLE_MPD_PATCH_REPLACE_NODE = "manifest_patch/mpd_patch_replace_node";
    private static final String XML_ADD_SEGMENTS = "manifest_patch/xml_add_segments";
    private static final String XML_ADD_PERIOD = "manifest_patch/xml_add_period";
    private static final String XML_REPLACE_NODE = "manifest_patch/xml_replace_node";

    @Test
    public void testParseSamplePatch() throws IOException {
        DashManifestPatchParser parser = new DashManifestPatchParser();
        DashManifestPatch patch = parser.parse(
                TestUtil.getString(ApplicationProvider.getApplicationContext(), SAMPLE_MPD_PATCH));

        assertThat(patch.mpdId).isEqualTo("mpd-id");
        assertThat(patch.originalPublishTimeMs).isEqualTo(Util.parseXsDateTime("2020-11-09T03:48:41.51468868Z"));
        assertThat(patch.publishTimeMs).isEqualTo(Util.parseXsDateTime("2020-11-09T03:48:43.514902582Z"));
        assertThat(patch.operations.size()).isEqualTo(6);
        assertThat(patch.operations.get(4).getXPath()).isEqualTo(
                "/MPD/Period[@id='81']/AdaptationSet/SegmentTemplate/SegmentTimeline/S/@r");
    }

    @Test
    public void testParseAddOperationWithSegments() throws IOException {
        DashManifestPatchParser parser = new DashManifestPatchParser();
        DashManifestPatch patch = parser.parse(
                TestUtil.getString(ApplicationProvider.getApplicationContext(), SAMPLE_MPD_PATCH_ADD_SEGMENTS));

        assertThat(patch.operations.size()).isEqualTo(1);
        DashManifestPatch.AddOperation operation = (DashManifestPatch.AddOperation)patch.operations.get(0);
        assertThat(operation.type).isNull();

        assertThat(DashManifestPatchParser.docToString(operation.element.getOwnerDocument()))
                .isEqualTo(TestUtil.getString(ApplicationProvider.getApplicationContext(), XML_ADD_SEGMENTS));
    }

    @Test
    public void testParseAddOperationWithPeriod() throws IOException {
        DashManifestPatchParser parser = new DashManifestPatchParser();
        DashManifestPatch patch = parser.parse(
                TestUtil.getString(ApplicationProvider.getApplicationContext(), SAMPLE_MPD_PATCH_ADD_PERIOD));

        assertThat(patch.operations.size()).isEqualTo(1);
        DashManifestPatch.AddOperation operation = (DashManifestPatch.AddOperation)patch.operations.get(0);
        assertThat(operation.type).isNull();

        assertThat(DashManifestPatchParser.docToString(operation.element.getOwnerDocument()))
                .isEqualTo(TestUtil.getString(ApplicationProvider.getApplicationContext(), XML_ADD_PERIOD));
    }

    @Test
    public void testParseAddAttribute() throws IOException {
        DashManifestPatchParser parser = new DashManifestPatchParser();
        DashManifestPatch patch = parser.parse(
                TestUtil.getString(ApplicationProvider.getApplicationContext(), SAMPLE_MPD_PATCH_ADD_ATTRIBUTE));

        assertThat(patch.operations.size()).isEqualTo(1);
        DashManifestPatch.AddOperation operation = (DashManifestPatch.AddOperation)patch.operations.get(0);
        assertThat(operation.type).isEqualTo("@r");
        assertThat(operation.content).isEqualTo("2");
        assertThat(operation.path).isEqualTo(
                "/MPD/Period[@id='alt-17-101']/AdaptationSet[2]/SegmentTemplate/SegmentTimeline/S");
    }

    @Test
    public void testParseReplaceAttribute() throws IOException {
        DashManifestPatchParser parser = new DashManifestPatchParser();
        DashManifestPatch patch = parser.parse(
                TestUtil.getString(ApplicationProvider.getApplicationContext(),
                                        SAMPLE_MPD_PATCH_REPLACE_ATTRIBUTE));

        assertThat(patch.operations.size()).isEqualTo(1);
        DashManifestPatch.ReplaceOperation operation = (DashManifestPatch.ReplaceOperation)patch.operations.get(0);
        if (operation.element == null) assertThat(operation.content).isNotNull();
        if (operation.content == null) assertThat(operation.element).isNotNull();
    }

    @Test
    public void testParseReplaceNode() throws IOException {
        DashManifestPatchParser parser = new DashManifestPatchParser();
        DashManifestPatch patch = parser.parse(
                TestUtil.getString(ApplicationProvider.getApplicationContext(), SAMPLE_MPD_PATCH_REPLACE_NODE));

        assertThat(patch.operations.size()).isEqualTo(1);
        DashManifestPatch.ReplaceOperation operation = (DashManifestPatch.ReplaceOperation)patch.operations.get(0);
        if (operation.element == null) assertThat(operation.content).isNotNull();
        if (operation.content == null) assertThat(operation.element).isNotNull();

        assertThat(DashManifestPatchParser.docToString(operation.element.getOwnerDocument()))
                .isEqualTo(TestUtil.getString(ApplicationProvider.getApplicationContext(), XML_REPLACE_NODE));
    }
}
