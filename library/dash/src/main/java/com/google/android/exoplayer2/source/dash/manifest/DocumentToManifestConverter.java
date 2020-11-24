package com.google.android.exoplayer2.source.dash.manifest;

import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.ParserException;
import com.google.android.exoplayer2.drm.DrmInitData.SchemeData;
import com.google.android.exoplayer2.extractor.mp4.PsshAtomUtil;
import com.google.android.exoplayer2.metadata.emsg.EventMessage;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.UriUtil;
import com.google.android.exoplayer2.util.Util;

import org.checkerframework.checker.nullness.compatqual.NullableType;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.buildFormat;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.buildRepresentation;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.checkContentTypeConsistency;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.checkLanguageConsistency;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.getContentType;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.parseContentType;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.parseDolbyChannelConfiguration;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.parseFrameRate;
import static com.google.android.exoplayer2.source.dash.manifest.DashManifestParser.parseLastSegmentNumberSupplementalProperty;

public class DocumentToManifestConverter {
    private static final String TAG = "DocToManifestConverter";
    private static final Pattern FRAME_RATE_PATTERN = Pattern.compile("(\\d+)(?:/(\\d+))?");

    public static DashManifest convert(Document document, String baseUrl) throws ParserException {
        Element root = document.getDocumentElement();
        long availabilityStartTimeMs = parseDateTime(root, "availabilityStartTime", C.TIME_UNSET);
        long durationMs = parseDuration(root, "mediaPresentationDuration", C.TIME_UNSET);
        long minBufferTimeMs = parseDuration(root, "minBufferTime", C.TIME_UNSET);
        String typeString = root.getAttribute("type");
        boolean dynamic = "dynamic".equals(typeString);
        long minUpdatePeriodMs = dynamic ? parseDuration(root, "minimumUpdatePeriod", C.TIME_UNSET) : C.TIME_UNSET;
        long timeShiftBufferDepthMs = dynamic ? parseDuration(root, "timeShiftBufferDepth", C.TIME_UNSET) : C.TIME_UNSET;
        long suggestedPresentationDelayMs = dynamic ? parseDuration(root, "suggestedPresentationDelay", C.TIME_UNSET) : C.TIME_UNSET;
        long publishTimeMs = parseDateTime(root, "publishTime", 0);
        @Nullable ProgramInformation programInformation = null;
        @Nullable UtcTimingElement utcTiming = null;
        @Nullable Uri location = null;
        @Nullable PatchLocation patchLocation = null;
        List<Period> periods = new ArrayList<>();
        List<Period> earlyAccessPeriods = new ArrayList<>();
        long nextPeriodStartMs = dynamic ? C.TIME_UNSET : 0;
        boolean seenEarlyAccessPeriod = false;
        boolean seenFirstBaseUrl = false;

        for (int i = 0; i < root.getChildNodes().getLength(); i++) {
            if (!(root.getChildNodes().item(i) instanceof Element)) {
                continue;
            }
            Element child = (Element)root.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("BaseURL")) {
                if (!seenFirstBaseUrl) {
                    baseUrl = parseBaseUrl(child, baseUrl);
                    seenFirstBaseUrl = true;
                }
            } else if (name.equals("ProgramInformation")) {
                programInformation = parseProgramInformation(child);
            } else if (name.equals("UTCTiming")) {
                utcTiming = parseUtcTiming(child);
            } else if (name.equals("Location")) {
                location = Uri.parse(child.getTextContent());
            } else if (name.equals("PatchLocation")) {
                patchLocation = parsePatchLocation(child, baseUrl);
            } else if (name.equals("Period") && !seenEarlyAccessPeriod) {
                Pair<Period, Long> periodWithDurationMs = parsePeriod(child, baseUrl, nextPeriodStartMs);
                Period period = periodWithDurationMs.first;
                if (period.startMs == C.TIME_UNSET) {
                    if (dynamic) {
                        // This is an early access period. Ignore it. All subsequent periods must also be
                        // early access.
                        earlyAccessPeriods.add(period);
                        seenEarlyAccessPeriod = true;
                    } else {
                        throw new ParserException("Unable to determine start of period " + periods.size());
                    }
                } else {
                    long periodDurationMs = periodWithDurationMs.second;
                    nextPeriodStartMs = periodDurationMs == C.TIME_UNSET ? C.TIME_UNSET
                            : (period.startMs + periodDurationMs);
                    periods.add(period);
                }
            }
        }

