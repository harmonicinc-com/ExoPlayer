package com.google.android.exoplayer2.source.dash.manifest;

import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.source.dash.DashUtil;
import com.google.android.exoplayer2.upstream.ParsingLoadable;
import com.google.android.exoplayer2.util.Log;
import com.google.android.exoplayer2.util.XmlPullParserUtil;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;


public class DashManifestPatchParser extends DefaultHandler
        implements ParsingLoadable.Parser<DashManifestPatch> {
    private static final String TAG = "MpdPatchParser";
    private static final String UTF_8 = "UTF-8";
    private final XmlPullParserFactory xmlParserFactory;
    private Document document;
    private DocumentBuilder documentBuilder;
    @Nullable private Element root;
    @Nullable private String manifestString;
    @Nullable public String manifestPatchString;

    public DashManifestPatchParser() {
        try {
            xmlParserFactory = XmlPullParserFactory.newInstance();
            DocumentBuilderFactory builder = DocumentBuilderFactory.newInstance();
            documentBuilder = builder.newDocumentBuilder();
            document = documentBuilder.newDocument();
        } catch (XmlPullParserException e) {
            throw new RuntimeException("Couldn't create XmlPullParserFactory instance", e);
        } catch (ParserConfigurationException e) {
            throw new RuntimeException("Couldn't create Document instance", e);
        }
    }

    public void setManifestString(String manifestString) throws IOException, SAXException {
        if (this.manifestString != null && this.manifestString.equals(manifestString)) {
            return;
        }
        this.manifestString = manifestString;
        InputStream stream = new ByteArrayInputStream(manifestString.getBytes());
        document = documentBuilder.parse(stream);
        root = null;
    }

    public Document getDocument() {
        return document;
    }

    public String getDocumentString() {
        return docToString(document);
    }

    public static String docToString(Document doc) {
        try {
          Transformer transformer = TransformerFactory.newInstance().newTransformer();
          StringWriter writer = new StringWriter();
          transformer.transform(new DOMSource(doc), new StreamResult(writer));
          return writer.toString();
        } catch (TransformerException ex) {
          return "";
        }
    }

    @Override
    public DashManifestPatch parse(Uri uri, InputStream inputStream) throws IOException {
        try {
            this.manifestPatchString = DashUtil.inputStreamToString(inputStream, "UTF-8");
            InputStream textStream = new ByteArrayInputStream(this.manifestPatchString.getBytes());

            XmlPullParser xpp = xmlParserFactory.newPullParser();
            xpp.setInput(textStream, null);
            int eventType = xpp.next();
            if (eventType != XmlPullParser.START_TAG || !"Patch".equals(xpp.getName())) {
                throw new ParserException(
                        "inputStream does not contain a valid manifest patch");
            }
            return parseDashManifestPatch(xpp, uri.toString());
        } catch (XmlPullParserException e) {
            throw new ParserException(e);
        }
    }

    protected DashManifestPatch parseDashManifestPatch(XmlPullParser xpp,
                                                  String baseUrl) throws XmlPullParserException, IOException {
        String mpdId = DashManifestParser.parseString(xpp, "mpdId", "");
        long originalPublishTime = DashManifestParser.parseDateTime(xpp, "originalPublishTime", C.TIME_UNSET);
        long publishTime = DashManifestParser.parseDateTime(xpp, "publishTime", C.TIME_UNSET);

        if (root == null) {
            root = document.createElement("Patch");
            if (document.getDocumentElement() == null) {
                document.appendChild(root);
            }
        }
        while (root.hasChildNodes()) {
            root.removeChild(root.getFirstChild());
        }

        List<DashManifestPatch.Operation> operationList = new ArrayList<>();
        do {
            xpp.next();

            @Nullable
            DashManifestPatch.Operation operation = parseOperation(xpp);
            if (operation != null) {
                operationList.add(operation);
            } else {
                DashManifestParser.maybeSkipTag(xpp);
            }
        } while (!XmlPullParserUtil.isEndTag(xpp, "Patch"));

        return new DashManifestPatch(mpdId, originalPublishTime, publishTime, operationList);
    }

    @Nullable
    protected DashManifestPatch.Operation parseOperation(XmlPullParser xpp) throws XmlPullParserException {
        if (XmlPullParserUtil.isStartTag(xpp, "add")) {
            return parseAddOperation(xpp);
        } else if (XmlPullParserUtil.isStartTag(xpp, "replace")) {
            return parseReplaceOperation(xpp);
        } else if (XmlPullParserUtil.isStartTag(xpp, "remove")) {
            return parseRemoveOperation(xpp);
        }
        return null;
    }

    private DashManifestPatch.Operation parseAddOperation(XmlPullParser xpp) {
        String xpath = DashManifestParser.parseString(xpp, "sel", "");
        String pos = DashManifestParser.parseString(xpp, "pos", "");
        String type = DashManifestParser.parseString(xpp, "type", "");
        @Nullable Element element = null;
        if (type.length() < 1) {
            try {
                element = createElements(xpp, xpp.getName(), document);
                root.appendChild(element);
            } catch (Exception ex) {
                Log.w(TAG, "Failed to parse nodes of add operation", ex);
            }
        }
        @Nullable String content = null;
        if (element == null) {
            try {
                content = DashManifestParser.parseText(xpp, "add");
            } catch (Exception ex) {
                Log.w(TAG, "Failed to parse content of add operation", ex);
            }
        }
        return new DashManifestPatch.AddOperation(xpath, content,
                pos.length() > 0 ? pos : null,
                type.length() > 0 ? type : null,
                element);
    }

    public boolean isAttributeOperation(String path) {
        int index = path.lastIndexOf('/');
        return path.indexOf('@', index + 1) > 0;
    }

    private DashManifestPatch.Operation parseReplaceOperation(XmlPullParser xpp) {
        String xpath = DashManifestParser.parseString(xpp, "sel", "");
        boolean isAttributeOp = isAttributeOperation(xpath);
        @Nullable String content = null;
        @Nullable Element element = null;
        try {
            if (!isAttributeOp) {
                element = createElements(xpp, xpp.getName(), document);
                root.appendChild(element);
            } else {
                content = DashManifestParser.parseText(xpp, "replace");
            }
        } catch (Exception ex) {
            Log.w(TAG, "Failed to parse nodes of replace operation", ex);
        }
        return new DashManifestPatch.ReplaceOperation(isAttributeOp, xpath, content, element);
    }

    private DashManifestPatch.Operation parseRemoveOperation(XmlPullParser xpp) {
        String xpath = DashManifestParser.parseString(xpp, "sel", "");
        String ws = DashManifestParser.parseString(xpp, "ws", "");
        return new DashManifestPatch.RemoveOperation(isAttributeOperation(xpath), xpath, ws.length() > 0 ? ws : null);
    }

    private static Element createElements(XmlPullParser xpp, String startTag, Document document)
            throws IOException, XmlPullParserException {
        Element element = document.createElement(startTag);
        for (int i = 0; i < xpp.getAttributeCount(); i++) {
            element.setAttribute(xpp.getAttributeName(i), xpp.getAttributeValue(i));
        }
        do {
            xpp.next();
            if (xpp.getText() != null) {
                String text = xpp.getText();
                text = text.trim();
                if (text.length() > 0) {
                    element.setTextContent(text);
                }
                xpp.next();
            }
            if (xpp.getName() != null && !xpp.getName().equals(startTag)) {
                Element nextElement = createElements(xpp, xpp.getName(), document);
                element.appendChild(nextElement);
            }
        } while (!XmlPullParserUtil.isEndTag(xpp, startTag));
        return element;
    }
}
