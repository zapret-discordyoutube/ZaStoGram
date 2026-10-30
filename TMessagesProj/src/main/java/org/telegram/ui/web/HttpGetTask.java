package org.telegram.ui.web;


import android.os.AsyncTask;

import org.telegram.messenger.Utilities;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;

public class HttpGetTask extends AsyncTask<String, Void, String> {

    private final HashMap<String, String> headers = new HashMap<>();
    private final Utilities.Callback<String> callback;
    private Exception exception;
    private int connectTimeout;
    private int readTimeout;

    public HttpGetTask(Utilities.Callback<String> callback) {
        this.callback = callback;
    }

    public HttpGetTask setHeader(String key, String value) {
        headers.put(key, value);
        return this;
    }

    /** Zero keeps the platform default. */
    public HttpGetTask setTimeouts(int connectMs, int readMs) {
        this.connectTimeout = connectMs;
        this.readTimeout = readMs;
        return this;
    }

    @Override
    protected String doInBackground(String... params) {
        String urlString = params[0];

        try {
            URL url = new URL(urlString);
            HttpURLConnection urlConnection = (HttpURLConnection) url.openConnection();
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) continue;
                urlConnection.setRequestProperty(e.getKey(), e.getValue());
            }
            urlConnection.setRequestMethod("GET");
            urlConnection.setDoInput(true);
            if (connectTimeout > 0) {
                urlConnection.setConnectTimeout(connectTimeout);
            }
            if (readTimeout > 0) {
                urlConnection.setReadTimeout(readTimeout);
            }

            int statusCode = urlConnection.getResponseCode();
            BufferedReader in;
            if (statusCode >= 200 && statusCode < 300) {
                in = new BufferedReader(new InputStreamReader(urlConnection.getInputStream()));
            } else {
                in = new BufferedReader(new InputStreamReader(urlConnection.getErrorStream()));
            }

            StringBuilder response = new StringBuilder();
            String line;
            while ((line = in.readLine()) != null) {
                response.append(line);
            }
            in.close();

            return response.toString();

        } catch (Exception e) {
            this.exception = e;
            return null;
        }
    }

    @Override
    protected void onPostExecute(String result) {
        if (callback != null) {
            if (exception == null) {
                callback.run(result);
            } else {
                callback.run(null);
            }
        }
    }
}