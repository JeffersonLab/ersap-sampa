package org.jlab.epsci.stream.sampa.decoder;

/**
 * Copyright (c) 2021, Jefferson Science Associates, all rights reserved.
 * See LICENSE.txt file.
 * Thomas Jefferson National Accelerator Facility
 * Experimental Physics Software and Computing Infrastructure Group
 * 12000, Jefferson Ave, Newport News, VA 23606
 * Phone : (757)-269-7100
 *
 * @author gurjyan on 8/31/22
 * @project ersap-sampa
 */

import org.jlab.epsci.stream.sampa.SDecoder;
import org.jlab.epsci.stream.sampa.SRingRawEvent;
import org.jlab.epsci.stream.sampa.SType;

import java.nio.ByteBuffer;


/**
 * <p>This class implements parsing the output of the Direct ADC Serialization
 * (DAS) mode of operating the SAMPA board.
 * This is one of 2 standard modes of readout, the other being the DSP mode.</p>
 *
 * <p>There are 80 channels worth of data. Each frame gets parsed into 20 bytes of data,
 * where 5 ByteBuffers each contain 4 bytes. So, if we set a max of 262144 frames to store,
 * then we're using a total of 5,242,880 bytes (1048576 bytes in each of 5 BBs).
 * If this memory is divided into 80 channels, each channel has 65536 bytes of data.
 * </p>
 * <p>
 * Base this class on storing data in the underlying structure of the ByteBuffer
 * instead of an ArrayList&lt;Integer&gt; for efficiency. This avoids the mountains
 * of garbage generated by creating objects for storage in such lists instead of the
 * primitive bytes of the ByteBuffer. It also makes bulk copying much more efficient.
 * Finally, it avoids the constant mutex use of a list.
 */
public class DasDecoder implements SDecoder {

    /**
     * If true, print out debug info.
     */
    private final boolean verbose;

    private final int streamId;

    /**
     * Total number of frames parsed.
     */
    private long frameCount;

    /**
     * Max number of frames that can be locally stored.
     * Also the target "block" size to fit in one raw event.
     * The first and last block will, most likely, be of different size.
     * The first must sync, and who knows when the data ends.
     */
    private static int maxFramesStored;

    /**
     * Max number of bytes in local ByteBuffer.
     * Each buf has 4 bytes/frame.
     */
    private static int maxBufSize;

    /**
     * Got sync on all channels.
     */
    private boolean gotSync = false;

    private long offset = 20000;

    // SYNC positions, index in the ByteBuffer at the sync start - for each stream
    private final int sync_unknown_ = -1;
    private int sync_low_ = sync_unknown_; // Need to do this since we're not looking for a sync at the moment
    private int sync_high_ = sync_unknown_;
    private int sync_low_1_ = sync_unknown_;
    private int sync_high_1_ = sync_unknown_;
    private int sync_2_ = sync_unknown_;

    // Has the sync been found in this channel?
    private boolean syncLow0Found;
    private boolean syncHigh0Found;
    private boolean syncLow1Found;
    private boolean syncHigh1Found;
    private boolean sync2Found;

    private boolean syncHighMiniFound;
    private int sync_high_mini_ = sync_unknown_;


    // Offset into the SRingRawEvent's ByteBuffer array to place data for a stream
    private static final int channel_offset_low_ = 0;
    private static final int channel_offset_high_ = 16;
    private static final int channel_offset_low_1_ = 32;
    private static final int channel_offset_high_1_ = 48;
    private static final int channel_offset_2_ = 64;

    // ByteBuffers for SAMPA half-words (5-bit each) used for constructing ADC values
    public final ByteBuffer sampa_stream_low_;   // for SAMPA0 (or SAMPA 3)
    public final ByteBuffer sampa_stream_high_;
    public final ByteBuffer sampa_stream_low_1_;  // for SAMPA1 (or SAMPA 4)
    public final ByteBuffer sampa_stream_high_1_;
    public final ByteBuffer sampa_stream_2_;      // for SAMPA2 (1 stream only)

    // Clock data is currently unused
//    private final ByteBuffer sampa_stream_clock_0_ = ByteBuffer.allocate(maxBufSize); // for SAMPA0 e-link 10 (clock)
//    private final ByteBuffer sampa_stream_clock_1_ = ByteBuffer.allocate(maxBufSize); // for SAMPA1 e-link 10 (clock)
//    private final ByteBuffer sampa_stream_clock_2_ = ByteBuffer.allocate(maxBufSize); // for SAMPA3 e-link 10 (clock)

    /**
     * SYNC Pattern. The following sequence in the data is the SYNC pattern. 0 = 0x2B5 and 1 = 0x14A.
     * When 0x2B5 is split into 2 5bit words, each is 0x15. When 0x14A is split, each 5bit word is 0xA.
     */
    private static final byte[] SYNC_PATTERN = {0x15, 0x15, 0xa, 0xa, 0x15, 0x15, 0xa, 0xa, 0x15, 0x15, 0xa,
            0xa, 0x15, 0x15, 0xa, 0xa, 0x15, 0x15, 0x15, 0x15, 0xa, 0xa,
            0xa, 0xa, 0x15, 0x15, 0x15, 0x15, 0xa, 0xa, 0xa, 0xa};


    /**
     * Constructor. No debug output. 1024 samples for statistics. 8192 bytes storage (2048 frames).
     */
    public DasDecoder() {
        this(false, 0, 8192);
    }

