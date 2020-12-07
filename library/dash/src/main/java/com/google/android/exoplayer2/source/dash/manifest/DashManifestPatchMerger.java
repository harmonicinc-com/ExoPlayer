package com.google.android.exoplayer2.source.dash.manifest;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.Log;

import org.w3c.dom.Document;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

public class DashManifestPatchMerger extends DefaultHandler
        implements ParsingLoadable.Parser<DashManifest> {

    private static final String TAG = "MpdPatchMerger";
    private final DashManifestPatchParser parser;
    private final DocumentBuilder documentBuilder;
    @Nullable private Document document;
    @Nullable private String manifestString;

    public DashManifestPatchMerger() {
        this(new DashManifestPatchParser());
    }

    public DashManifestPatchMerger(DashManifestPatchParser parser) {
        this.parser = parser;
        try {
            DocumentBuilderFactory builder = DocumentBuilderFactory.newInstance();
            documentBuilder = builder.newDocumentBuilder();
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Couldn't create Document instance", e);
        }
    }

    public Document getDocument() {
        return document;
    }

    public void setManifestString(String manifestString) throws IOException, SAXException {
        if (this.manifestString != null && this.manifestString.equals(manifestString)) {
            return;
        }
        this.manifestString = manifestString;
        InputStream stream = new ByteArrayInputStream(manifestString.getBytes());
        document = documentBuilder.parse(stream);
    }

    @Override
    public DashManifest parse(Uri uri, InputStream inputStream) throws IOException {
        String manifestPatchString = DashUtil.inputStreamToString(inputStream, "UTF-8");

        DashManifestPatch patch = parser.parse(manifestPatchString);

        if (!patch.applyPatch(document)) {
            Log.d(TAG, "Failed to apply manifest patch: " + manifestPatchString);
            throw new ParserException("Failed to apply manifest patch");
        }

        Log.d(TAG, "Patch success, operation count: " + patch.operations.size());
        return DocumentToManifestConverter.convert(document, uri.toString());
    }

}
