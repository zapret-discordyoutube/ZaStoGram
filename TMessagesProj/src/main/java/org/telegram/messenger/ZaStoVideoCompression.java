package org.telegram.messenger;

import java.io.File;
import java.util.Locale;

/**
 * ZaStoGram: sends small videos as they are instead of re-encoding them.
 *
 * Telegram downscales every video above 1280 px (and re-encodes any rotated
 * one) before upload, and the upload then waits for the encoder part by part:
 * a 13 MB 4K clip took most of a minute on a Pixel 9 while the network
 * answered each part in 0.3 s. Up to the chosen size the original mp4 goes
 * out untouched, as a regular streamable video, exactly like Desktop does.
 */
public final class ZaStoVideoCompression {

    public static final String KEY_LIMIT_MB = "zasto_video_no_recompress_mb";
    public static final int[] LIMIT_CHOICES_MB = {0, 20, 50, 100, 200};

    private ZaStoVideoCompression() {
    }

    public static int getLimitMb() {
        return MessagesController.getGlobalMainSettings().getInt(KEY_LIMIT_MB, 0);
    }

    public static void setLimitMb(int limitMb) {
        MessagesController.getGlobalMainSettings().edit().putInt(KEY_LIMIT_MB, Math.max(0, limitMb)).apply();
    }

    public static String describe(int limitMb) {
        return limitMb <= 0 ? "Выкл" : String.format(Locale.US, "до %d МБ", limitMb);
    }

    /**
     * Returns null when the video should be sent as the original file, or the
     * given info otherwise. Anything the user edited keeps going through the
     * converter, since only the converter can apply the edit.
     */
    public static VideoEditedInfo apply(String path, VideoEditedInfo info) {
        final int limitMb = getLimitMb();
        if (limitMb <= 0 || info == null || path == null) {
            return info;
        }
        // Without edit info only an mp4 is sent as a video; anything else would become a file.
        if (!path.toLowerCase(Locale.US).endsWith(".mp4")) {
            return info;
        }
        if (info.roundVideo || info.isSticker || info.isStory || info.muted
                || info.cropState != null || info.paintPath != null || info.blurPath != null
                || info.filterState != null || info.mediaEntities != null
                || !info.mixedSoundInfos.isEmpty() || info.avatarStartTime >= 0
                || info.videoOffset != 0 || info.startTime > 0
                || (info.endTime > 0 && info.endTime < info.estimatedDuration * 1000)) {
            return info;
        }
        final long size = new File(path).length();
        if (size <= 0 || size > limitMb * 1024L * 1024L) {
            return info;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("zasto_video_no_recompress size=" + size + " limit_mb=" + limitMb);
        }
        return null;
    }
}