    /**
     * Constructor.
     *
     * @param verbose          if true, printout debug messages.
     * @param streamId         if of stream this decoder is working on.
     * @param rawEventByteSize max number of bytes this decoder will be able to store
     *                         before needing to transfer it to an event.  This must be
     *                         no larger than the internal buffer size of a raw event.
     */
    public DasDecoder(boolean verbose, int streamId, int rawEventByteSize) {
        this.verbose = verbose;
        this.streamId = streamId;

        // Round up buffer size to 4 byte boundary.
        // Each single source rawEvent has 80 buffers whereas this decoder
        // has 5 buffers for the same data so correct for that.
        maxBufSize = ((rawEventByteSize + 3) / 4) * 4 * (80 / 5);
        maxFramesStored = maxBufSize / 4;    // 4 bytes per frame in each local buffer

        System.out.print("Constructor: each buffer is of size " + maxBufSize + " bytes, max frames stored = " + maxFramesStored);

        sampa_stream_low_ = ByteBuffer.allocate(maxBufSize);
        sampa_stream_high_ = ByteBuffer.allocate(maxBufSize);
        sampa_stream_low_1_ = ByteBuffer.allocate(maxBufSize);
        sampa_stream_high_1_ = ByteBuffer.allocate(maxBufSize);
        sampa_stream_2_ = ByteBuffer.allocate(maxBufSize);
    }


    /**
     * Get the max number of frames that can be stored in a SRingRawEvent in DAS mode.
     *
     * @return max number of frames that can be stored in a SRingRawEvent in DAS mode.
     */
    public static int getMaxFramesStored() {
        return maxFramesStored;
    }


    /**
     * {@inheritDoc}
     */
    public void reSync() {
        gotSync = syncLow0Found = syncLow1Found = syncHigh0Found = syncHigh1Found = sync2Found = false;
        clearSyncOffsets();
    }

    /**
     * {@inheritDoc}
     */
    public SType getSampaType() {
        return SType.DAS;
    }

    /**
     * Not applicable to DAS mode. Do nothing.
     */
    public void printStats() {
    }

    /**
     * {@inheritDoc}
     */
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * {@inheritDoc}
     */
    public boolean isFull() {
        // Full if anyone of the buffers doesn't have enough room to read another frame.
        // Different buffers may have differing amounts of data depending on whether
        // in the last data transfer it was closer to a multiple of 32. It will also
        // depend on the initial sync offsets.
        return ((sampa_stream_low_.remaining() < 4) ||
                (sampa_stream_high_.remaining() < 4) ||
                (sampa_stream_low_1_.remaining() < 4) ||
                (sampa_stream_high_1_.remaining() < 4) ||
                (sampa_stream_2_.remaining() < 4));
    }


    //--------------------------------------------------
    // Interface methods irrelevant to DAS mode
    //--------------------------------------------------

    /**
     * Not applicable to DAS mode.
     *
     * @return -1.
     */
    public int getBlockCount() {
        return -1;
    }

    /**
     * Not applicable to DAS mode.
     *
     * @return 0
     */
    public int incrementBlockCount() {
        return 0;
    }


    //--------------------------------------------------


    /**
     * After a sync event has been triggered and read, the offsets
     * due to synchronization are necessary on the first data read,
     * but must be cleared for subsequent reads as the sync offsets
     * will have been taken into account.
     */
    void clearSyncOffsets() {
        sync_low_ = sync_high_ = sync_low_1_ = sync_high_1_ = sync_2_ = -1;
    }


    /**
     * Clear all stored data.
     */
    private void dumpData() {
        sampa_stream_low_.clear();
        sampa_stream_high_.clear();
        sampa_stream_low_1_.clear();
        sampa_stream_high_1_.clear();
        sampa_stream_2_.clear();

//        sampa_stream_clock_0_.clear();
//        sampa_stream_clock_1_.clear();
//        sampa_stream_clock_2_.clear();
    }


    /**
     * Decode a single frame of streamed data from SAMPA card.
     *
     * @param gbt_frame frame of streamed data.
     * @param rawEvent  object from ring buffer for storing data and passing to next ring consumer.
     *                  Not used in DAS mode.
     * @throws Exception thrown if storage limit of rawEvent is reached.
     *                   This can happen if looking for a sync from each stream, but only some are found, while
     *                   at the same time the storage limit for streamed data has been reached.
     *                   Or it can happen in the routine decoding of frames.
     *                   If internal logic is sound, this should never happen.
     */
    public void decodeSerial(int[] gbt_frame, SRingRawEvent rawEvent) throws Exception {

        // First check to make sure we have room for more parsed data ...
        if (isFull()) {
            if (!gotSync) {
                System.out.println("decodeSerial " + streamId + ", data storage limit reached but only partial syncs found");
                throw new Exception("Data storage limit reached in DAS decoder, but only partial syncs found");
            }

            // Data is probably good, but no room to store it. Something wrong with internal logic.
            System.out.println("decodeSerial " + streamId + ", data storage limit reached");
            throw new Exception("Data storage limit reached in DAS decoder");
        }

        // Get and parse 1 frame of data (adds 20 bytes of data to this decoder)
        getHalfWords(gbt_frame);

        // Initially there is no sync so the first thing we need to do is look for it.
        // Once found and the data starts flowing, all data values are good.
        // Perhaps in the future, another sync signal will be sent at some unknown time.
        // Tests show, however, that this KILLS performance. So skip if for now.

        if (!gotSync) {

            // Note that treadout is set to drive the sync control pattern for 16 cycles of 10 bit quantities.
            // That means that the getSyncCount() method must match up 32, 5-bit values for each 1/2 chip
            // (each ByteBuffer). Thus the 32, 5-bit values in SYNC_PATTERN.
            // Since each frame produces 4, 5-bit values / ByteBuffer, we need 32/4 = 8 frames of data to
            // hold a sync pattern.

//System.out.println("\ndecodeSerial: Don't have a sync yet\n");

            // Update gotSync by looking for syncs
            getSyncCount();

            // If we still don't have all syncs, read more data and try looking again
        } else {
            rawEvent.incrementFramesStored();
        }

        frameCount++;
    }


