package com.google.android.exoplayer2.source.dash.manifest;

import android.net.Uri;
import com.google.android.exoplayer2.ParserException;
import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;

public class DocumentToManifestConverter {

  private static final DashManifestParser parser = new DashManifestParser();

  public static DashManifest convert(Document document, String baseUrl) throws ParserException {
    try {
      String mpd = getStringFromDoc(document);
      return parser.parse(Uri.parse(baseUrl), new ByteArrayInputStream(mpd.getBytes()));
    } catch (Exception ex) {
      throw new ParserException();
    }
  }

  public static String getStringFromDoc(Document doc) throws TransformerException {
    DOMSource domSource = new DOMSource(doc);
    StringWriter writer = new StringWriter();
    StreamResult result = new StreamResult(writer);
    TransformerFactory tf = TransformerFactory.newInstance();
    Transformer transformer = tf.newTransformer();
    transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
    transformer.setOutputProperty(OutputKeys.INDENT, "yes");
    transformer.transform(domSource, result);
    writer.flush();
    return writer.toString();
  }
}
