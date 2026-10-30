package org.telegram.ui.web;


import android.content.ContentResolver;
import android.os.AsyncTask;
import android.os.Build;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;

import androidx.annotation.Keep;

import androidx.media3.common.MimeTypes;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.ProtocolException;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Keep
public class HttpGetFileTask extends AsyncTask<String, Void, File> {

    private static final Pattern CONTENT_RANGE_PATTERN = Pattern.compile("^bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)$");
    private static final Pattern UNSATISFIED_CONTENT_RANGE_PATTERN = Pattern.compile("^bytes\\s+\\*/(\\d+)$");

    private File file;

    private Utilities.Callback<File> doneCallback;
    private Utilities.Callback<Float> progressCallback;

    private String overrideExt;
    private boolean resumeExistingFile;
    private boolean keepPartialFileOnCancel;

    private Exception exception;
    private long max_size = -1;
    private int connectTimeout;
    private int readTimeout;

    public HttpGetFileTask(
        Utilities.Callback<File> doneCallback,
        Utilities.Callback<Float> progressCallback
    ) {
        this.doneCallback = doneCallback;
        this.progressCallback = progressCallback;
    }

    @Keep
    public HttpGetFileTask setOverrideExtension(String ext) {
        this.overrideExt = ext;
        return this;
    }

    @Keep
    public HttpGetFileTask setDestFile(File file) {
        this.file = file;
        return this;
    }

    /**
     * Appends to an existing destination with a validated HTTP Range request.
     * If the server ignores Range, the destination is safely overwritten.
     */
    @Keep
    public HttpGetFileTask setResumeExistingFile(boolean resumeExistingFile) {
        this.resumeExistingFile = resumeExistingFile;
        return this;
    }

    /** Keeps a resumable destination when the caller pauses the task. */
    @Keep
    public HttpGetFileTask setKeepPartialFileOnCancel(boolean keepPartialFileOnCancel) {
        this.keepPartialFileOnCancel = keepPartialFileOnCancel;
        return this;
    }

    /** Zero keeps the platform default. */
    @Keep
    public HttpGetFileTask setTimeouts(int connectMs, int readMs) {
        this.connectTimeout = connectMs;
        this.readTimeout = readMs;
        return this;
    }

    @Keep
    public HttpGetFileTask setMaxSize(long max_size) {
        this.max_size = max_size;
        return this;
    }