    /**
     * {@inheritDoc}
     */
    public void transferData(SRingRawEvent rawEvent) {
        getAdcValues(rawEvent);
    }


    /**
     * Transform part of the frame into a 5 bit half-word for storage in a ByteBuffer.
     *
     * @param d   integer from frame.
     * @param src bit of interest in d arg (number of bits to shift right and then &amp; 1).
     * @param trg bit of output to set with value of d's bit of interest.
     * @return integer resulting from this transformation.
     */
    private int bit(int d, int src, int trg) {
        return ((d >>> src) & 1) << trg;
    }

    // Ed's method does NOT work when src = 31. It runs into issues with sign extension
    // private int bit(int d, int src, int trg) { return ((d & (1 << src)) >> src) << trg; }

    /**
     * <p>Parse incoming frames and store as 5 bit values (1 half word) in ByteBuffers.
     * Each 5 bit values is stored in 1 byte as opposed to the original C++
     * version which stored them in 32 bit ints.</p>
     *
     * <p>Each frame is an array of 4 ints (16 bytes) which contains 100 bits
     * of actual ADC data (not counting time info).
     * This translates into 100/(8 bits/byte) --> 12.5 bytes --> 3.125 ints.
     * When unpacked and stored, each 5 bit quantity is placed into 1 byte.
     * Thus the frame of 16 bytes expands into 5*4 = 20 bytes --> 5 ints held in 5 ByteBuffers (4 bytes in each). </p>
     *
     * @param gf frame of data to parse.
     */
    private void getHalfWords(int[] gf) {

        // extract the 4 halfwords for SAMPA0 higher data stream and insert them into the stream_high vector
        byte b1 = (byte) (bit(gf[1], 7, 4) | bit(gf[1], 3, 3) | bit(gf[0], 31, 2) | bit(gf[0], 27, 1) | bit(gf[0], 23, 0));
        byte b2 = (byte) (bit(gf[1], 6, 4) | bit(gf[1], 2, 3) | bit(gf[0], 30, 2) | bit(gf[0], 26, 1) | bit(gf[0], 22, 0));
        byte b3 = (byte) (bit(gf[1], 5, 4) | bit(gf[1], 1, 3) | bit(gf[0], 29, 2) | bit(gf[0], 25, 1) | bit(gf[0], 21, 0));
        byte b4 = (byte) (bit(gf[1], 4, 4) | bit(gf[1], 0, 3) | bit(gf[0], 28, 2) | bit(gf[0], 24, 1) | bit(gf[0], 20, 0));

        //System.out.println("getHalfWords " + streamId + ": room in BB = " + sampa_stream_high_.remaining());

        sampa_stream_high_.put(b1);
        sampa_stream_high_.put(b2);
        sampa_stream_high_.put(b3);
        sampa_stream_high_.put(b4);

        //if (streamId == 2) System.out.println(streamId + ": --> buf pos = " + sampa_stream_high_.position());
        //if (streamId == 2) System.out.println(streamId + ": --> " + b1 + " " + b2 + " " + b3 + " " + b4);

        // extract the 4 halfwords for SAMPA0 lower data stream and insert them into the stream_low vector
        b1 = (byte) (bit(gf[0], 19, 4) | bit(gf[0], 15, 3) | bit(gf[0], 11, 2) | bit(gf[0], 7, 1) | bit(gf[0], 3, 0));
        b2 = (byte) (bit(gf[0], 18, 4) | bit(gf[0], 14, 3) | bit(gf[0], 10, 2) | bit(gf[0], 6, 1) | bit(gf[0], 2, 0));
        b3 = (byte) (bit(gf[0], 17, 4) | bit(gf[0], 13, 3) | bit(gf[0], 9, 2) | bit(gf[0], 5, 1) | bit(gf[0], 1, 0));
        b4 = (byte) (bit(gf[0], 16, 4) | bit(gf[0], 12, 3) | bit(gf[0], 8, 2) | bit(gf[0], 4, 1) | bit(gf[0], 0, 0));

        sampa_stream_low_.put(b1);
        sampa_stream_low_.put(b2);
        sampa_stream_low_.put(b3);
        sampa_stream_low_.put(b4);

        //System.out.println(streamId + ": --> " + b1 + " " + b2 + " " + b3 + " " + b4);

        // extract the 4 halfwords for SAMPA1 higher data stream and insert them into the stream_high vector
        b1 = (byte) (bit(gf[2], 19, 4) | bit(gf[2], 15, 3) | bit(gf[2], 11, 2) | bit(gf[2], 7, 1) | bit(gf[2], 3, 0));
        b2 = (byte) (bit(gf[2], 18, 4) | bit(gf[2], 14, 3) | bit(gf[2], 10, 2) | bit(gf[2], 6, 1) | bit(gf[2], 2, 0));
        b3 = (byte) (bit(gf[2], 17, 4) | bit(gf[2], 13, 3) | bit(gf[2], 9, 2) | bit(gf[2], 5, 1) | bit(gf[2], 1, 0));
        b4 = (byte) (bit(gf[2], 16, 4) | bit(gf[2], 12, 3) | bit(gf[2], 8, 2) | bit(gf[2], 4, 1) | bit(gf[2], 0, 0));

        sampa_stream_high_1_.put(b1);
        sampa_stream_high_1_.put(b2);
        sampa_stream_high_1_.put(b3);
        sampa_stream_high_1_.put(b4);

        //System.out.println(streamId + ": --> " + b1 + " " + b2 + " " + b3 + " " + b4);

        // extract the 4 halfwords for SAMPA1 lower data stream and insert them into the stream_low vector
        b1 = (byte) (bit(gf[1], 31, 4) | bit(gf[1], 27, 3) | bit(gf[1], 23, 2) | bit(gf[1], 19, 1) | bit(gf[1], 15, 0));
        b2 = (byte) (bit(gf[1], 30, 4) | bit(gf[1], 26, 3) | bit(gf[1], 22, 2) | bit(gf[1], 18, 1) | bit(gf[1], 14, 0));
        b3 = (byte) (bit(gf[1], 29, 4) | bit(gf[1], 25, 3) | bit(gf[1], 21, 2) | bit(gf[1], 17, 1) | bit(gf[1], 13, 0));
        b4 = (byte) (bit(gf[1], 28, 4) | bit(gf[1], 24, 3) | bit(gf[1], 20, 2) | bit(gf[1], 16, 1) | bit(gf[1], 12, 0));

        sampa_stream_low_1_.put(b1);
        sampa_stream_low_1_.put(b2);
        sampa_stream_low_1_.put(b3);
        sampa_stream_low_1_.put(b4);

        //System.out.println(streamId + ": --> " + b1 + " " + b2 + " " + b3 + " " + b4);

        // extract the 4 halfwords for SAMPA2 data stream and insert them into the stream_vector
        b1 = (byte) (bit(gf[3], 11, 4) | bit(gf[3], 7, 3) | bit(gf[3], 3, 2) | bit(gf[2], 31, 1) | bit(gf[2], 27, 0));
        b2 = (byte) (bit(gf[3], 10, 4) | bit(gf[3], 6, 3) | bit(gf[3], 2, 2) | bit(gf[2], 30, 1) | bit(gf[2], 26, 0));
        b3 = (byte) (bit(gf[3], 9, 4) | bit(gf[3], 5, 3) | bit(gf[3], 1, 2) | bit(gf[2], 29, 1) | bit(gf[2], 25, 0));
        b4 = (byte) (bit(gf[3], 8, 4) | bit(gf[3], 4, 3) | bit(gf[3], 0, 2) | bit(gf[2], 28, 1) | bit(gf[2], 24, 0));

        sampa_stream_2_.put(b1);
        sampa_stream_2_.put(b2);
        sampa_stream_2_.put(b3);
        sampa_stream_2_.put(b4);

        //System.out.println(streamId + ": --> " + b1 + " " + b2 + " " + b3 + " " + b4 + "\n");
        //System.out.println("getHalfWords END " + streamId + ": room in BB = " + sampa_stream_high_.remaining());
    }


