package org.jlab.epsci.stream.engine;

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
import org.jlab.epsci.ersap.base.ErsapUtil;
import org.jlab.epsci.ersap.base.error.ErsapException;
import org.jlab.epsci.ersap.engine.Engine;
import org.jlab.epsci.ersap.engine.EngineData;
import org.jlab.epsci.ersap.engine.EngineDataType;
import org.jlab.epsci.stream.sampa.SRingRawEvent;
import org.jlab.epsci.stream.sampa.util.Das2StreamStatistics;
import org.jlab.epsci.stream.sampa.util.DasDataType;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;

public class SampaStreamTestEngine implements Engine {
    private static final String PRINT_INTERVAL = "print-interval";
    private static final String SLOT = "slot";
    private Das2StreamStatistics dasStat = new Das2StreamStatistics();

    private boolean print;
    private int slotToPrint;


    @Override
    public EngineData configure(EngineData input) {
        System.out.println("SMPStreamTestEngine engine configure...");

        if (input.getMimeType().equalsIgnoreCase(EngineDataType.JSON.mimeType())) {
            String source = (String) input.getData();
            JSONObject data = new JSONObject(source);
            if (data.has(PRINT_INTERVAL)) {
                int pi = data.getInt(PRINT_INTERVAL);
                // Timer for measuring and printing statistics.
                Timer timer = new Timer();
                timer.schedule(new TimerTask() {
                    @Override
                    public void run() {
                        print = true;
                    }
                }, 0, pi * 1000);
                if (data.has(SLOT)) {
                    slotToPrint = data.getInt(SLOT);
                }
            }
        }
        return null;
    }

    @Override
    public EngineData execute(EngineData input) {
//        System.out.println("DDD JAVA service"+input.getMimeType());

        ByteBuffer bb = (ByteBuffer)input.getData();
        ByteBuffer[] data = null;
        try {
            data = DasDataType.deserialize(bb);
        } catch (ErsapException e) {
            e.printStackTrace();
        }
        dasStat.calculateStats(data);
        dasStat.printStats(System.out, false);

        return input;
    }

    @Override
    public EngineData executeGroup(Set<EngineData> inputs) {
        return inputs.iterator().next();
    }

    @Override
    public Set<EngineDataType> getInputDataTypes() {
        return ErsapUtil.buildDataTypes(EngineDataType.BYTES,
                EngineDataType.JSON);
//        return ErsapUtil.buildDataTypes(SampaDasType.SAMPA_DAS,
//                EngineDataType.JSON);
    }

    @Override
    public Set<EngineDataType> getOutputDataTypes() {
        return ErsapUtil.buildDataTypes(EngineDataType.BYTES);
//        return ErsapUtil.buildDataTypes(SampaDasType.SAMPA_DAS);
    }

    @Override
    public Set<String> getStates() {
        return null;
    }

    @Override
    public String getDescription() {
        return "Test Sampa Stream Engine";
    }

    @Override
    public String getVersion() {
        return "0.1";
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
