package com.google.zxing.client.android;

import com.google.zxing.DecodeHintType;

import java.util.Map;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Created by yue.huang
 * on 2020-05-06
 */
public class DecodeThreadPoolExecutor extends ThreadPoolExecutor {
    private CaptureActivity activity;
    private Map<DecodeHintType,?> hints;
    private String characterSet;

    public static boolean DECODE_SUCCEED = false;

    public DecodeThreadPoolExecutor() {
        super(Runtime.getRuntime().availableProcessors(), Runtime.getRuntime().availableProcessors()+1, 5, TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());
    }


    public void setDecodeParameters(CaptureActivity activity, Map<DecodeHintType,?> baseHints, String characterSet){
        this.activity = activity;
        this.hints = baseHints;
        this.characterSet = characterSet;
    }


    public void executeDecode(byte[] data, int width, int height){
        execute(new DecodeThread(activity, hints, characterSet, new ViewfinderResultPointCallback(activity.getViewfinderView()),data,width,height));
    }
}