    /**
     * Parse incoming frames and store as 5 bit values (1 half word) in ByteBuffers.
     * Each 5 bit values is stored in 1 byte as opposed to the original C++
     * version which stored them in 32 bit ints.
     *
     * @param gf frame of data - array of 4 ints which contains 112 bytes of actual data.
     */
    private void getHalfWordsOrig(int[] gf) {

        // extract the 4 halfwords for SAMPA0 higher data stream and insert them into the stream_high vector
        sampa_stream_high_.put((byte) (bit(gf[1], 7, 4) | bit(gf[1], 3, 3) | bit(gf[0], 31, 2) | bit(gf[0], 27, 1) | bit(gf[0], 23, 0)));
        sampa_stream_high_.put((byte) (bit(gf[1], 6, 4) | bit(gf[1], 2, 3) | bit(gf[0], 30, 2) | bit(gf[0], 26, 1) | bit(gf[0], 22, 0)));
        sampa_stream_high_.put((byte) (bit(gf[1], 5, 4) | bit(gf[1], 1, 3) | bit(gf[0], 29, 2) | bit(gf[0], 25, 1) | bit(gf[0], 21, 0)));
        sampa_stream_high_.put((byte) (bit(gf[1], 4, 4) | bit(gf[1], 0, 3) | bit(gf[0], 28, 2) | bit(gf[0], 24, 1) | bit(gf[0], 20, 0)));

        // extract the 4 halfwords for SAMPA0 lower data stream and insert them into the stream_low vector
        sampa_stream_low_.put((byte) (bit(gf[0], 19, 4) | bit(gf[0], 15, 3) | bit(gf[0], 11, 2) | bit(gf[0], 7, 1) | bit(gf[0], 3, 0)));
        sampa_stream_low_.put((byte) (bit(gf[0], 18, 4) | bit(gf[0], 14, 3) | bit(gf[0], 10, 2) | bit(gf[0], 6, 1) | bit(gf[0], 2, 0)));
        sampa_stream_low_.put((byte) (bit(gf[0], 17, 4) | bit(gf[0], 13, 3) | bit(gf[0], 9, 2) | bit(gf[0], 5, 1) | bit(gf[0], 1, 0)));
        sampa_stream_low_.put((byte) (bit(gf[0], 16, 4) | bit(gf[0], 12, 3) | bit(gf[0], 8, 2) | bit(gf[0], 4, 1) | bit(gf[0], 0, 0)));

        // extract the 4 halfwords for SAMPA1 higher data stream and insert them into the stream_high vector
        sampa_stream_high_1_.put((byte) (bit(gf[2], 19, 4) | bit(gf[2], 15, 3) | bit(gf[2], 11, 2) | bit(gf[2], 7, 1) | bit(gf[2], 3, 0)));
        sampa_stream_high_1_.put((byte) (bit(gf[2], 18, 4) | bit(gf[2], 14, 3) | bit(gf[2], 10, 2) | bit(gf[2], 6, 1) | bit(gf[2], 2, 0)));
        sampa_stream_high_1_.put((byte) (bit(gf[2], 17, 4) | bit(gf[2], 13, 3) | bit(gf[2], 9, 2) | bit(gf[2], 5, 1) | bit(gf[2], 1, 0)));
        sampa_stream_high_1_.put((byte) (bit(gf[2], 16, 4) | bit(gf[2], 12, 3) | bit(gf[2], 8, 2) | bit(gf[2], 4, 1) | bit(gf[2], 0, 0)));

        // extract the 4 halfwords for SAMPA1 lower data stream and insert them into the stream_low vector
        sampa_stream_low_1_.put((byte) (bit(gf[1], 31, 4) | bit(gf[1], 27, 3) | bit(gf[1], 23, 2) | bit(gf[1], 19, 1) | bit(gf[1], 15, 0)));
        sampa_stream_low_1_.put((byte) (bit(gf[1], 30, 4) | bit(gf[1], 26, 3) | bit(gf[1], 22, 2) | bit(gf[1], 18, 1) | bit(gf[1], 14, 0)));
        sampa_stream_low_1_.put((byte) (bit(gf[1], 29, 4) | bit(gf[1], 25, 3) | bit(gf[1], 21, 2) | bit(gf[1], 17, 1) | bit(gf[1], 13, 0)));
        sampa_stream_low_1_.put((byte) (bit(gf[1], 28, 4) | bit(gf[1], 24, 3) | bit(gf[1], 20, 2) | bit(gf[1], 16, 1) | bit(gf[1], 12, 0)));

        // extract the 4 halfwords for SAMPA2 data stream and insert them into the stream_vector
        sampa_stream_2_.put((byte) (bit(gf[3], 11, 4) | bit(gf[3], 7, 3) | bit(gf[3], 3, 2) | bit(gf[2], 31, 1) | bit(gf[2], 27, 0)));
        sampa_stream_2_.put((byte) (bit(gf[3], 10, 4) | bit(gf[3], 6, 3) | bit(gf[3], 2, 2) | bit(gf[2], 30, 1) | bit(gf[2], 26, 0)));
        sampa_stream_2_.put((byte) (bit(gf[3], 9, 4) | bit(gf[3], 5, 3) | bit(gf[3], 1, 2) | bit(gf[2], 29, 1) | bit(gf[2], 25, 0)));
        sampa_stream_2_.put((byte) (bit(gf[3], 8, 4) | bit(gf[3], 4, 3) | bit(gf[3], 0, 2) | bit(gf[2], 28, 1) | bit(gf[2], 24, 0)));

        // SAMPA e-link 10 (clock) streams
        // Manual says that this clock is not guaranteed to be related to the data.
        // Thus it can't be used as a marker in the data.
//        sampa_stream_clock_0_.put((byte)(bit(gf[1], 11, 0)));
//        sampa_stream_clock_0_.put((byte)(bit(gf[1], 10, 0)));
//        sampa_stream_clock_0_.put((byte)(bit(gf[1],  9, 0)));
//        sampa_stream_clock_0_.put((byte)(bit(gf[1],  8, 0)));
//
//        sampa_stream_clock_1_.put((byte)(bit(gf[2], 23, 0)));
//        sampa_stream_clock_1_.put((byte)(bit(gf[2], 22, 0)));
//        sampa_stream_clock_1_.put((byte)(bit(gf[2], 21, 0)));
//        sampa_stream_clock_1_.put((byte)(bit(gf[2], 20, 0)));
//
//        sampa_stream_clock_2_.put((byte)(bit(gf[3], 15, 0)));
//        sampa_stream_clock_2_.put((byte)(bit(gf[3], 14, 0)));
//        sampa_stream_clock_2_.put((byte)(bit(gf[3], 13, 0)));
//        sampa_stream_clock_2_.put((byte)(bit(gf[3], 12, 0)));
//

        // for debug printing of data streams
        if (verbose) {

            long lowInt = 0xffffffffL;

            // Get rid of any negative values
            System.out.println(" w3 = " + ((long) gf[3] & lowInt) + " w2 = " + ((long) gf[2] & lowInt) +
                    " w1 = " + ((long) gf[1] & lowInt) + " w0 = " + ((long) gf[0] & lowInt));

            long bw1_7_4 = ((long) bit(gf[1], 7, 4) & lowInt);
            long bw1_3_3 = ((long) bit(gf[1], 3, 3) & lowInt);
            long bw0_31_2 = ((long) bit(gf[0], 31, 2) & lowInt);
            long bw0_27_1 = ((long) bit(gf[0], 27, 1) & lowInt);
            long bw0_23_0 = ((long) bit(gf[0], 23, 0) & lowInt);
            System.out.println(" bw1_7_4  = " + bw1_7_4 + " bw1_3_3 = " + bw1_3_3 + " bw0_31_2 = " + bw0_31_2 +
                    " bw0_27_1 = " + bw0_27_1 + " bw0_23_0 = " + bw0_23_0);

            long bw1_6_4 = ((long) bit(gf[1], 6, 4) & lowInt);
            long bw1_2_3 = ((long) bit(gf[1], 2, 3) & lowInt);
            long bw0_30_2 = ((long) bit(gf[0], 30, 2) & lowInt);
            long bw0_26_1 = ((long) bit(gf[0], 26, 1) & lowInt);
            long bw0_22_0 = ((long) bit(gf[0], 22, 0) & lowInt);
            System.out.println(" bw1_6_4  = " + bw1_6_4 + " bw1_2_3 = " + bw1_2_3 + " bw0_30_2 = " + bw0_30_2 +
                    " bw0_26_1 = " + bw0_26_1 + " bw0_22_0 = " + bw0_22_0);

            long bw1_5_4 = ((long) bit(gf[1], 5, 4) & lowInt);
            long bw1_1_3 = ((long) bit(gf[1], 1, 3) & lowInt);
            long bw0_29_2 = ((long) bit(gf[0], 29, 2) & lowInt);
            long bw0_25_1 = ((long) bit(gf[0], 25, 1) & lowInt);
            long bw0_21_0 = ((long) bit(gf[0], 21, 0) & lowInt);
            System.out.println(" bw1_5_4  = " + bw1_5_4 + " bw1_1_3 = " + bw1_1_3 + " bw0_29_2 = " + bw0_29_2 +
                    " bw0_25_1 = " + bw0_25_1 + " bw0_21_0 = " + bw0_21_0);

            long bw1_4_4 = ((long) bit(gf[1], 4, 4) & lowInt);
            long bw1_0_3 = ((long) bit(gf[1], 0, 3) & lowInt);
            long bw0_28_2 = ((long) bit(gf[0], 28, 2) & lowInt);
            long bw0_24_1 = ((long) bit(gf[0], 24, 1) & lowInt);
            long bw0_20_0 = ((long) bit(gf[0], 20, 0) & lowInt);
            System.out.println(" bw1_4_4  = " + bw1_4_4 + " bw1_0_3 = " + bw1_0_3 + " bw0_28_2 = " + bw0_28_2 +
                    " bw0_24_1 = " + bw0_24_1 + " bw0_20_0 = " + bw0_20_0);

            long high_1 = bw1_7_4 | bw1_3_3 | bw0_31_2 | bw0_27_1 | bw0_23_0;
            long high_2 = bw1_6_4 | bw1_2_3 | bw0_30_2 | bw0_26_1 | bw0_22_0;
            long high_3 = bw1_5_4 | bw1_1_3 | bw0_29_2 | bw0_25_1 | bw0_21_0;
            long high_4 = bw1_4_4 | bw1_0_3 | bw0_28_2 | bw0_24_1 | bw0_20_0;
            System.out.println(" high_1 = " + high_1 + " high_2 = " + high_2 + " high_3 = " + high_3 + " high_4 = " + high_4);


            long bw0_19_4 = ((long) bit(gf[0], 19, 4) & lowInt);
            long bw0_15_3 = ((long) bit(gf[0], 15, 3) & lowInt);
            long bw0_11_2 = ((long) bit(gf[0], 11, 2) & lowInt);
            long bw0_7_1 = ((long) bit(gf[0], 7, 1) & lowInt);
            long bw0_3_0 = ((long) bit(gf[0], 3, 0) & lowInt);
            System.out.println(" bw0_19_4 = " + bw0_19_4 + " bw0_15_3 = " + bw0_15_3 + " bw0_11_2 = " + bw0_11_2 +
                    " bw0_7_1  = " + bw0_7_1 + " bw0_3_0 = " + bw0_3_0);

            long bw0_18_4 = ((long) bit(gf[0], 18, 4) & lowInt);
            long bw0_14_3 = ((long) bit(gf[0], 14, 3) & lowInt);
            long bw0_10_2 = ((long) bit(gf[0], 10, 2) & lowInt);
            long bw0_6_1 = ((long) bit(gf[0], 6, 1) & lowInt);
            long bw0_2_0 = ((long) bit(gf[0], 2, 0) & lowInt);
            System.out.println(" bw0_18_4 = " + bw0_18_4 + " bw0_14_3 = " + bw0_14_3 + " bw0_10_2 = " + bw0_10_2 +
                    " bw0_6_1  = " + bw0_6_1 + " bw0_2_0 = " + bw0_2_0);

            long bw0_17_4 = ((long) bit(gf[0], 17, 4) & lowInt);
            long bw0_13_3 = ((long) bit(gf[0], 13, 3) & lowInt);
            long bw0_9_2 = ((long) bit(gf[0], 9, 2) & lowInt);
            long bw0_5_1 = ((long) bit(gf[0], 5, 1) & lowInt);
            long bw0_1_0 = ((long) bit(gf[0], 1, 0) & lowInt);
            System.out.println(" bw0_17_4 = " + bw0_17_4 + " bw0_13_3 = " + bw0_13_3 + " bw0_9_2 = " + bw0_9_2 +
                    " bw0_5_1  = " + bw0_5_1 + " bw0_1_0 = " + bw0_1_0);

            long bw0_16_4 = ((long) bit(gf[0], 16, 4) & lowInt);
            long bw0_12_3 = ((long) bit(gf[0], 12, 3) & lowInt);
            long bw0_8_2 = ((long) bit(gf[0], 8, 2) & lowInt);
            long bw0_4_1 = ((long) bit(gf[0], 4, 1) & lowInt);
            long bw0_0_0 = ((long) bit(gf[0], 0, 0) & lowInt);
            System.out.println(" bw0_16_4 = " + bw0_16_4 + " bw0_12_3 = " + bw0_12_3 + " bw0_8_2 = " + bw0_8_2 +
                    " bw0_4_1  = " + bw0_4_1 + " bw0_0_0 = " + bw0_0_0);

            long low_1 = bw0_19_4 | bw0_15_3 | bw0_11_2 | bw0_7_1 | bw0_3_0;
            long low_2 = bw0_18_4 | bw0_14_3 | bw0_10_2 | bw0_6_1 | bw0_2_0;
            long low_3 = bw0_17_4 | bw0_13_3 | bw0_9_2 | bw0_5_1 | bw0_1_0;
            long low_4 = bw0_16_4 | bw0_12_3 | bw0_8_2 | bw0_4_1 | bw0_0_0;
            System.out.println(" low_1 = " + low_1 + " low_2 = " + low_2 + " low_3 = " + low_3 + " low_4 = " + low_4);
        }
    }


