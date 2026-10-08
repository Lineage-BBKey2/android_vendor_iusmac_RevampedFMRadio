/*
 * Copyright (C) 2014,2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.fmradio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * This class provides the interface for recording and saving FM audio.
 */
public class FmRecorder implements MediaRecorder.OnErrorListener, MediaRecorder.OnInfoListener {
    private static final String TAG = "FmRecorder";

    public static final String RECORDING_FILE_PREFIX = "FM";
    public static final String RECORDING_FILE_EXTENSION = ".mp3";
    private static final String RECORDING_FILE_TYPE = "audio/mpeg";

    public static final int ERROR_SDCARD_NOT_PRESENT = 0;
    public static final int ERROR_SDCARD_INSUFFICIENT_SPACE = 1;
    public static final int ERROR_SDCARD_WRITE_FAILED = 2;
    public static final int ERROR_RECORDER_INTERNAL = 3;

    public static final int STATE_IDLE = 5;
    public static final int STATE_RECORDING = 6;
    public static final int STATE_PLAYBACK = 7;
    public static final int STATE_INVALID = -1;

    public int mInternalState = STATE_IDLE;
    private long mRecordTime = 0;
    private long mRecordStartTime = 0;
    private long mRecordFileSize = 0;
    private Uri mRecordUri = null;
    private String mRecordFileName = null;
    private ParcelFileDescriptor mRecordFileDescriptor = null;
    private ContentResolver mContentResolver = null;
    private boolean mIsRecordingFileSaved = false;
    private OnRecorderStateChangedListener mStateListener = null;
    private MediaRecorder mRecorder;

    /**
     * Start recording FM audio into a pending MediaStore item.
     */
    public void startRecording(Context context) {
        mRecordTime = 0;
        mRecordFileSize = 0;

        if (!Environment.MEDIA_MOUNTED.equals(Environment.getExternalStorageState())) {
            Log.e(TAG, "startRecording, no external storage available");
            setError(ERROR_SDCARD_NOT_PRESENT);
            return;
        }

        String recordingStorage = FmUtils.getDefaultStoragePath();
        if (!FmUtils.hasEnoughSpace(recordingStorage)) {
            Log.e(TAG, "startRecording, storage does not have sufficient space");
            setError(ERROR_SDCARD_INSUFFICIENT_SPACE);
            return;
        }

        clearRecordingReference(!mIsRecordingFileSaved);
        mIsRecordingFileSaved = false;

        long currentTime = System.currentTimeMillis();
        String time = new SimpleDateFormat("MMddyyyy_HHmmss", Locale.ENGLISH)
                .format(new Date(currentTime));
        mRecordFileName = time;
        mContentResolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME,
                mRecordFileName + RECORDING_FILE_EXTENSION);
        values.put(MediaStore.Audio.Media.TITLE, mRecordFileName);
        values.put(MediaStore.Audio.Media.MIME_TYPE, RECORDING_FILE_TYPE);
        values.put(MediaStore.Audio.Media.RELATIVE_PATH, getFmRecordFolder(context));
        values.put(MediaStore.Audio.Media.IS_PENDING, 1);

