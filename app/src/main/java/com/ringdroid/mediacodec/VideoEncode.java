package com.ringdroid.mediacodec;

import android.graphics.SurfaceTexture;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.view.Surface;

import com.ringdroid.egl.EGLUtils;
import com.ringdroid.egl.GLFramebuffer;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;

/**
 * Created by 海米 on 2018/7/4.
 */

public class VideoEncode {
    private static final String TAG = "VideoEncode";
    private static final String VIDEO = "video/";
    private MediaExtractor videoExtractor;
    private MediaCodec videoDecoder;
    private MediaCodec videoEncode;


    private static final String AUDIO = "audio/";
    private MediaExtractor audioExtractor;
    private MediaCodec audioDecoder;
    private MediaCodec audioEncode;

    private MediaMuxer mediaMuxer;
    private int videoTrackIndex = -1;
    private int audioTrackIndex = -1;

    private int audioTrack;
    private int videoTrack;
    private Handler videoHandler;
    private HandlerThread videoThread;


    private Handler eglHandler;
    private HandlerThread eglThread;


    private Handler audioDecoderHandler;
    private HandlerThread audioDecoderThread;
    private Handler audioEncodeHandler;
    private HandlerThread audioEncodeThread;


    private long startTime = 0;
    private long endTime = 0;

    private long duration;


    private EGLUtils mEglUtils;
    private GLFramebuffer mFramebuffer;

    public VideoEncode(){
        videoThread = new HandlerThread("VideoMediaCodec");
        videoThread.start();
        videoHandler = new Handler(videoThread.getLooper());

        eglThread = new HandlerThread("OpenGL");
        eglThread.start();
        eglHandler = new Handler(eglThread.getLooper());


        audioDecoderThread = new HandlerThread("AudioDecoderMediaCodec");
        audioDecoderThread.start();
        audioDecoderHandler = new Handler(audioDecoderThread.getLooper());

        audioEncodeThread = new HandlerThread("AudioEncodeMediaCodec");
        audioEncodeThread.start();
        audioEncodeHandler = new Handler(audioEncodeThread.getLooper());
    }