    /**
     * <p>Find the sync position for all streams and store internally.
     * A returned value of 5 indicates that all of the 5 total streams have found
     * the sync signal. Any value less than 5 indicates that more data needs to be
     * read so that the remaining stream(s) can find a sync.</p>
     * <p>
     * If all syncs have already been found, this method returns without doing
     * any work. To begin the search for another sync, the user must first
     * call {@link #reSync()}.
     *
     * @return the number of buffers that found a sync pattern (5 max).
     */
    private int getSyncCount() {

        if (gotSync) return 5;

        // Start by assuming we have all syncs
        int count = 5;

        // If a sync is already found for this stream, do NOT repeat the search
        if (!syncLow0Found) {
            sync_low_ = findSync(sampa_stream_low_, 0);
            if (sync_low_ == sync_unknown_) {
                // We don't have a sync on this stream
                count--;
            } else {
                // Mark sync as having been found so no redundant work is done
                // when this method is called again.
                syncLow0Found = true;
            }
        }

        if (!syncHigh0Found) {
            sync_high_ = findSync(sampa_stream_high_, 0);
            if (sync_high_ == sync_unknown_) {
                count--;
            } else {
                syncHigh0Found = true;
            }
        }

        if (!syncLow1Found) {
            sync_low_1_ = findSync(sampa_stream_low_1_, 0);
            if (sync_low_1_ == sync_unknown_) {
                count--;
            } else {
                syncLow1Found = true;
            }
        }

        if (!syncHigh1Found) {
            sync_high_1_ = findSync(sampa_stream_high_1_, 0);
            if (sync_high_1_ == sync_unknown_) {
                count--;
            } else {
                syncHigh1Found = true;
            }
        }

        if (!sync2Found) {
            sync_2_ = findSync(sampa_stream_2_, 0);
            if (sync_2_ == sync_unknown_) {
                count--;
            } else {
                sync2Found = true;
            }
        }

        gotSync = (count == 5);

        //if (verbose && gotSync && streamId == 2) {
        if (gotSync && streamId == 2) {
            System.out.println("SYNC " + streamId + ": SAMPA0 Stream Low  = " + sync_low_);
            System.out.println("SYNC " + streamId + ": SAMPA0 Stream High = " + sync_high_);
            System.out.println("SYNC " + streamId + ": SAMPA1 Stream Low  = " + sync_low_1_);
            System.out.println("SYNC " + streamId + ": SAMPA1 Stream High = " + sync_high_1_);
            System.out.println("SYNC " + streamId + ": SAMPA2 Stream      = " + sync_2_);
        }

        return count;
    }