        try {
            mRecordUri = mContentResolver.insert(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values);
            if (mRecordUri == null) {
                Log.e(TAG, "startRecording, unable to create MediaStore item");
                setError(ERROR_SDCARD_WRITE_FAILED);
                clearRecordingReference(true);
                return;
            }

            mRecordFileDescriptor = mContentResolver.openFileDescriptor(mRecordUri, "w");
            if (mRecordFileDescriptor == null) {
                Log.e(TAG, "startRecording, unable to open MediaStore item");
                setError(ERROR_SDCARD_WRITE_FAILED);
                clearRecordingReference(true);
                return;
            }

            if (mRecorder != null && STATE_RECORDING == mInternalState) {
                stopRecording();
            }

            final long maxFileSize = FmUtils.getAvailableSpace(recordingStorage)
                    - FmUtils.LOW_SPACE_THRESHOLD;

            mRecorder = new MediaRecorder();
            mRecorder.setMaxFileSize(maxFileSize);
            mRecorder.setMaxDuration(0);
            mRecorder.setAudioSource(MediaRecorder.AudioSource.RADIO_TUNER);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mRecorder.setAudioSamplingRate(44100);
            mRecorder.setAudioEncodingBitRate(128000);
            mRecorder.setAudioChannels(2);
            mRecorder.setOutputFile(mRecordFileDescriptor.getFileDescriptor());
            mRecorder.prepare();
            mRecorder.setOnErrorListener(this);
            mRecorder.setOnInfoListener(this);
            Log.d(TAG, "startRecording, recorder.start()");
            mRecorder.start();
            mRecordStartTime = SystemClock.elapsedRealtime();
            mIsRecordingFileSaved = false;
            setState(STATE_RECORDING);
        } catch (RuntimeException | IOException e) {
            Log.e(TAG, "startRecording, error while starting recording", e);
            releaseRecorder();
            clearRecordingReference(true);
            setError(ERROR_RECORDER_INTERNAL);
        }
    }

    public void stopRecording() {
        if (STATE_RECORDING != mInternalState) {
            Log.w(TAG, "stopRecording, called in wrong state");
            return;
        }

        mRecordTime = SystemClock.elapsedRealtime() - mRecordStartTime;
        stopRecorder();
        setState(STATE_IDLE);
    }

    public long getRecordTime() {
        if (STATE_RECORDING == mInternalState) {
            mRecordTime = SystemClock.elapsedRealtime() - mRecordStartTime;
        }
        return mRecordTime;
    }

    public int getState() {
        return mInternalState;
    }

    public String getRecordFileName() {
        return mRecordFileName;
    }

    /**
     * Publish the current pending MediaStore item under the user-selected name.
     */
    public Uri saveRecording(Context context, String newName) {
        if (mRecordUri == null) {
            Log.e(TAG, "saveRecording, recording URI is null");
            return null;
        }

        ContentResolver resolver = context.getContentResolver();
        Resources resources = context.getResources();
        ContentValues values = new ContentValues();
        values.put(MediaStore.Audio.Media.DISPLAY_NAME,
                newName + RECORDING_FILE_EXTENSION);
        values.put(MediaStore.Audio.Media.TITLE, newName);
        values.put(MediaStore.Audio.Media.DURATION, mRecordTime);
        values.put(MediaStore.Audio.Media.MIME_TYPE, RECORDING_FILE_TYPE);
        values.put(MediaStore.Audio.Media.ARTIST,
                resources.getString(R.string.audio_db_artist_name));
        values.put(MediaStore.Audio.Media.ALBUM,
                resources.getString(R.string.audio_db_album_name));
        values.put(MediaStore.Audio.Media.IS_MUSIC, 0);
        values.put(MediaStore.Audio.Media.IS_PENDING, 0);

        try {
            if (resolver.update(mRecordUri, values, null, null) != 1) {
                Log.e(TAG, "saveRecording, unable to publish MediaStore item");
                return null;
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "saveRecording, unable to update MediaStore item", e);
            return null;
        }

        mRecordFileName = newName;
        mIsRecordingFileSaved = true;
        return mRecordUri;
    }

    public void discardRecording() {
        if (STATE_RECORDING == mInternalState) {
            stopRecorder();
        }

        clearRecordingReference(!mIsRecordingFileSaved);
        mRecordStartTime = 0;
        mRecordTime = 0;
        mRecordFileSize = 0;
        setState(STATE_IDLE);
    }

    public void registerRecorderStateListener(OnRecorderStateChangedListener listener) {
        mStateListener = listener;
    }

    public interface OnRecorderStateChangedListener {
        void onRecorderStateChanged(int state);
        void onRecorderError(int error);
    }

    @Override
    public void onError(MediaRecorder recorder, int what, int extra) {
        Log.e(TAG, "onError, what = " + what + ", extra = " + extra);
        stopRecorder();
        int error = ERROR_RECORDER_INTERNAL;
        if (what == MediaRecorder.MEDIA_RECORDER_ERROR_UNKNOWN
                && !FmUtils.hasEnoughSpace(FmUtils.getDefaultStoragePath())) {
            error = ERROR_SDCARD_INSUFFICIENT_SPACE;
        }
        setError(error);
        if (STATE_RECORDING == mInternalState) {
            setState(STATE_IDLE);
        }
    }

    @Override
    public void onInfo(MediaRecorder recorder, int what, int extra) {
        Log.e(TAG, "onInfo, what = " + what + ", extra = " + extra);
        final int error;
        switch (what) {
            case MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED:
                error = ERROR_RECORDER_INTERNAL;
                break;
            case MediaRecorder.MEDIA_RECORDER_INFO_MAX_FILESIZE_REACHED:
                error = ERROR_SDCARD_INSUFFICIENT_SPACE;
                break;
            default:
                return;
        }
        stopRecorder();
        setError(error);
        if (STATE_RECORDING == mInternalState) {
            setState(STATE_IDLE);
        }
    }

    public void resetRecorder() {
        stopRecorder();
        clearRecordingReference(!mIsRecordingFileSaved);
        mRecordStartTime = 0;
        mRecordTime = 0;
        mRecordFileSize = 0;
        mIsRecordingFileSaved = false;
        mInternalState = STATE_IDLE;
    }

    private void setError(int error) {
        if (mStateListener != null) {
            mStateListener.onRecorderError(error);
        }
    }

    private void setState(int state) {
        mInternalState = state;
        if (mStateListener != null) {
            mStateListener.onRecorderStateChanged(state);
        }
    }

    private void stopRecorder() {
        if (mRecorder != null) {
            try {
                mRecorder.stop();
            } catch (RuntimeException e) {
                Log.e(TAG, "stopRecorder, unable to stop recorder", e);
            } finally {
                releaseRecorder();
            }
        }
        updateFileSize();
        closeRecordFileDescriptor();
    }

    private void releaseRecorder() {
        if (mRecorder == null) {
            return;
        }
        try {
            mRecorder.reset();
        } catch (RuntimeException e) {
            Log.w(TAG, "releaseRecorder, unable to reset recorder", e);
        }
        mRecorder.release();
        mRecorder = null;
    }

    private void updateFileSize() {
        if (mRecordFileDescriptor == null) {
            return;
        }
        long size = mRecordFileDescriptor.getStatSize();
        if (size >= 0) {
            mRecordFileSize = size;
        }
    }

    private void closeRecordFileDescriptor() {
        if (mRecordFileDescriptor == null) {
            return;
        }
        try {
            mRecordFileDescriptor.close();
        } catch (IOException e) {
            Log.w(TAG, "Unable to close recording file descriptor", e);
        }
        mRecordFileDescriptor = null;
    }

    private void clearRecordingReference(boolean delete) {
        closeRecordFileDescriptor();
        if (delete && mRecordUri != null && mContentResolver != null) {
            try {
                mContentResolver.delete(mRecordUri, null, null);
            } catch (RuntimeException e) {
                Log.w(TAG, "Unable to delete pending recording", e);
            }
        }
        mRecordUri = null;
        mRecordFileName = null;
        mContentResolver = null;
    }

    public static boolean recordingExists(Context context, String name) {
        Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        String displayName = name + RECORDING_FILE_EXTENSION;
        String relativePath = getFmRecordFolder(context);
        String relativePathWithSeparator = relativePath + File.separator;
        String selection = MediaStore.Audio.Media.DISPLAY_NAME + "=? AND ("
                + MediaStore.Audio.Media.RELATIVE_PATH + "=? OR "
                + MediaStore.Audio.Media.RELATIVE_PATH + "=?)";
        String[] selectionArgs = {
                displayName,
                relativePath,
                relativePathWithSeparator
        };

        try (Cursor cursor = context.getContentResolver().query(
                collection,
                new String[] { MediaStore.Audio.Media._ID },
                selection,
                selectionArgs,
                null)) {
            return cursor != null && cursor.moveToFirst();
        } catch (RuntimeException e) {
            Log.w(TAG, "Unable to check for an existing recording", e);
            return false;
        }
    }

    public static String getFmRecordFolder(Context context) {
        return Environment.DIRECTORY_RECORDINGS + File.separator
                + context.getResources().getString(R.string.audio_save_dir_name);
    }

    public long getFileSize() {
        updateFileSize();
        return mRecordFileSize;
    }
}