    public void init(String videoPath, final long startTime, long endTime,
                     final int cropWidth, final int cropHeight, final float[] textureVertexData){
        this.startTime = startTime;
        this.endTime = endTime;
        videoInit = false;
        audioInit = false;
        String path = Environment.getExternalStorageDirectory().getAbsolutePath() +"/VideoEdit.mp4";

        File f = new File(path);
        if(f.exists()){
            f.delete();
        }

        videoExtractor = new MediaExtractor();
        audioExtractor = new MediaExtractor();
        try {
            videoExtractor.setDataSource(videoPath);

            audioExtractor.setDataSource(videoPath);
            mediaMuxer = new MediaMuxer(path,MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            muxerStart = false;

            for (int i = 0; i < audioExtractor.getTrackCount(); i++) {
                MediaFormat format = audioExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith(AUDIO)) {
                    audioExtractor.selectTrack(i);
                    audioTrack = i;
                    if(startTime != 0){
                        audioExtractor.seekTo(startTime,audioTrack);
                    }
                    audioDecoder = MediaCodec.createDecoderByType(mime);
                    audioDecoder.configure(format, null, null, 0 /* Decoder */);

                    int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE) ?
                            format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;
                    int channelCount = format.containsKey(MediaFormat.KEY_CHANNEL_COUNT) ?
                            format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : 1;
                    int bitRate = format.containsKey(MediaFormat.KEY_BIT_RATE) ?
                            format.getInteger(MediaFormat.KEY_BIT_RATE) : 128000;

                    audioEncode = MediaCodec.createEncoderByType(mime);
                    MediaFormat encodeFormat = MediaFormat.createAudioFormat(mime, sampleRate, channelCount);//参数对应-> mime type、采样率、声道数
                    encodeFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitRate);//比特率
                    encodeFormat.setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC);
                    encodeFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 100 * 1024);
                    audioEncode.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                    audioExtractor.seekTo(startTime,audioTrack);
                    if(endTime == 0){
                        this.endTime = format.getLong(MediaFormat.KEY_DURATION);
                    }
                    audioDecoder.start();
                    audioEncode.start();
                    break;
                }
            }
            for (int i = 0; i < videoExtractor.getTrackCount(); i++) {
                final MediaFormat format = videoExtractor.getTrackFormat(i);
                String mime = format.getString(MediaFormat.KEY_MIME);
                if (mime.startsWith(VIDEO)) {
                    videoExtractor.selectTrack(i);
                    videoTrack = i;
                    if(startTime != 0){
                        videoExtractor.seekTo(startTime,videoTrack);
                    }
                    duration = format.getLong(MediaFormat.KEY_DURATION);


                    int BIT_RATE = cropWidth*cropHeight*2*8;
                    MediaFormat mediaFormat = MediaFormat.createVideoFormat(mime, cropWidth, cropHeight);
                    mediaFormat.setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE);
                    mediaFormat.setInteger(MediaFormat.KEY_FRAME_RATE, format.getInteger(MediaFormat.KEY_FRAME_RATE));
                    mediaFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
                    mediaFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

                    videoEncode = MediaCodec.createEncoderByType(mime);
                    videoEncode.configure(mediaFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);

                    videoDecoder = MediaCodec.createDecoderByType(mime);
                    eglHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Log.d(TAG, "eglHandler  run: "+Thread.currentThread().getName());

                            Surface surface = videoEncode.createInputSurface();
                            videoEncode.start();
                            isDraw = false;
                            mEglUtils = new EGLUtils();
                            mEglUtils.initEGL(surface);
                            mFramebuffer = new GLFramebuffer(textureVertexData);
                            mFramebuffer.onCreated();
                            mFramebuffer.onChanged(cropWidth,cropHeight);
                            SurfaceTexture surfaceTexture = mFramebuffer.getSurfaceTexture();
                            surfaceTexture.setDefaultBufferSize(cropWidth,cropHeight);
                            surfaceTexture.setOnFrameAvailableListener(new SurfaceTexture.OnFrameAvailableListener() {
                                @Override
                                public void onFrameAvailable(SurfaceTexture surfaceTexture) {
                                    mFramebuffer.onDrawFrame();
                                    mEglUtils.swap();
                                    synchronized (decoderObject){
                                        isDraw = true;
                                        decoderObject.notifyAll();
                                    }
                                }
                            });
                            videoDecoder.configure(format, new Surface(surfaceTexture), null, 0 /* Decoder */);
                            videoDecoder.start();
                            if(encoderListener != null){
                                encoderListener.onStart();
                            }
                            start();
                        }
                    }) ;
                    break;
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    private long presentationTimeUs;
    private final Object decoderObject = new Object();
    private final Object audioObject = new Object();
    private final Object videoObject = new Object();

    private boolean isDraw = false;

    private boolean muxerStart = false;
    private boolean videoInit = false;
    private boolean audioInit = false;


    private void start(){
        Log.d(TAG, "start  run: "+Thread.currentThread().getName());
        Log.w(TAG, "run: startTime "+startTime+" endTime "+endTime+" duration "+duration);

        muxerStart = false;
        videoInit = false;
        audioInit = false;
        videoHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "videoHandler run: "+Thread.currentThread().getName());

                Log.w(TAG, "run: -----------------------------  start  -----------------------------");
                Log.w(TAG, "run: startTime "+startTime+" endTime "+endTime+" duration "+duration);
                if (audioEncode == null
                        ) {
                    audioInit = true;
                }
                while (true) {
                    if(!audioInit){  // 音频没有混合就不开始解码
                        synchronized (videoObject){
                            try {
                                videoObject.wait(50);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        continue;
                    }
                    int run = extractorVideoInputBuffer(videoExtractor,videoDecoder);
                    if(run == 1){
                        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                        int outIndex = videoDecoder.dequeueOutputBuffer(info, 10000);
                        presentationTimeUs = info.presentationTimeUs;
                        Log.d(TAG, "run: videoDecoder  outIndex "+outIndex+" presentationTimeUs "
                                +info.presentationTimeUs+" size "+info.size+" flag "+info.flags+" offset "+info.offset);
                        if(outIndex >= 0){
                            videoDecoder.releaseOutputBuffer(outIndex, true /* Surface init */);
                            boolean s = false;
                            synchronized (decoderObject){ // 等待绘制完成，之后才能从编码器里面取数据
                                try {
                                    decoderObject.wait(50);
                                } catch (InterruptedException e) {
                                    e.printStackTrace();
                                }
                                if(isDraw){
                                    s = true;
                                }
                            }
                            if(s){
                                encodeVideoOutputBuffer(videoEncode,info,presentationTimeUs);
                            }else {
                                Log.i(TAG, "run: not draw");
                            }
                            if(presentationTimeUs > endTime){
                                videoEncode.signalEndOfInputStream();
                                break;
                            }
                        }

                    }else if(run == -1){
                        videoEncode.signalEndOfInputStream();
                        break;
                    }else{
                        try {
                            Thread.sleep(50);
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }


                }

                eglHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mEglUtils.release();
                    }
                });
                videoDecoder.stop();
                videoDecoder.release();
                videoDecoder = null;
                videoExtractor.release();
                videoExtractor = null;
                videoEncode.stop();
                videoEncode.release();
                videoEncode = null;
                muxerRelease();
                Log.w(TAG, "run: -----------------------------  end  -----------------------------");

            }
        });
        if (audioEncode == null) {
            return;
        }
        audioDecoderHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "audioDecoder  run: "+Thread.currentThread().getName());
                while (true) {
                    if(!muxerStart){
                        synchronized (audioObject){
                            try {
                                audioObject.wait(50);
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        continue;
                    }

                    extractorInputBuffer(audioExtractor,audioDecoder);
                    MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                    int outIndex = audioDecoder.dequeueOutputBuffer(info, 10000);
                    Log.d(TAG, "run: audioDecoder  outIndex "+outIndex+" presentationTimeUs "
                            +info.presentationTimeUs+" size "+info.size+" flag "+info.flags+" offset "+info.offset);

                    if(outIndex >= 0){
                        ByteBuffer data;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                            data = audioDecoder.getOutputBuffer(outIndex);
                        }else{
                            data = audioDecoder.getOutputBuffers()[outIndex];
                        }
                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            info.size = 0;
                            Log.d(TAG, "run: audioDecoder size==0");
                            audioDecoder.releaseOutputBuffer(outIndex, false);
                        }
                        if (info.size != 0) {
                            Log.d(TAG, "run: audioDecoder size!=0");
                            if(info.presentationTimeUs >= startTime){
                                data.position(info.offset);
                                data.limit(info.offset + info.size);
                                Log.d(TAG, "run: audioDecoder presentationTimeUs "
                                        +info.presentationTimeUs+" size "+info.size+" flag "+info.flags+" offset "+info.offset);


                                encodeInputBuffer(data,audioEncode,info);
                            }
                            audioDecoder.releaseOutputBuffer(outIndex, false);
                            if(info.presentationTimeUs > endTime){
                                Log.d(TAG, "run: audioDecoder  > endTime");

                                break;
                            }
                        }
                    }
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "run: audioDecoder  info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0");

                        break;
                    }
                }
                Log.d(TAG, "run: audioDecoder release");
                audioDecoder.stop();
                audioDecoder.release();
                audioExtractor.release();
                audioExtractor = null;
                audioDecoder = null;
            }
        });
        audioEncodeHandler.post(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "audioEncode  run: "+Thread.currentThread().getName());

                MediaCodec.BufferInfo bufferInfo = new MediaCodec.BufferInfo();
                while (true){
                    int inputIndex = audioEncode.dequeueOutputBuffer(bufferInfo, 1000);
                    Log.d(TAG, "run: audioEncode  outIndex "+inputIndex+" presentationTimeUs "
                            +bufferInfo.presentationTimeUs+" size "+bufferInfo.size+" flag "
                            +bufferInfo.flags+" offset "+bufferInfo.offset);

                    if(inputIndex >= 0){
                        ByteBuffer byteBuffer;
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                            byteBuffer = audioEncode.getOutputBuffer(inputIndex);
                        }else{
                            byteBuffer = audioEncode.getOutputBuffers()[inputIndex];
                        }
                        if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0;
                            Log.d(TAG, "run: audioEncode size==0");
                            audioEncode.releaseOutputBuffer(inputIndex, false);
                        }
                        if (bufferInfo.size != 0) {
                            Log.d(TAG, "run: audioEncode size!=0");
                            long presentationTimeUs = bufferInfo.presentationTimeUs;
                            if(presentationTimeUs >= startTime && presentationTimeUs <= endTime){
                                bufferInfo.presentationTimeUs = presentationTimeUs - startTime;
                                byteBuffer.position(bufferInfo.offset);
                                byteBuffer.limit(bufferInfo.offset + bufferInfo.size);
                                Log.d(TAG, "run: audioEncode presentationTimeUs "
                                        +bufferInfo.presentationTimeUs+" size "+bufferInfo.size
                                        +" flag "+bufferInfo.flags+" offset "+bufferInfo.offset);


                                mediaMuxer.writeSampleData(audioTrackIndex, byteBuffer, bufferInfo);
                            }
                            audioEncode.releaseOutputBuffer(inputIndex, false);
                            if(presentationTimeUs > endTime){
                                Log.d(TAG, "run: audioEncode  > endTime");

                                break;
                            }
                        }
                    }else if(inputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
                        if(videoTrackIndex == -1){
                            MediaFormat mediaFormat = audioEncode.getOutputFormat();
                            audioTrackIndex = mediaMuxer.addTrack(mediaFormat);
                            audioInit = true;
                            Log.d(TAG, "run: audioEncode   mediaMuxer.addTrack");

                        }
                    }
                    if ((bufferInfo.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        Log.d(TAG, "run: audioEncode  info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0");

                        break;
                    }
                }
                Log.d(TAG, "run: audioEncode release");
                audioEncode.stop();
                audioEncode.release();
                audioEncode = null;
                muxerRelease();
            }
        });
    }
    private synchronized void initMuxer(){
        muxerStart = true;
        mediaMuxer.start();
    }
    private synchronized void muxerRelease(){
        if(audioEncode == null && videoEncode == null && mediaMuxer != null){
            mediaMuxer.stop();
            mediaMuxer.release();
            mediaMuxer = null;
            if(encoderListener != null){
                encoderListener.onStop();
            }
        }

    }


    private int extractorVideoInputBuffer(MediaExtractor mediaExtractor,MediaCodec mediaCodec){
        int inputIndex = mediaCodec.dequeueInputBuffer(50000);
        if (inputIndex >= 0) {
            ByteBuffer inputBuffer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                inputBuffer = mediaCodec.getInputBuffer(inputIndex);
            }else{
                inputBuffer = audioEncode.getInputBuffers()[inputIndex];
            }
            long sampleTime = mediaExtractor.getSampleTime();
            int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
            Log.d(TAG, "extractorVideoInputBuffer: sampleTime "+sampleTime+" sampleSize "+sampleSize);
            if (mediaExtractor.advance()) {
                mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                Log.w(TAG, "extractorVideoInputBuffer: queueInputBuffer  1 " );
                return 1;
            } else {
                if(sampleSize > 0){
                    mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
                    Log.w(TAG, "extractorVideoInputBuffer: queueInputBuffer  1" );
                    return 1;
                }else{
                    Log.w(TAG, "extractorVideoInputBuffer: queueInputBuffer  -1" );
                    return -1;
                }

            }
        }
        return 0;
    }
    private void encodeVideoOutputBuffer(MediaCodec mediaCodec,MediaCodec.BufferInfo info,long presentationTimeUs){
        int encoderStatus = mediaCodec.dequeueOutputBuffer(info, 50000);
        Log.d(TAG, "encodeVideoOutputBuffer: encoderStatus"+encoderStatus);
        Log.d(TAG, "videoEncode  encodeVideoOutputBuffer: presentationTimeUs "+presentationTimeUs+
                " info presentationTimeUs "+info.presentationTimeUs+" size "+info.size+" flag "+info.flags+" offset "+info.offset);
        if (encoderStatus >= 0) {
            ByteBuffer encodedData;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                encodedData = mediaCodec.getOutputBuffer(encoderStatus);
            }else{
                encodedData = mediaCodec.getOutputBuffers()[encoderStatus];
            }
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {  // 如果是缓冲区的特定数据就过滤掉，不添加到混合区里面去
                info.size = 0;
                Log.i(TAG, "encodeVideoOutputBuffer: size ==0 return");
            }
            if (info.size != 0) {
                if(presentationTimeUs >= startTime && presentationTimeUs<= endTime){
                    encodedData.position(info.offset);
                    encodedData.limit(info.offset + info.size);
                    // 此处时间需要重新赋值，是因为编码器是没有时间的，需要自己确定，是根据解码器的时间确定的，而且是一一对应的，解码到surfaceView在编码
                    info.presentationTimeUs = presentationTimeUs - startTime;
                    Log.d(TAG, "videoEncode  encodeVideoOutputBuffer: presentationTimeUs "+presentationTimeUs+
                            " after presentationTimeUs "+info.presentationTimeUs+" size "+info.size+" flag "+info.flags+" offset "+info.offset);
                    mediaMuxer.writeSampleData(videoTrackIndex, encodedData, info);
                }else {
                    Log.i(TAG, "encodeVideoOutputBuffer: time not accord return");
                }
                if(encoderListener != null){
                    encoderListener.onProgress((int) ((presentationTimeUs-startTime)*100.0f/(endTime-startTime)));
                }
            }
            mediaCodec.releaseOutputBuffer(encoderStatus, false);
        }else if(encoderStatus == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){
            if(videoTrackIndex == -1){
                MediaFormat mediaFormat = mediaCodec.getOutputFormat();
                videoTrackIndex = mediaMuxer.addTrack(mediaFormat);
                videoInit = true;
                initMuxer();
            }
        }
    }

    private void extractorInputBuffer(MediaExtractor mediaExtractor,MediaCodec mediaCodec){
        int inputIndex = mediaCodec.dequeueInputBuffer(50000);
        Log.d(TAG, "audioDecoder extractorInputBuffer: inputIndex "+inputIndex);
        if (inputIndex >= 0) {
            ByteBuffer inputBuffer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                inputBuffer = mediaCodec.getInputBuffer(inputIndex);
            }else{
                inputBuffer = mediaCodec.getInputBuffers()[inputIndex];
            }
            long sampleTime = mediaExtractor.getSampleTime();
//            if(sampleTime >= endTime){
//                return;
//            }
            int sampleSize = mediaExtractor.readSampleData(inputBuffer, 0);
            Log.d(TAG, "audioDecoder extractorInputBuffer: sampleTime "+sampleTime+" sampleSize "+sampleSize);
            if (mediaExtractor.advance()) {
                Log.d(TAG, "audioDecoder extractorInputBuffer: advance ");
                mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, 0);
            } else {
                if(sampleSize > 0){
                    Log.d(TAG, "audioDecoder extractorInputBuffer: >0 ");
                    mediaCodec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTime, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }else{
                    Log.d(TAG, "audioDecoder extractorInputBuffer: <=0 ");
                    mediaCodec.queueInputBuffer(inputIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                }
            }
        }
    }



    private void encodeInputBuffer(ByteBuffer data,MediaCodec mediaCodec,MediaCodec.BufferInfo info){
        int inputIndex = mediaCodec.dequeueInputBuffer(50000);
        Log.d(TAG, "audioDecoder encodeInputBuffer:inputIndex "+inputIndex);
        if (inputIndex >= 0) {
            ByteBuffer inputBuffer;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP){
                inputBuffer = mediaCodec.getInputBuffer(inputIndex);
            }else{
                inputBuffer = mediaCodec.getInputBuffers()[inputIndex];
            }
            inputBuffer.clear();
            inputBuffer.put(data);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                Log.d(TAG, "audioDecoder encodeInputBuffer: !=0");
                mediaCodec.queueInputBuffer(inputIndex, 0, data.limit(), info.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
            }else{
                Log.d(TAG, "audioDecoder encodeInputBuffer: ==0");
                mediaCodec.queueInputBuffer(inputIndex, 0, data.limit(), info.presentationTimeUs, 0);
            }

        }
    }

    public long getContentPosition(){
        return presentationTimeUs/1000;
    }
    public long getDuration() {
        return duration/1000;
    }
    private OnEncoderListener encoderListener;

    public void setEncoderListener(OnEncoderListener encoderListener) {
        this.encoderListener = encoderListener;
    }

    public interface OnEncoderListener{
        void onStart();
        void onStop();
        void onProgress(int progress);
    }

}