    /**
     * Loop over the 5-bit half-word stream and search for the SYNC pattern.
     * and find the position of the last SYNC pattern value (0xA) in the stream.
     * The number of bytes to skip to get to real data = returned position + 1.
     *
     * @param data       input data "stream".
     * @param startIndex index of byte to start the search
     * @return position of the last SYNC pattern value (0xA) in the data buffer or -1 if unknown.
     */
    private int findSync(ByteBuffer data, int startIndex) {
        int index = 0;

        for (int i = 0; i < data.limit(); i++) {
            if (data.get(i) == SYNC_PATTERN[index + startIndex]) {
//if (index > 27) System.out.println("findSync : " + index + " 0x" + Integer.toHexString((int)(data.get(i))) + " - 0x" + Integer.toHexString(SYNC_PATTERN[index + startIndex]));
                index++;
            } else {
                index = 0;
            }

            if (index == (32 - startIndex)) {
                return i;
            }
        }
        return sync_unknown_;
    }


    /**
     * Find all ADC values from parsed data so far.
     *
     * @param rawEvent object in which to store the ADC values.
     */
    public void getAdcValues(SRingRawEvent rawEvent) {
        extractAdcValues(1, sampa_stream_low_, sync_low_ + 1, channel_offset_low_, rawEvent);
        extractAdcValues(2, sampa_stream_high_, sync_high_ + 1, channel_offset_high_, rawEvent);
        extractAdcValues(3, sampa_stream_low_1_, sync_low_1_ + 1, channel_offset_low_1_, rawEvent);
        extractAdcValues(4, sampa_stream_high_1_, sync_high_1_ + 1, channel_offset_high_1_, rawEvent);
        extractAdcValues(5, sampa_stream_2_, sync_2_ + 1, channel_offset_2_, rawEvent);
        // The offsets must only be used once before being cleared
        clearSyncOffsets();
    }


