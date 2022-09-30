package org.jlab.epsci.stream.engine;

import org.jlab.epsci.ersap.base.ErsapUtil;
import org.jlab.epsci.ersap.base.error.ErsapException;
import org.jlab.epsci.ersap.engine.Engine;
import org.jlab.epsci.ersap.engine.EngineData;
import org.jlab.epsci.ersap.engine.EngineDataType;
import org.jlab.epsci.stream.sampa.histo.DasHistogram;
import org.jlab.epsci.stream.sampa.util.DasDataType;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Set;
import java.util.StringTokenizer;

/**
 * Copyright (c) 2021, Jefferson Science Associates, all rights reserved.
 * See LICENSE.txt file.
 * Thomas Jefferson National Accelerator Facility
 * Experimental Physics Software and Computing Infrastructure Group
 * 12000, Jefferson Ave, Newport News, VA 23606
 * Phone : (757)-269-7100
 *
 * @author gurjyan on 9/29/22
 * @project ersap-sampa
 */
public class SampaHistogramEngine implements Engine {
    private static String FRAME_TITLE = "frame_title";
    private String frameTitle;
    private static String FRAME_WIDTH = "frame_width";
    private int frameWidth;
    private static String FRAME_HEIGHT = "frame_height";
    private int frameHeight;
    private static String HIST_TITLES = "hist_titles";
    private ArrayList<Integer> histTitles;
    private static String HIST_BINS = "hist_bins";
    private int histBins;
    private static String HIST_MIN = "hist_min";
    private double histMin;
    private static String HIST_MAX = "hist_max";
    private double histMax;
    private static String GRID_SIZE = "grid_size";
    private int gridSize;

    private DasHistogram histogram;

    @Override
    public EngineData configure(EngineData input) {
        if (input.getMimeType().equalsIgnoreCase(EngineDataType.JSON.mimeType())) {
        String source = (String) input.getData();
        JSONObject opts = new JSONObject(source);        if (opts.has(FRAME_TITLE)) {
                frameTitle = opts.getString(FRAME_TITLE);
            }
            if (opts.has(FRAME_WIDTH)) {
                frameWidth = opts.getInt(FRAME_WIDTH);
            }
            if (opts.has(FRAME_HEIGHT)) {
                frameHeight = opts.getInt(FRAME_HEIGHT);
            }
            if (opts.has(GRID_SIZE)) {
                gridSize = opts.getInt(GRID_SIZE);
            }
            if (opts.has(HIST_TITLES)) {
                histTitles = new ArrayList<>();
                String ht = opts.getString(HIST_TITLES);
                StringTokenizer st = new StringTokenizer(ht, ",");
                while (st.hasMoreTokens()) {
                    histTitles.add(Integer.parseInt(st.nextToken().trim()));
                }
            }
            if (opts.has(HIST_BINS)) {
                histBins = opts.getInt(HIST_BINS);
            }
            if (opts.has(HIST_MIN)) {
                histMin = opts.getDouble(HIST_MIN);
            }
            if (opts.has(HIST_MAX)) {
                histMax = opts.getDouble(HIST_MAX);
            }
            if (opts.has(GRID_SIZE)) {
                gridSize = opts.getInt(GRID_SIZE);
            }
            histogram = new DasHistogram(frameTitle, histTitles,
                    gridSize, frameWidth, frameHeight,
                    histBins, histMin, histMax);
        }
        return null;
    }

    @Override
    public EngineData execute(EngineData input) {
        ByteBuffer bb = (ByteBuffer)input.getData();
        ByteBuffer[] data;
        try {
            data = DasDataType.deserialize(bb);
            int sampleLimit = data[0].limit()/2;
            for (int channel = 0; channel < 160; channel++) {
                if(histTitles.contains(channel)) {
                    short[] _sData = new short[sampleLimit];
                    for (int sample = 0; sample < sampleLimit; sample++) {
                       _sData[sample] =  data[channel].getShort(2 * sample);
                    }
                    histogram.update(String.valueOf(channel), _sData);                 ;
                }
            }

        } catch (ErsapException e) {
            e.printStackTrace();
        }
        return input;
    }

    @Override
    public EngineData executeGroup(Set<EngineData> inputs) {
        return null;
    }

    @Override
    public Set<EngineDataType> getInputDataTypes() {
        return ErsapUtil.buildDataTypes(EngineDataType.BYTES,
                EngineDataType.JSON);
    }

    @Override
    public Set<EngineDataType> getOutputDataTypes() {
        return ErsapUtil.buildDataTypes(EngineDataType.BYTES);
    }

    @Override
    public Set<String> getStates() {
        return null;
    }

    @Override
    public String getDescription() {
        return "SAMPA channel histogram engine";
    }

    @Override
    public String getVersion() {
        return "v-1.0";
    }

    @Override
    public String getAuthor() {
        return "Vardan Gyurjyan";
    }

    @Override
    public void reset() {

    }

    @Override
    public void destroy() {

    }
}
