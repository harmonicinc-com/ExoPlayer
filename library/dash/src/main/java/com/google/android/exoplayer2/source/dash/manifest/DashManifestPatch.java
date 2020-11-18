package com.google.android.exoplayer2.source.dash.manifest;

import android.util.Log;

import androidx.annotation.Nullable;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import java.util.List;

import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

// Reference: https://tools.ietf.org/html/rfc5261
public class DashManifestPatch {
    private static final String TAG = "DashManifestPatch";
    public final String mpdId;
    public final long originalPublishTimeMs;
    public final long publishTimeMs;
    public final List<Operation> operations;

    public DashManifestPatch(
            String mpdId,
            long originalPublishTimeMs,
            long publishTimeMs,
            List<Operation> operations) {
        this.mpdId = mpdId;
        this.originalPublishTimeMs = originalPublishTimeMs;
        this.publishTimeMs = publishTimeMs;
        this.operations = operations;
    }

    public boolean applyPatch(Document document) {
        XPath xPath =  XPathFactory.newInstance().newXPath();
        boolean result = true;
        try {
            for (Operation operation : operations) {
                if (!operation.execute(document, xPath)) {
                    Log.w(TAG, "Failed to execute operation, xpath: " + operation.getXPath());
                    result = false;
                    break;
                }
            }
        } catch (XPathExpressionException ex) {
            Log.w(TAG, "Failed to apply patch", ex);
            result = false;
        }
        return result;
    }

    public interface Operation {
        String getXPath();
        boolean execute(Document document, XPath xPath) throws XPathExpressionException;
    }

    public static class AddOperation implements Operation {
        public final String path;
        @Nullable public final String content;
        @Nullable public final String pos;
        @Nullable public final String type;
        @Nullable public final Element element;

        public AddOperation(String path,
                            @Nullable String content,
                            @Nullable String pos,
                            @Nullable String type,
                            @Nullable Element element) {
            this.path = path;
            this.content = content;
            this.pos = pos;
            this.type = type;
            this.element = element;
        }

        @Override
        public String getXPath() {
            return path;
        }

        @Override
        public boolean execute(Document document, XPath xPath) throws XPathExpressionException {
            Element node = (Element)xPath.compile(path).evaluate(document, XPathConstants.NODE);
            if (node == null) {
                return false;
            }
            if (type != null) {
                // Add Attribute
                String name = type.substring(1); // Remove '@' at the start
                node.setAttribute(name, content);
            } else {
                // Add Node
                while (element.hasChildNodes()) {
                    node.appendChild(element.getFirstChild());
                }
            }
            return true;
        }
    }

    public static class ReplaceOperation implements Operation {
        public final boolean isAttribute;
        public final String path;
        @Nullable public final String content;
        @Nullable public final Element element;

        public ReplaceOperation(boolean isAttribute, String path,
                                @Nullable String content,
                                @Nullable Element element) {
            this.isAttribute = isAttribute;
            this.path = path;
            this.content = content;
            this.element = element;
        }

        @Override
        public String getXPath() {
            return path;
        }

        @Override
        public boolean execute(Document document, XPath xPath) throws XPathExpressionException {
            if (isAttribute) {
                int index = path.lastIndexOf('/');
                String modifiedPath = path.substring(0, index);
                String attrName = path.substring(index + 2);
                Element node = (Element) xPath.compile(modifiedPath).evaluate(document, XPathConstants.NODE);
                node.setAttribute(attrName, content);
            } else {
                Element node = (Element)xPath.compile(path).evaluate(document, XPathConstants.NODE);
                Node parent = node.getParentNode();
                if (element.getFirstChild() != null) {
                    parent.replaceChild(element.getFirstChild(), node);
                }
            }
            return true;
        }
    }

    public static class RemoveOperation implements Operation {
        public final boolean isAttribute;
        public final String path;
        @Nullable public final String ws;
        public RemoveOperation(boolean isAttribute,
                               String path,
                               @Nullable String ws) {
            this.isAttribute = isAttribute;
            this.path = path;
            this.ws = ws;
        }

        @Override
        public String getXPath() {
            return path;
        }

        @Override
        public boolean execute(Document document, XPath xPath) throws XPathExpressionException {
            if (isAttribute) {
                int index = path.lastIndexOf('/');
                String modifiedPath = path.substring(0, index);
                String attrName = path.substring(index + 2);
                Element node = (Element) xPath.compile(modifiedPath).evaluate(document, XPathConstants.NODE);
                node.removeAttribute(attrName);
            } else {
                Element node = (Element)xPath.compile(path).evaluate(document, XPathConstants.NODE);
                Node parent = node.getParentNode();
                parent.removeChild(node);
            }
            return true;
        }
    }
}