    @Override
    protected File doInBackground(String... params) {
        String urlString = params[0];

        long totalSize = 0L;
        long downloadedSize = resumeExistingFile && file != null && file.isFile() ? file.length() : 0L;
        boolean canResume = downloadedSize > 0L;
        Exception lastException = null;
        if (max_size > 0 && downloadedSize > max_size) {
            deleteDestination();
            exception = new IOException("existing destination exceeds the maximum download size");
            return null;
        }
        for (int i = 0; i < 5; ++i) {
            boolean resuming = canResume && downloadedSize > 0L;
            HttpURLConnection urlConnection = null;
            try {
                URL url = new URL(urlString);
                urlConnection = (HttpURLConnection) url.openConnection();
                urlConnection.setRequestMethod("GET");
                if (connectTimeout > 0) {
                    urlConnection.setConnectTimeout(connectTimeout);
                }
                if (readTimeout > 0) {
                    urlConnection.setReadTimeout(readTimeout);
                }
                if (resuming) {
                    urlConnection.setRequestProperty("Range", "bytes=" + downloadedSize + "-");
                }
                urlConnection.setDoInput(true);

                final int status = urlConnection.getResponseCode();
                if (resuming && status == 416) {
                    long remoteSize = parseUnsatisfiedContentRange(urlConnection.getHeaderField("Content-Range"));
                    if (remoteSize == downloadedSize) {
                        return file;
                    }
                    FileLog.d("resume offset is outside the remote file, downloading from the beginning");
                    downloadedSize = 0L;
                    resuming = false;
                    continue;
                }
                if (status < 200 || status >= 300) {
                    throw new IOException("HTTP GET failed with status " + status);
                }
                if (resuming && status == HttpURLConnection.HTTP_PARTIAL) {
                    long rangeStart = parseContentRangeStart(urlConnection.getHeaderField("Content-Range"));
                    if (rangeStart != downloadedSize) {
                        throw new ProtocolException("server returned an invalid Content-Range for offset " + downloadedSize);
                    }
                } else if (resuming) {
                    FileLog.d("failed to resume, server doesn't support partial content. downloading from the beginning");
                    downloadedSize = 0L;
                    resuming = false;
                }
                InputStream in = urlConnection.getInputStream();
                long responseSize;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    responseSize = urlConnection.getContentLengthLong();
                } else {
                    responseSize = urlConnection.getContentLength();
                }
                long contentRangeSize = parseContentRangeSize(urlConnection.getHeaderField("Content-Range"));
                if (contentRangeSize > 0L) {
                    totalSize = contentRangeSize;
                } else if (responseSize >= 0L) {
                    totalSize = resuming ? downloadedSize + responseSize : responseSize;
                } else {
                    totalSize = 0L;
                }
                if (max_size > 0 && totalSize > max_size) {
                    in.close();
                    deleteDestination();
                    exception = new IOException("download exceeds the maximum size");
                    return null;
                }

                if (file == null) {
                    final String ext = overrideExt != null ? overrideExt : MimeTypeMap.getSingleton().getExtensionFromMimeType(urlConnection.getContentType());
                    file = StoryEntry.makeCacheFile(UserConfig.selectedAccount, ext);
                }

                try (BufferedInputStream bis = new BufferedInputStream(in, 16_384)) {
                    try (FileOutputStream fos = new FileOutputStream(file, resuming)) {
                        canResume = true;

                        byte[] buffer = new byte[16_384];
                        int bytesRead;

                        while ((bytesRead = bis.read(buffer)) != -1) {
                            if (max_size > 0 && downloadedSize + bytesRead > max_size) {
                                throw new DownloadTooLargeException();
                            }
                            fos.write(buffer, 0, bytesRead);
                            downloadedSize += bytesRead;

                            if (isCancelled()) {
                                if (!keepPartialFileOnCancel) {
                                    deleteDestination();
                                }
                                return null;
                            }

                            if (totalSize > 0) {
                                float progress = Utilities.clamp01((float) downloadedSize / totalSize);
                                if (progressCallback != null) {
                                    AndroidUtilities.runOnUIThread(() -> progressCallback.run(progress));
                                }
                            }
                        }

                        if (totalSize > 0L && downloadedSize != totalSize) {
                            throw new ProtocolException("unexpected end of download at "
                                    + downloadedSize + " of " + totalSize + " bytes");
                        }

                        if (progressCallback != null) {
                            AndroidUtilities.runOnUIThread(() -> progressCallback.run(1.0f));
                        }
                    }
                }

                if (isCancelled()) {
                    if (!keepPartialFileOnCancel) {
                        deleteDestination();
                    }
                    return null;
                }
                return file;
            } catch (DownloadTooLargeException e) {
                deleteDestination();
                exception = new IOException("download exceeds the maximum size", e);
                return null;
            } catch (IOException e) {
                lastException = e;
                if (isCancelled()) {
                    if (!keepPartialFileOnCancel) {
                        deleteDestination();
                    }
                    return null;
                }
                downloadedSize = canResume && file != null && file.isFile() ? file.length() : 0L;
                FileLog.d("download interrupted, retrying from byte " + downloadedSize);
                FileLog.e(e);
            } catch (Exception e) {
                this.exception = e;
                FileLog.e(e);
                return null;
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }
        }
        this.exception = new RuntimeException("too many retries", lastException);
        return null;
    }

    private void deleteDestination() {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static long parseContentRangeStart(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange.trim());
        if (!matcher.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignore) {
            return -1L;
        }
    }

    private static long parseContentRangeSize(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        Matcher matcher = CONTENT_RANGE_PATTERN.matcher(contentRange.trim());
        if (!matcher.matches() || "*".equals(matcher.group(3))) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(3));
        } catch (NumberFormatException ignore) {
            return -1L;
        }
    }

    private static long parseUnsatisfiedContentRange(String contentRange) {
        if (contentRange == null) {
            return -1L;
        }
        Matcher matcher = UNSATISFIED_CONTENT_RANGE_PATTERN.matcher(contentRange.trim());
        if (!matcher.matches()) {
            return -1L;
        }
        try {
            return Long.parseLong(matcher.group(1));
        } catch (NumberFormatException ignore) {
            return -1L;
        }
    }

    private static final class DownloadTooLargeException extends IOException {
    }

    @Override
    protected void onPostExecute(File file) {
        if (doneCallback != null) {
            if (exception == null) {
                doneCallback.run(file);
            } else {
                doneCallback.run(null);
            }
        }
    }
}