    /**
     * Extract the ADC values from the data stream.
     *
     * @param data           data from a single stream.
     * @param startPos       starting position in data (# of bad bytes to skip).
     * @param channel_offset offset into the array of buffers at which to start placing ADC values.
     * @param rawEvent       object into which to write the extracted ADC values.
     */
    private void extractAdcValues(int id, ByteBuffer data, int startPos, int channel_offset, SRingRawEvent rawEvent) {

        // Calculate the number of full Adc channel entries in the stream.
        // Each data buffer contains data from 1 chip in split mode.
        // (16 channels where each channel has 2 sequential 5 bit (1 byte) halfwords
        // entries which needs to be combined into one ADC value)

        // At this point data has been added, byte by byte, to the data ByteBuffer.
        // It's position tells how many bytes have been added.
        // It's limit will be its capacity.
        int dataBytes = data.position();

        int maxSamples = (dataBytes - startPos) / 32;
        // Did we read all the data?
        boolean readAll = 32 * maxSamples == (dataBytes - startPos);

//        if (verbose)
        // Array of buffers in which to place data
        ByteBuffer[] dataBufs = rawEvent.getData();

//        if (id == 1 && streamId == 2) {
//            System.out.println("extractAdcValues stream = " + streamId + " : BB " + id + ": samples in substream: " +
//                                       maxSamples + ", readAll = " + readAll + ", real data bytes : " + (dataBytes - startPos) +
//                                       ", all bytes = " + dataBytes + ", startPos = " + startPos);
//        }

//        if (id == 1 && streamId == 2) {
//            System.out.println("dataBufs[0].pos = " + dataBufs[0].position() + ", lim = " + dataBufs[0].limit());
//        }

        // Loop over the samples
        for (int numSamples = 0; numSamples < maxSamples; numSamples++) {
            // Extract the 16 channels
            int offset = startPos + numSamples * 32;

            for (int channel = 0; channel < 16; channel++) {
                // Reconstructing a 10-bit value from 2, 5-bit values.
                // This will fit into a short w/out having to worry about sign extension.
//if (streamId == 2)  System.out.println("get stream " + streamId + " at " + (offset + channel * 2 + 1));
                short adc_value = (short) (((data.get(offset + channel * 2 + 1)) << 5) | (data.get(offset + channel * 2)));
//if ((id == 1) && (channel == 0) && (streamId == 2) && numSamples < 30) { //vg
//                System.out.print(Integer.toHexString(adc_value) + "  " );
//                System.out.print(adc_value + "  " ); //vg
//} //vg
//                System.out.println("stream = "+ streamId + ", channel = "+ channel+" "+ Integer.toHexString(adc_value));


                // Read this into a SRingRawEvent, not into local memory ...
                try {
                    // The buffers in the raw event object are all set to little endian
                    dataBufs[channel + channel_offset].putShort(adc_value);
                } catch (Exception e) {
                    System.out.println("Failed on dataBufs[" + (channel + channel_offset) + "], remaining = " + dataBufs[channel + channel_offset].remaining());
                    //e.printStackTrace();
                }
            }
        }

//if (id == 1 && streamId == 2) System.out.println(); // vg

        // Now if we've read ALL the data, just clear the buffer - which is most efficient
        if (readAll) {
            data.clear();
//if (id == 1 && streamId == 2) System.out.println("CLEAR instead of compact, str " + streamId + " : BB " + id +
//                                     ", all data bytes = " + dataBytes + ", bytes consumed " +
//                                          (maxSamples*32 + startPos));
        }
        // Otherwise, shift data to beginning, then reset limit so we can add more data properly.
        else {
            // Now that we gobbled up data from this buffer, remove it from
            // what's stored locally by setting its position just past the
            // used data and then compacting the buffer.
            // We read # samples/chan * 16 chan * 2 entries/chan
//if (id == 1 && (streamId == 2)) System.out.println("Before COMPACT stream " + streamId + " : " + id + ": all data bytes = " + dataBytes + ", bytes consumed " +
//                                                (maxSamples*32 + startPos) + ", for next round = " + (dataBytes - maxSamples*32 - startPos));
            data.limit(dataBytes);
            data.position(maxSamples * 32 + startPos);
            data.compact();
//if (id == 1 && (streamId == 2)) System.out.println("COMPACTED stream " + streamId + " : " + id + ": data.pos = " + data.position() + ", lim = " + data.limit());
        }


//System.out.println("extractAdcValues END Id = " + streamId + ", Data bytes : " + (data.limit() - startPos));

    }

}
