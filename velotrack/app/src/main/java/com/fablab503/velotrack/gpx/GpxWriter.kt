package com.fablab503.velotrack.gpx

import com.fablab503.velotrack.model.TrackPoint
import java.io.BufferedWriter
import java.io.OutputStream
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Streaming GPX 1.1 writer. Pure JVM code: no Android imports.
 */
object GpxWriter {
    const val CREATOR = "VeloTrack"

    private const val GPX_NS = "http://www.topografix.com/GPX/1/1"
    private const val XSI_NS = "http://www.w3.org/2001/XMLSchema-instance"
    private const val SCHEMA_LOCATION =
        "http://www.topografix.com/GPX/1/1 http://www.topografix.com/GPX/1/1/gpx.xsd"

    /**
     * Streams GPX 1.1; a new <trkseg> starts whenever point.segment changes. Does not close `out`.
     * The output is flushed at the end.
     */
    fun write(out: OutputStream, trackName: String, points: Sequence<TrackPoint>) {
        val symbols = DecimalFormatSymbols(Locale.ROOT)
        val coordFormat = DecimalFormat("0.000000", symbols)
        val eleFormat = DecimalFormat("0.0", symbols)

        val writer = BufferedWriter(OutputStreamWriter(out, StandardCharsets.UTF_8))
        val iterator = points.iterator()
        val first: TrackPoint? = if (iterator.hasNext()) iterator.next() else null
        val metadataTimeMs = first?.timeMs ?: System.currentTimeMillis()

        writer.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        writer.write("<gpx version=\"1.1\" creator=\"")
        writer.write(CREATOR)
        writer.write("\" xmlns=\"")
        writer.write(GPX_NS)
        writer.write("\" xmlns:xsi=\"")
        writer.write(XSI_NS)
        writer.write("\" xsi:schemaLocation=\"")
        writer.write(SCHEMA_LOCATION)
        writer.write("\">\n")
        writer.write("  <metadata><time>")
        writer.write(isoTime(metadataTimeMs))
        writer.write("</time></metadata>\n")
        writer.write("  <trk>\n")
        writer.write("    <name>")
        writer.write(escapeXml(trackName))
        writer.write("</name>\n")

        var currentSegment: Int? = null
        var point = first
        while (point != null) {
            if (currentSegment == null) {
                writer.write("    <trkseg>\n")
                currentSegment = point.segment
            } else if (point.segment != currentSegment) {
                writer.write("    </trkseg>\n")
                writer.write("    <trkseg>\n")
                currentSegment = point.segment
            }
            writePoint(writer, point, coordFormat, eleFormat)
            point = if (iterator.hasNext()) iterator.next() else null
        }
        if (currentSegment != null) {
            writer.write("    </trkseg>\n")
        }
        writer.write("  </trk>\n")
        writer.write("</gpx>\n")
        writer.flush()
    }

    private fun writePoint(
        writer: BufferedWriter,
        point: TrackPoint,
        coordFormat: DecimalFormat,
        eleFormat: DecimalFormat,
    ) {
        writer.write("      <trkpt lat=\"")
        writer.write(coordFormat.format(point.lat))
        writer.write("\" lon=\"")
        writer.write(coordFormat.format(point.lon))
        writer.write("\">")
        val ele = point.ele
        if (ele != null && ele.isFinite()) {
            writer.write("<ele>")
            writer.write(eleFormat.format(ele))
            writer.write("</ele>")
        }
        writer.write("<time>")
        writer.write(isoTime(point.timeMs))
        writer.write("</time></trkpt>\n")
    }

    /** Escapes &, <, >, " and ' for use in XML text and attribute values. */
    fun escapeXml(s: String): String {
        val sb = StringBuilder(s.length + 16)
        for (c in s) {
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '"' -> sb.append("&quot;")
                '\'' -> sb.append("&apos;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    /** ISO-8601 UTC timestamp with second precision, e.g. 2026-09-07T10:15:30Z. */
    fun isoTime(timeMs: Long): String {
        val instant = Instant.ofEpochMilli(timeMs).truncatedTo(ChronoUnit.SECONDS)
        return DateTimeFormatter.ISO_INSTANT.format(instant)
    }
}