        DashManifest manifest = new DashManifest(
                availabilityStartTimeMs,
                durationMs,
                minBufferTimeMs,
                dynamic,
                minUpdatePeriodMs,
                timeShiftBufferDepthMs,
                suggestedPresentationDelayMs,
                publishTimeMs,
                programInformation,
                utcTiming,
                location,
                patchLocation,
                periods);
        manifest.earlyAccessPeriods = earlyAccessPeriods;
        return manifest;
    }

    static ProgramInformation parseProgramInformation(Element node) {
        @Nullable String moreInformationURL = getAttribute(node, "moreInformationURL");
        @Nullable String lang = getAttribute(node, "lang");
        @Nullable String title = null;
        @Nullable String source = null;
        @Nullable String copyright = null;

        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            Node child = node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("Title")) {
                title = child.getTextContent();
            } else if (name.equals("Source")) {
                source = child.getTextContent();
            } else if (name.equals("Copyright")) {
                copyright = child.getTextContent();
            }
        }
        return new ProgramInformation(title, source, copyright, moreInformationURL, lang);
    }

    static UtcTimingElement parseUtcTiming(Element node) {
        String schemeIdUri = parseString(node, "schemeIdUri", "");
        String value = parseString(node, "value", "");
        return new UtcTimingElement(schemeIdUri, value);
    }

    static PatchLocation parsePatchLocation(Element node, String parentBaseUrl) {
        long ttl = parseLong(node, "ttl", 0);
        String url = UriUtil.resolve(parentBaseUrl, node.getTextContent());
        return new PatchLocation(ttl, url);
    }

    static Pair<Period, Long> parsePeriod(Element node, String baseUrl, long defaultStartMs) {
        String id = node.getAttribute("id");
        long startMs = parseDuration(node, "start", defaultStartMs);
        long durationMs = parseDuration(node, "duration", C.TIME_UNSET);
        SegmentBase segmentBase = null;
        List<AdaptationSet> adaptationSets = new ArrayList<>();
        List<EventStream> eventStreams = new ArrayList<>();
        boolean seenFirstBaseUrl = false;
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            if (!(node.getChildNodes().item(i) instanceof Element)) {
                continue;
            }
            Element child = (Element)node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("BaseURL")) {
                if (!seenFirstBaseUrl) {
                    baseUrl = parseBaseUrl(child, baseUrl);
                    seenFirstBaseUrl = true;
                }
            } else if (name.equals("AdaptationSet")) {
                adaptationSets.add(parseAdaptationSet(child, baseUrl, segmentBase, durationMs));
            } else if (name.equals("EventStream")) {
                eventStreams.add(parseEventStream(child));
            } else if (name.equals("SegmentBase")) {
                segmentBase = parseSegmentBase(child, null);
            } else if (name.equals("SegmentList")) {
                segmentBase = parseSegmentList(child, null, durationMs);
            } else if (name.equals("SegmentTemplate")) {
                segmentBase = parseSegmentTemplate(child, null, Collections.emptyList(), durationMs);
            }
        }

        try {
            Representation.MultiSegmentRepresentation rep =
                    (Representation.MultiSegmentRepresentation)adaptationSets.get(0).representations.get(0);
            if (rep.segmentBase.duration == C.TIME_UNSET && rep.segmentBase.segmentTimeline == null) {
                Log.d(TAG, "Invalid SegmentTemplate when parsing Period, assume period is Early Access Period");
                startMs = defaultStartMs;
            }
        } catch (ClassCastException ex) {
            throw new RuntimeException(ex);
        }
        return Pair.create(new Period(id, startMs, adaptationSets, eventStreams), durationMs);
    }

    static AdaptationSet parseAdaptationSet(Element node, String baseUrl, @Nullable SegmentBase segmentBase,
                                            long periodDurationMs) {
        int id = parseInt(node, "id", AdaptationSet.ID_UNSET);
        int contentType = parseContentType(getAttribute(node, "contentType"));
        String mimeType = parseString(node, "mimeType", null);
        String codecs = parseString(node, "codecs", null);
        int width = parseInt(node, "width", Format.NO_VALUE);
        int height = parseInt(node, "height", Format.NO_VALUE);
        float frameRate = parseFrameRate(getAttribute(node, "frameRate"), Format.NO_VALUE);
        int audioChannels = Format.NO_VALUE;
        int audioSamplingRate = parseInt(node, "audioSamplingRate", Format.NO_VALUE);
        @Nullable String language = getAttribute(node, "lang");
        @Nullable String label = getAttribute(node, "label");
        String drmSchemeType = null;
        ArrayList<SchemeData> drmSchemeDatas = new ArrayList<>();
        ArrayList<Descriptor> inbandEventStreams = new ArrayList<>();
        ArrayList<Descriptor> accessibilityDescriptors = new ArrayList<>();
        ArrayList<Descriptor> roleDescriptors = new ArrayList<>();
        ArrayList<Descriptor> supplementalProperties = new ArrayList<>();
        List<DashManifestParser.RepresentationInfo> representationInfos = new ArrayList<>();

        boolean seenFirstBaseUrl = false;
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            if (!(node.getChildNodes().item(i) instanceof Element)) {
                continue;
            }
            Element child = (Element)node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("BaseURL")) {
                if (!seenFirstBaseUrl) {
                    baseUrl = parseBaseUrl(child, baseUrl);
                    seenFirstBaseUrl = true;
                }
            } else if (name.equals("ContentProtection")) {
                Pair<String, SchemeData> contentProtection = parseContentProtection(child);
                if (contentProtection.first != null) {
                    drmSchemeType = contentProtection.first;
                }
                if (contentProtection.second != null) {
                    drmSchemeDatas.add(contentProtection.second);
                }
            } else if (name.equals("ContentComponent")) {
                language = checkLanguageConsistency(language, child.getAttribute("lang"));
                contentType = checkContentTypeConsistency(contentType,
                        parseContentType(getAttribute(child, "contentType")));
            } else if (name.equals("Role")) {
                roleDescriptors.add(parseDescriptor(child));
            } else if (name.equals("AudioChannelConfiguration")) {
                audioChannels = parseAudioChannelConfiguration(child);
            } else if (name.equals("Accessibility")) {
                accessibilityDescriptors.add(parseDescriptor(child));
            } else if (name.equals("SupplementalProperty")) {
                supplementalProperties.add(parseDescriptor(child));
            } else if (name.equals("Representation")) {
                DashManifestParser.RepresentationInfo representationInfo =
                        parseRepresentation(
                                child,
                                baseUrl,
                                mimeType,
                                codecs,
                                width,
                                height,
                                frameRate,
                                audioChannels,
                                audioSamplingRate,
                                language,
                                roleDescriptors,
                                accessibilityDescriptors,
                                supplementalProperties,
                                segmentBase,
                                periodDurationMs);
                contentType = checkContentTypeConsistency(contentType,
                        getContentType(representationInfo.format));
                representationInfos.add(representationInfo);
            } else if (name.equals("SegmentBase")) {
                segmentBase = parseSegmentBase(child, (SegmentBase.SingleSegmentBase) segmentBase);
            } else if (name.equals("SegmentList")) {
                segmentBase = parseSegmentList(child, (SegmentBase.SegmentList) segmentBase, periodDurationMs);
            } else if (name.equals("SegmentTemplate")) {
                segmentBase =
                        parseSegmentTemplate(
                                child, (SegmentBase.SegmentTemplate) segmentBase,
                                supplementalProperties, periodDurationMs);
            } else if (name.equals("InbandEventStream")) {
                inbandEventStreams.add(parseDescriptor(child));
            } else if (name.equals("Label")) {
                label = parseLabel(child);
            }
        }

        List<Representation> representations = new ArrayList<>(representationInfos.size());
        for (int i = 0; i < representationInfos.size(); i++) {
            representations.add(
                    buildRepresentation(
                            representationInfos.get(i),
                            label,
                            drmSchemeType,
                            drmSchemeDatas,
                            inbandEventStreams));
        }

        return new AdaptationSet(id, contentType, representations,
                accessibilityDescriptors, supplementalProperties);
    }

    static EventStream parseEventStream(Element node) {
        String schemeIdUri = parseString(node, "schemeIdUri", "");
        String value = parseString(node, "value", "");
        long timescale = parseLong(node, "timescale", 1);
        List<Pair<Long, EventMessage>> events = new ArrayList<>();
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            Node child = node.getChildNodes().item(i);
            if (child.getNodeName().equals("Event")) {
                events.add(parseEventMessage((Element)child, schemeIdUri, value, timescale));
            }
        }
        long[] presentationTimesUs = new long[events.size()];
        EventMessage[] eventMessages = new EventMessage[events.size()];
        for (int i = 0; i < events.size(); i++) {
            presentationTimesUs[i] = events.get(i).first;
            eventMessages[i] = events.get(i).second;
        }
        return new EventStream(schemeIdUri, value, timescale, presentationTimesUs, eventMessages);
    }

    static Pair<Long, EventMessage> parseEventMessage(Element node, String schemeIdUri,
                                                      String value, long timescale) {
        long duration = parseLong(node, "duration", C.TIME_UNSET);
        long id = parseLong(node, "id", 0);
        long presentationTime = parseLong(node, "presentationTime", 0);
        long durationMs = Util.scaleLargeTimestamp(duration, C.MILLIS_PER_SECOND, timescale);
        long presentationTimeUs = Util.scaleLargeTimestamp(presentationTime, C.MICROS_PER_SECOND,
                timescale);
        @Nullable String messageData = parseString(node, "messageData", null);
        byte[] bytes;
        if (messageData != null) {
            bytes = Util.getUtf8Bytes(messageData);
        } else {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < node.getChildNodes().getLength(); i++) {
                Node child = node.getChildNodes().item(i);
                if (child instanceof Element) {
                    sb.append(extractInnerText(child));
                } else {
                    sb.append(child.getTextContent());
                }
            }
            bytes = Util.getUtf8Bytes(sb.toString());
        }
        return Pair.create(
                presentationTimeUs,
                new EventMessage(schemeIdUri, value, durationMs, id, bytes));
    }

    static String extractInnerText(Node node) {
        try {
            Transformer transformer =  TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(writer));
            return writer.toString()
                    .replace("\r\n", "\n")
                    .replace("\"", "&\";");
        } catch (TransformerException e) {
            throw new RuntimeException(e);
        }
    }

    static @Nullable String getAttribute(Element node, String name) {
        if (node.hasAttribute(name)) {
            return node.getAttribute(name);
        }
        return null;
    }

    static long parseDateTime(Element node, String name, long defaultValue)
            throws ParserException {
        if (!node.hasAttribute(name)) {
            return defaultValue;
        }
        return Util.parseXsDateTime(node.getAttribute(name));
    }

    static long parseLong(Element node, String name, long defaultValue) {
        if (!node.hasAttribute(name)) {
            return defaultValue;
        }
        return Long.parseLong(node.getAttribute(name));
    }

    static int parseInt(Element node, String name, int defaultValue) {
        if (!node.hasAttribute(name)) {
            return defaultValue;
        }
        return Integer.parseInt(node.getAttribute(name));
    }

    static String parseString(Element node, String name, String defaultValue) {
        if (!node.hasAttribute(name)) {
            return defaultValue;
        }
        return node.getAttribute(name);
    }

    static String parseBaseUrl(Element node, String parentBaseUrl) {
        return UriUtil.resolve(parentBaseUrl, node.getTextContent());
    }

    static Descriptor parseDescriptor(Element node) {
        String schemeIdUri = parseString(node, "schemeIdUri", "");
        String value = parseString(node, "value", null);
        String id = parseString(node, "id", null);
        return new Descriptor(schemeIdUri, value, id);
    }

    static int parseAudioChannelConfiguration(Element node) {
        String schemeIdUri = parseString(node, "schemeIdUri", null);
        int audioChannels =
                "urn:mpeg:dash:23003:3:audio_channel_configuration:2011".equals(schemeIdUri)
                    ? parseInt(node, "value", Format.NO_VALUE)
                    : ("tag:dolby.com,2014:dash:audio_channel_configuration:2011".equals(schemeIdUri)
                            || "urn:dolby:dash:audio_channel_configuration:2011".equals(schemeIdUri)
                        ? parseDolbyChannelConfiguration(getAttribute(node, "value"))
                        : Format.NO_VALUE);
        return audioChannels;
    }

    static SegmentBase.SingleSegmentBase parseSegmentBase(
            Element node, @Nullable SegmentBase.SingleSegmentBase parent) {

        long timescale = parseLong(node, "timescale", parent != null ? parent.timescale : 1);
        long presentationTimeOffset = parseLong(node, "presentationTimeOffset",
                parent != null ? parent.presentationTimeOffset : 0);

        long indexStart = parent != null ? parent.indexStart : 0;
        long indexLength = parent != null ? parent.indexLength : 0;
        @Nullable String indexRangeText = getAttribute(node, "indexRange");
        if (indexRangeText != null) {
            String[] indexRange = indexRangeText.split("-");
            indexStart = Long.parseLong(indexRange[0]);
            indexLength = Long.parseLong(indexRange[1]) - indexStart + 1;
        }

        @Nullable RangedUri initialization = parent != null ? parent.initialization : null;
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            Node child = node.getChildNodes().item(i);
            if (child.getNodeName().equals("Initialization")) {
                initialization = parseInitialization(node);
            }
        }
        return new SegmentBase.SingleSegmentBase(initialization, timescale,
                presentationTimeOffset, indexStart, indexLength);
    }

    static RangedUri parseInitialization(Element node) {
        return parseRangedUrl(node, "sourceURL", "range");
    }

    static RangedUri parseSegmentUrl(Element node) {
        return parseRangedUrl(node, "media", "mediaRange");
    }

    static RangedUri parseRangedUrl(Element node, String urlAttribute,
                                       String rangeAttribute) {
        String urlText = node.getAttribute(urlAttribute);
        long rangeStart = 0;
        long rangeLength = C.LENGTH_UNSET;
        @Nullable String rangeText = getAttribute(node, rangeAttribute);
        if (rangeText != null) {
            String[] rangeTextArray = rangeText.split("-");
            rangeStart = Long.parseLong(rangeTextArray[0]);
            if (rangeTextArray.length == 2) {
                rangeLength = Long.parseLong(rangeTextArray[1]) - rangeStart + 1;
            }
        }
        return new RangedUri(urlText, rangeStart, rangeLength);
    }

    static SegmentBase.SegmentList parseSegmentList(
            Element node, @Nullable SegmentBase.SegmentList parent, long periodDurationMs) {
        long timescale = parseLong(node, "timescale", parent != null ? parent.timescale : 1);
        long presentationTimeOffset = parseLong(node, "presentationTimeOffset",
                parent != null ? parent.presentationTimeOffset : 0);
        long duration = parseLong(node, "duration", parent != null ? parent.duration : C.TIME_UNSET);
        long startNumber = parseLong(node, "startNumber", parent != null ? parent.startNumber : 1);

        @Nullable RangedUri initialization = null;
        @Nullable List<SegmentBase.SegmentTimelineElement> timeline = null;
        @Nullable List<RangedUri> segments = null;

        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            Node child = node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("Initialization")) {
                initialization = parseInitialization(node);
            } else if (name.equals("SegmentTimeline")) {
                timeline = parseSegmentTimeline(node, timescale, periodDurationMs);
            } else if (name.equals("SegmentURL")) {
                if (segments == null) {
                    segments = new ArrayList<>();
                }
                segments.add(parseSegmentUrl(node));
            }
        }

        if (parent != null) {
            initialization = initialization != null ? initialization : parent.initialization;
            timeline = timeline != null ? timeline : parent.segmentTimeline;
            segments = segments != null ? segments : parent.mediaSegments;
        }

        return new SegmentBase.SegmentList(initialization, timescale,
                presentationTimeOffset, startNumber, duration, timeline, segments);
    }

    static List<SegmentBase.SegmentTimelineElement> parseSegmentTimeline(
            Element node, long timescale, long periodDurationMs) {
        List<SegmentBase.SegmentTimelineElement> segmentTimeline = new ArrayList<>();
        long startTime = 0;
        long elementDuration = C.TIME_UNSET;
        int elementRepeatCount = 0;
        boolean havePreviousTimelineElement = false;

        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            if (!(node.getChildNodes().item(i) instanceof Element)) {
                continue;
            }
            Element child = (Element)node.getChildNodes().item(i);
            if (child.getNodeName().equals("S")) {
                long newStartTime = parseLong(child, "t", C.TIME_UNSET);
                if (havePreviousTimelineElement) {
                    startTime =
                            DashManifestParser.addSegmentTimelineElementsToList(
                                    segmentTimeline,
                                    startTime,
                                    elementDuration,
                                    elementRepeatCount,
                                    /* endTime= */ newStartTime);
                }
                if (newStartTime != C.TIME_UNSET) {
                    startTime = newStartTime;
                }
                elementDuration = parseLong(child, "d", C.TIME_UNSET);
                elementRepeatCount = parseInt(child, "r", 0);
                havePreviousTimelineElement = true;
            }
        }

        if (havePreviousTimelineElement) {
            long periodDuration = Util.scaleLargeTimestamp(periodDurationMs, timescale, 1000);
            DashManifestParser.addSegmentTimelineElementsToList(
                    segmentTimeline,
                    startTime,
                    elementDuration,
                    elementRepeatCount,
                    /* endTime= */ periodDuration);
        }
        return segmentTimeline;
    }

    static Pair<@NullableType String, @NullableType SchemeData> parseContentProtection(
            Element node) {
        @NullableType String schemeType = null;
        @NullableType String licenseServerUrl = null;
        byte[] data = null;
        @NullableType UUID uuid = null;

        String schemeIdUri = node.getAttribute("schemeIdUri");
        if (schemeIdUri != null) {
            switch (Util.toLowerInvariant(schemeIdUri)) {
                case "urn:mpeg:dash:mp4protection:2011":
                    schemeType = node.getAttribute("value");
                    @NullableType String defaultKid = getAttributeValueIgnorePrefix(node, "default_KID");
                    if (!TextUtils.isEmpty(defaultKid)
                            && !"00000000-0000-0000-0000-000000000000".equals(defaultKid)) {
                        String[] defaultKidStrings = defaultKid.split("\\s+");
                        UUID[] defaultKids = new UUID[defaultKidStrings.length];
                        for (int i = 0; i < defaultKidStrings.length; i++) {
                            defaultKids[i] = UUID.fromString(defaultKidStrings[i]);
                        }
                        data = PsshAtomUtil.buildPsshAtom(C.COMMON_PSSH_UUID, defaultKids, null);
                        uuid = C.COMMON_PSSH_UUID;
                    }
                    break;
                case "urn:uuid:9a04f079-9840-4286-ab92-e65be0885f95":
                    uuid = C.PLAYREADY_UUID;
                    break;
                case "urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed":
                    uuid = C.WIDEVINE_UUID;
                    break;
                default:
                    break;
            }
        }

        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            Element child = (Element)node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("ms:laurl")) {
                licenseServerUrl = child.getAttribute("licenseUrl");
            } else if (data == null
                    && stripPrefix(name).equals("pssh")) {
                data = Base64.decode(child.getTextContent(), Base64.DEFAULT);
                uuid = PsshAtomUtil.parseUuid(data);
                if (uuid == null) {
                    Log.w(TAG, "Skipping malformed cenc:pssh data");
                    data = null;
                }
            } else if (data == null
                    && C.PLAYREADY_UUID.equals(uuid)
                    && name.equals("mspr:pro")) {
                data = PsshAtomUtil.buildPsshAtom(
                                C.PLAYREADY_UUID,
                                Base64.decode(child.getTextContent(), Base64.DEFAULT));
            }
        }
        SchemeData schemeData = uuid != null ?
                        new SchemeData(uuid, licenseServerUrl, MimeTypes.VIDEO_MP4, data)
                        : null;
        return Pair.create(schemeType, schemeData);
    }

    static @Nullable String getAttributeValueIgnorePrefix(Element node, String attributeName) {
        int attributeCount = node.getAttributes().getLength();
        for (int i = 0; i < attributeCount; i++) {
            String attr = node.getAttributes().item(i).getNodeValue();
            if (stripPrefix(attr).equals(attributeName)) {
                return attr;
            }
        }
        return null;
    }

    static String stripPrefix(String name) {
        int prefixSeparatorIndex = name.indexOf(':');
        return prefixSeparatorIndex == -1 ? name : name.substring(prefixSeparatorIndex + 1);
    }

    static String parseLabel(Element node) {
        String value = node.getTextContent();
        if (value == null || !value.isEmpty()) {
            return value;
        }
        return null;
    }

    static long parseDuration(Element node, String name, long defaultValue) {
        if (!node.hasAttribute(name)) {
            return defaultValue;
        }
        return Util.parseXsDuration(node.getAttribute(name));
    }

    static SegmentBase.SegmentTemplate parseSegmentTemplate(
            Element node,
            @Nullable SegmentBase.SegmentTemplate parent,
            List<Descriptor> adaptationSetSupplementalProperties,
            long periodDurationMs) {
        long timescale = parseLong(node, "timescale", parent != null ? parent.timescale : 1);
        long presentationTimeOffset = parseLong(node, "presentationTimeOffset",
                parent != null ? parent.presentationTimeOffset : 0);
        long duration = parseLong(node, "duration", parent != null ? parent.duration : C.TIME_UNSET);
        long startNumber = parseLong(node, "startNumber", parent != null ? parent.startNumber : 1);
        long endNumber =
                parseLastSegmentNumberSupplementalProperty(adaptationSetSupplementalProperties);

        UrlTemplate mediaTemplate = parseUrlTemplate(node, "media",
                parent != null ? parent.mediaTemplate : null);
        UrlTemplate initializationTemplate = parseUrlTemplate(node, "initialization",
                parent != null ? parent.initializationTemplate : null);

        RangedUri initialization = null;
        List<SegmentBase.SegmentTimelineElement> timeline = null;

        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            if (!(node.getChildNodes().item(i) instanceof Element)) {
                continue;
            }
            Element child = (Element)node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("Initialization")) {
                initialization = parseInitialization(child);
            } else if (name.equals("SegmentTimeline")) {
                timeline = parseSegmentTimeline(child, timescale, periodDurationMs);
            }
        }

        if (parent != null) {
            initialization = initialization != null ? initialization : parent.initialization;
            timeline = timeline != null ? timeline : parent.segmentTimeline;
        }

        return new SegmentBase.SegmentTemplate(
                initialization,
                timescale,
                presentationTimeOffset,
                startNumber,
                endNumber,
                duration,
                timeline,
                initializationTemplate,
                mediaTemplate);
    }

    @Nullable
    static UrlTemplate parseUrlTemplate(
            Element node, String name, @Nullable UrlTemplate defaultValue) {
        if (!node.hasAttribute(name)) {
            return defaultValue;
        }
        return UrlTemplate.compile(node.getAttribute(name));
    }

    static DashManifestParser.RepresentationInfo parseRepresentation(
            Element node,
            String baseUrl,
            @Nullable String adaptationSetMimeType,
            @Nullable String adaptationSetCodecs,
            int adaptationSetWidth,
            int adaptationSetHeight,
            float adaptationSetFrameRate,
            int adaptationSetAudioChannels,
            int adaptationSetAudioSamplingRate,
            @Nullable String adaptationSetLanguage,
            List<Descriptor> adaptationSetRoleDescriptors,
            List<Descriptor> adaptationSetAccessibilityDescriptors,
            List<Descriptor> adaptationSetSupplementalProperties,
            @Nullable SegmentBase segmentBase,
            long periodDurationMs) {
        String id = node.getAttribute("id");
        int bandwidth = parseInt(node, "bandwidth", Format.NO_VALUE);

        String mimeType = parseString(node, "mimeType", adaptationSetMimeType);
        String codecs = parseString(node, "codecs", adaptationSetCodecs);
        int width = parseInt(node, "width", adaptationSetWidth);
        int height = parseInt(node, "height", adaptationSetHeight);
        float frameRate = parseFrameRate(getAttribute(node, "frameRate"), adaptationSetFrameRate);
        int audioChannels = adaptationSetAudioChannels;
        int audioSamplingRate = parseInt(node, "audioSamplingRate", adaptationSetAudioSamplingRate);
        String drmSchemeType = null;
        ArrayList<SchemeData> drmSchemeDatas = new ArrayList<>();
        ArrayList<Descriptor> inbandEventStreams = new ArrayList<>();
        ArrayList<Descriptor> supplementalProperties = new ArrayList<>();

        boolean seenFirstBaseUrl = false;
        for (int i = 0; i < node.getChildNodes().getLength(); i++) {
            if (!(node.getChildNodes().item(i) instanceof Element)) {
                continue;
            }
            Element child = (Element)node.getChildNodes().item(i);
            String name = child.getNodeName();
            if (name.equals("BaseURL")) {
                if (!seenFirstBaseUrl) {
                    baseUrl = parseBaseUrl(child, baseUrl);
                    seenFirstBaseUrl = true;
                }
            } else if (name.equals("AudioChannelConfiguration")) {
                audioChannels = parseAudioChannelConfiguration(child);
            } else if (name.equals("SegmentBase")) {
                segmentBase = parseSegmentBase(child, (SegmentBase.SingleSegmentBase) segmentBase);
            } else if (name.equals("SegmentList")) {
                segmentBase = parseSegmentList(child, (SegmentBase.SegmentList) segmentBase, periodDurationMs);
            } else if (name.equals("SegmentTemplate")) {
                segmentBase =
                        parseSegmentTemplate(
                                child,
                                (SegmentBase.SegmentTemplate) segmentBase,
                                adaptationSetSupplementalProperties,
                                periodDurationMs);
            } else if (name.equals("ContentProtection")) {
                Pair<String, SchemeData> contentProtection = parseContentProtection(child);
                if (contentProtection.first != null) {
                    drmSchemeType = contentProtection.first;
                }
                if (contentProtection.second != null) {
                    drmSchemeDatas.add(contentProtection.second);
                }
            } else if (name.equals("InbandEventStream")) {
                inbandEventStreams.add(parseDescriptor(child));
            } else if (name.equals("SupplementalProperty")) {
                supplementalProperties.add(parseDescriptor(child));
            }
        }

        Format format =
                buildFormat(
                        id,
                        mimeType,
                        width,
                        height,
                        frameRate,
                        audioChannels,
                        audioSamplingRate,
                        bandwidth,
                        adaptationSetLanguage,
                        adaptationSetRoleDescriptors,
                        adaptationSetAccessibilityDescriptors,
                        codecs,
                        supplementalProperties);
        segmentBase = segmentBase != null ? segmentBase : new SegmentBase.SingleSegmentBase();

        return new DashManifestParser.RepresentationInfo(format, baseUrl, segmentBase, drmSchemeType,
                drmSchemeDatas, inbandEventStreams, Representation.REVISION_ID_DEFAULT);
    }
}
