package com.google.android.exoplayer2.source.dash.manifest;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.android.exoplayer2.testutil.TestUtil;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;

import static com.google.common.truth.Truth.assertThat;

/** Unit tests for {@link DashManifestPatch}. */
@RunWith(AndroidJUnit4.class)
public class DashManifestPatchTest {
    private static final String SAMPLE_MPD_WITH_PATCH_LOCATION = "manifest_patch/mpd_with_patch_location";

    @Test
    public void testAddAttribute() throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        String path = "/MPD/PatchLocation";
        String attributeName = "id";
        String attributeValue = "123";
        DashManifestPatch.AddOperation operation = new DashManifestPatch.AddOperation(
                path, attributeValue, null, "@" + attributeName, null);
        operation.execute(document, xPath);

        Node target = null;
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeName().equals("PatchLocation")) {
                target = children.item(i);
                break;
            }
        }

        assertThat(target.getAttributes().getNamedItem(attributeName).getNodeValue()).isEqualTo(attributeValue);
    }

    @Test
    public void testAddNodes() throws IOException, ParserConfigurationException, SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        Element rootElement = document.createElement("root");
        Element element = document.createElement("Period");
        element.setAttribute("id", "98");
        rootElement.appendChild(element);

        Element element2 = document.createElement("Period");
        element2.setAttribute("id", "99");
        rootElement.appendChild(element2);

        String path = "/MPD";
        DashManifestPatch.AddOperation operation = new DashManifestPatch.AddOperation(
                path, null, null, null, rootElement);
        boolean success = operation.execute(document, xPath);
        assertThat(success).isTrue();

        List<Node> nodes = new ArrayList<>();
        NodeList children = document.getDocumentElement().getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i).getNodeName().equals("Period")) {
                nodes.add(children.item(i));
            }
        }

        assertThat(nodes.get(nodes.size() - 2).getAttributes().getNamedItem("id").getNodeValue()).isEqualTo("98");
        assertThat(nodes.get(nodes.size() - 1).getAttributes().getNamedItem("id").getNodeValue()).isEqualTo("99");
    }

    @Test
    public void testReplaceAttribute() throws IOException, ParserConfigurationException,
            SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        String path = "/MPD/Period[@id='81']/@start";
        DashManifestPatch.ReplaceOperation operation = new DashManifestPatch.ReplaceOperation(
                true, path, "PT1234M5.000000S", null);

        boolean success = operation.execute(document, xPath);
        assertThat(success).isTrue();

        Element targetNode = (Element)xPath.compile("/MPD/Period[@id='81']")
                                           .evaluate(document, XPathConstants.NODE);
        assertThat(targetNode.getAttributes().getNamedItem("start").getNodeValue())
                .isEqualTo("PT1234M5.000000S");
    }

    @Test
    public void testReplaceSingleNode() throws IOException, ParserConfigurationException,
            SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        Element root = document.createElement("root");

        Element location = document.createElement("PatchLocation");
        root.appendChild(location);
        location.setAttribute("ttl", "66");
        location.setTextContent("manifest-patch.mpd?publishTime=2020-11-09T03:48:43.514902582Z");

        String path = "/MPD/PatchLocation";
        DashManifestPatch.ReplaceOperation operation = new DashManifestPatch.ReplaceOperation(
                false, path, null, root);
        boolean success = operation.execute(document, xPath);
        assertThat(success).isTrue();

        Element targetNode = (Element)xPath.compile(path).evaluate(document, XPathConstants.NODE);
        assertThat(targetNode.getAttributes().getNamedItem("ttl").getNodeValue())
                .isEqualTo("66");
        assertThat(targetNode.getTextContent())
                .isEqualTo("manifest-patch.mpd?publishTime=2020-11-09T03:48:43.514902582Z");
    }

    @Test
    public void testReplaceNodeWithMultipleChildren() throws IOException, ParserConfigurationException,
            SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        Element root = document.createElement("root");
        Element timeline = document.createElement("SegmentTimeline");
        root.appendChild(timeline);

        Element element = document.createElement("S");
        element.setAttribute("t", "123");
        element.setAttribute("d", "20000000");
        element.setAttribute("r", "6");
        timeline.appendChild(element);

        Element element2 = document.createElement("S");
        element2.setAttribute("t", "120000123");
        element2.setAttribute("d", "30000000");
        element2.setAttribute("r", "3");
        timeline.appendChild(element2);

        String path = "/MPD/Period[@id='81']/AdaptationSet/SegmentTemplate/SegmentTimeline";
        DashManifestPatch.ReplaceOperation operation = new DashManifestPatch.ReplaceOperation(
                false, path, null, root);
        boolean success = operation.execute(document, xPath);
        assertThat(success).isTrue();

        Element targetNode = (Element)xPath.compile(path)
                .evaluate(document, XPathConstants.NODE);
        int elementCount = 0;
        for (int i = 0; i < targetNode.getChildNodes().getLength(); i++) {
            if (targetNode.getChildNodes().item(i) instanceof Element) {
                Element child = (Element)targetNode.getChildNodes().item(i);
                elementCount++;
            }
        }
        assertThat(elementCount).isEqualTo(2);

        Node segment1 = targetNode.getChildNodes().item(0);
        Node segment2 = targetNode.getChildNodes().item(1);

        assertThat(segment1.getAttributes().getNamedItem("t").getNodeValue())
                .isEqualTo("123");
        assertThat(segment1.getAttributes().getNamedItem("d").getNodeValue())
                .isEqualTo("20000000");
        assertThat(segment1.getAttributes().getNamedItem("r").getNodeValue())
                .isEqualTo("6");

        assertThat(segment2.getAttributes().getNamedItem("t").getNodeValue())
                .isEqualTo("120000123");
        assertThat(segment2.getAttributes().getNamedItem("d").getNodeValue())
                .isEqualTo("30000000");
        assertThat(segment2.getAttributes().getNamedItem("r").getNodeValue())
                .isEqualTo("3");
    }

    @Test
    public void testRemoveAttribute() throws IOException, ParserConfigurationException,
            SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        String path = "/MPD/Period[@id='81']/@start";
        DashManifestPatch.RemoveOperation operation = new DashManifestPatch.RemoveOperation(
                true, path, null);

        boolean success = operation.execute(document, xPath);
        assertThat(success).isTrue();

        Element targetNode = (Element)xPath.compile("/MPD/Period[@id='81']")
                                           .evaluate(document, XPathConstants.NODE);
        assertThat(targetNode.getAttributes().getNamedItem("start")).isNull();
    }

    @Test
    public void testRemoveNode() throws IOException, ParserConfigurationException,
            SAXException, XPathExpressionException {
        Document document = createDocument(SAMPLE_MPD_WITH_PATCH_LOCATION);
        XPath xPath =  XPathFactory.newInstance().newXPath();

        String path = "/MPD/Period[@id='81']/AdaptationSet/Representation[4]";
        DashManifestPatch.RemoveOperation operation = new DashManifestPatch.RemoveOperation(
                false, path, null);

        boolean success = operation.execute(document, xPath);
        assertThat(success).isTrue();

        Element targetNode = (Element)xPath.compile(path)
                .evaluate(document, XPathConstants.NODE);
        assertThat(targetNode.getAttributes().getNamedItem("id").getNodeValue())
                .isEqualTo("stream_4");
    }

    Document createDocument(String path) throws IOException, ParserConfigurationException,
            SAXException {
        InputStream stream = TestUtil.getInputStream(
                ApplicationProvider.getApplicationContext(), path);
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        DocumentBuilder builder =  builderFactory.newDocumentBuilder();
        return builder.parse(stream);
    }
}
