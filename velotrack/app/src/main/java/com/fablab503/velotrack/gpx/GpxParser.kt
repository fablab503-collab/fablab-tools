package com.fablab503.velotrack.gpx

import com.fablab503.velotrack.model.LatLon
import java.io.InputStream
import javax.xml.XMLConstants
import javax.xml.parsers.ParserConfigurationException
import javax.xml.parsers.SAXParserFactory
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.SAXException
import org.xml.sax.SAXNotRecognizedException
import org.xml.sax.SAXNotSupportedException
import org.xml.sax.helpers.DefaultHandler

/** Parsed GPX content: `points` and `elevations` have the same size and index. */
data class GpxData(val name: String?, val points: List<LatLon>, val elevations: List<Double?>)

/**
 * Streaming SAX parser for GPX routes (rte/rtept) and tracks (trk/trkseg/trkpt).
 * Pure JVM code: uses javax.xml.parsers, which is available on Android and on the JVM.
 */
object GpxParser {
    /**
     * Reads rte/rtept and trk/trkseg/trkpt (in document order) with SAX.
     * Throws IllegalArgumentException on malformed input or zero points.
     */
    fun parse(input: InputStream): GpxData {
        val factory = SAXParserFactory.newInstance()
        factory.isNamespaceAware = true
        factory.isValidating = false
        setFeatureQuietly(factory, XMLConstants.FEATURE_SECURE_PROCESSING, true)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-general-entities", false)
        setFeatureQuietly(factory, "http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureQuietly(factory, "http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        try {
            factory.isXIncludeAware = false
        } catch (ignored: UnsupportedOperationException) {
            // Not supported by this factory; nothing to do.
        }

        val handler = Handler()
        try {
            val parser = factory.newSAXParser()
            parser.parse(InputSource(input), handler)
        } catch (e: ParserConfigurationException) {
            throw IllegalArgumentException("XML parser unavailable: ${e.message}", e)
        } catch (e: SAXException) {
            throw IllegalArgumentException("Malformed GPX: ${e.message}", e)
        }
        if (handler.points.isEmpty()) {
            throw IllegalArgumentException("GPX contains no route or track points")
        }
        return GpxData(handler.name, handler.points, handler.elevations)
    }

    private fun setFeatureQuietly(factory: SAXParserFactory, feature: String, value: Boolean) {
        try {
            factory.setFeature(feature, value)
        } catch (ignored: ParserConfigurationException) {
        } catch (ignored: SAXNotRecognizedException) {
        } catch (ignored: SAXNotSupportedException) {
        }
    }

    private class Handler : DefaultHandler() {
        val points = ArrayList<LatLon>()
        val elevations = ArrayList<Double?>()
        var name: String? = null

        private var depthTrkOrRte = 0
        private var inMetadata = false
        private var inPoint = false
        private var pointLat = 0.0
        private var pointLon = 0.0
        private var pointEle: Double? = null
        private var inEle = false
        private var inName = false
        private val text = StringBuilder()

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
            when (elementName(localName, qName)) {
                "metadata" -> inMetadata = true
                "trk", "rte" -> depthTrkOrRte++
                "trkpt", "rtept" -> {
                    if (inPoint) throw SAXException("Nested track point")
                    val lat = attributes?.getValue("lat")?.trim()?.toDoubleOrNull()
                    val lon = attributes?.getValue("lon")?.trim()?.toDoubleOrNull()
                    if (lat == null || lon == null || lat.isNaN() || lon.isNaN()) {
                        throw SAXException("Point without numeric lat/lon attributes")
                    }
                    if (lat < -90.0 || lat > 90.0 || lon < -180.0 || lon > 180.0) {
                        throw SAXException("Point coordinates out of range: $lat, $lon")
                    }
                    inPoint = true
                    pointLat = lat
                    pointLon = lon
                    pointEle = null
                }
                "ele" -> if (inPoint) {
                    inEle = true
                    text.setLength(0)
                }
                "name" -> if (depthTrkOrRte > 0 && !inPoint && !inMetadata && name == null) {
                    inName = true
                    text.setLength(0)
                }
            }
        }

        override fun characters(ch: CharArray?, start: Int, length: Int) {
            if ((inEle || inName) && ch != null) {
                text.append(ch, start, length)
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            when (elementName(localName, qName)) {
                "metadata" -> inMetadata = false
                "trk", "rte" -> if (depthTrkOrRte > 0) depthTrkOrRte--
                "trkpt", "rtept" -> if (inPoint) {
                    points.add(LatLon(pointLat, pointLon))
                    elevations.add(pointEle)
                    inPoint = false
                    inEle = false
                }
                "ele" -> if (inEle) {
                    val value = text.toString().trim().toDoubleOrNull()
                    pointEle = if (value != null && value.isFinite()) value else null
                    inEle = false
                    text.setLength(0)
                }
                "name" -> if (inName) {
                    val value = text.toString().trim()
                    name = value.ifEmpty { null }
                    inName = false
                    text.setLength(0)
                }
            }
        }

        /** Local element name; works for namespaced (localName set) and un-namespaced (qName only) documents. */
        private fun elementName(localName: String?, qName: String?): String {
            if (!localName.isNullOrEmpty()) return localName
            if (qName.isNullOrEmpty()) return ""
            val colon = qName.lastIndexOf(':')
            return if (colon >= 0) qName.substring(colon + 1) else qName
        }
    }
}
