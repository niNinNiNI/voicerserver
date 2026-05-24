package com.volc.voice.ogg;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Minimal Ogg container demuxer — extracts raw Opus packets from an Ogg byte stream.
 *
 * Ogg page layout (RFC 3533):
 *   0-3:   "OggS"
 *   4:     version (0)
 *   5:     header_type (1=continuation, 2=BOS, 4=EOS)
 *   6-13:  granule_position (int64 LE)
 *   14-17: serial_number (uint32 LE)
 *   18-21: page_sequence (uint32 LE)
 *   22-25: checksum
 *   26:    page_segments (N)
 *   27..:  segment_table[N]
 *   then:  segment data (sum of segment_table)
 *
 * Opus-in-Ogg: page 0 = "OpusHead" (BOS), page 1 = "OpusTags", pages 2+ = audio.
 *
 * Handles cross-page packet continuation: when a segment has length 255 the
 * packet spans into the next page. The partial data is accumulated and prepended
 * when the packet completes in a later page.
 */
public class OggDemuxer {

    private static final int OGG_MAGIC = 0x4F676753; // "OggS"

    private ByteBuffer buf;
    private int pageCount;
    /** Partial packet data accumulated across page boundaries (segment length 255). */
    private byte[] continuedPacket;

    public OggDemuxer() {
        this.buf = ByteBuffer.allocate(0);
        this.pageCount = 0;
        this.continuedPacket = null;
    }

    /**
     * Feed Ogg data and return any extracted Opus packets.
     */
    public List<byte[]> feed(byte[] data) {
        var newBuf = ByteBuffer.allocate(buf.remaining() + data.length);
        newBuf.put(buf);
        newBuf.put(data);
        newBuf.flip();
        buf = newBuf;

        var packets = new ArrayList<byte[]>();
        while (parseOnePage(packets)) {
            // keep going
        }
        return packets;
    }

    public void reset() {
        buf = ByteBuffer.allocate(0);
        pageCount = 0;
        continuedPacket = null;
    }

    private boolean parseOnePage(List<byte[]> out) {
        if (buf.remaining() < 27) return false;

        int magic = buf.getInt(buf.position());
        if (magic != OGG_MAGIC) {
            // Scan for "OggS"
            byte[] remaining = new byte[buf.remaining()];
            buf.get(remaining);
            int idx = indexOf(remaining, new byte[]{'O', 'g', 'g', 'S'}, 1);
            if (idx < 0) {
                buf = ByteBuffer.allocate(0);
                return false;
            }
            buf = ByteBuffer.wrap(remaining, idx, remaining.length - idx);
            if (buf.remaining() < 27) return false;
        }

        int pos = buf.position();
        int headerType = buf.get(pos + 5) & 0xFF;
        int pageSegments = buf.get(pos + 26) & 0xFF;

        int headerEnd = pos + 27 + pageSegments;
        if (buf.limit() < headerEnd) return false;

        // Read segment table
        int dataSize = 0;
        for (int i = 0; i < pageSegments; i++) {
            dataSize += buf.get(pos + 27 + i) & 0xFF;
        }

        int pageEnd = headerEnd + dataSize;
        if (buf.limit() < pageEnd) return false;

        boolean isBOS = (headerType & 0x02) != 0;
        boolean isEOS = (headerType & 0x04) != 0;

        if (!isBOS && !isEOS && dataSize > 0) {
            // First non-BOS page after reset is OpusTags (header), skip it
            if (pageCount == 0) {
                pageCount++;
                buf.position(pageEnd);
                compactBuf();
                return true;
            }
            pageCount++;

            byte[] segmentData = new byte[dataSize];
            buf.position(headerEnd);
            buf.get(segmentData);

            // Extract packets using segment table.
            // Track accumulated data when a packet spans pages (segLen == 255).
            int offset = 0;
            int packetStart = 0;

            for (int i = 0; i < pageSegments; i++) {
                int segLen = buf.get(pos + 27 + i) & 0xFF;
                offset += segLen;

                if (segLen < 255) {
                    // Packet ends here
                    int len = offset - packetStart;
                    byte[] packet;

                    if (continuedPacket != null) {
                        // This packet continues from a previous page
                        packet = new byte[continuedPacket.length + len];
                        System.arraycopy(continuedPacket, 0, packet, 0, continuedPacket.length);
                        System.arraycopy(segmentData, packetStart, packet, continuedPacket.length, len);
                        continuedPacket = null;
                    } else {
                        packet = new byte[len];
                        System.arraycopy(segmentData, packetStart, packet, 0, len);
                    }
                    out.add(packet);
                    packetStart = offset;
                } else if (continuedPacket != null) {
                    // Segment is 255 — packet continues from previous page
                    // and also continues past this segment. Accumulate.
                    byte[] combined = new byte[continuedPacket.length + 255];
                    System.arraycopy(continuedPacket, 0, combined, 0, continuedPacket.length);
                    System.arraycopy(segmentData, packetStart, combined, continuedPacket.length, 255);
                    continuedPacket = combined;
                    packetStart = offset;
                }
            }

            // Handle packet that continues to the next page (last segment was 255)
            if (packetStart < offset) {
                int len = offset - packetStart;
                if (continuedPacket != null) {
                    byte[] combined = new byte[continuedPacket.length + len];
                    System.arraycopy(continuedPacket, 0, combined, 0, continuedPacket.length);
                    System.arraycopy(segmentData, packetStart, combined, continuedPacket.length, len);
                    continuedPacket = combined;
                } else {
                    continuedPacket = new byte[len];
                    System.arraycopy(segmentData, packetStart, continuedPacket, 0, len);
                }
            }
        }

        buf.position(pageEnd);
        compactBuf();
        return true;
    }

    private void compactBuf() {
        if (!buf.hasRemaining()) {
            buf = ByteBuffer.allocate(0);
        } else {
            byte[] remaining = new byte[buf.remaining()];
            buf.get(remaining);
            buf = ByteBuffer.wrap(remaining);
        }
    }

    private static int indexOf(byte[] haystack, byte[] needle, int fromIndex) {
        outer:
        for (int i = fromIndex; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) continue outer;
            }
            return i;
        }
        return -1;
    }
}
