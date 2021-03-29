package me.alwx.HttpServer;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.Set;

import android.support.annotation.Nullable;
import android.util.Log;

public class Server extends NanoHTTPD {
    private static final String TAG = "HttpServer";
    private static final String SERVER_EVENT_ID = "httpServerResponseReceived";

    private ReactContext reactContext;
    private Map<String, Response> responses;

    // Let's preserve recieved files until the app shut down.
    private DefaultTempFileManager cachedFiles = new DefaultTempFileManager();

    public Server(ReactContext context, int port) {
        super(port);
        reactContext = context;
        responses = new HashMap<>();

        Log.d(TAG, "Server started");
    }

    public void stop() {
        cachedFiles.clear();
        super.stop();
    }

    @Override
    public Response serve(IHTTPSession session) {
        Log.d(TAG, "Request received!");

        Random rand = new Random();
        String requestId = String.format("%d:%d", System.currentTimeMillis(), rand.nextInt(1000000));

        WritableMap request;
        try {
            request = fillRequestMap(session, requestId);
        } catch (Exception e) {
            return newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage());
        }

        this.sendEvent(reactContext, SERVER_EVENT_ID, request);

        while (responses.get(requestId) == null) {
            try {
                Thread.sleep(10);
            } catch (Exception e) {
                Log.d(TAG, "Exception while waiting: " + e);
            }
        }
        Response response = responses.get(requestId);
        responses.remove(requestId);
        return response;
    }

    public void respond(String requestId, int code, String type, String body) {
        responses.put(requestId, newFixedLengthResponse(Status.lookup(code), type, body));
    }

    public void respondWithFile(String requestId, int code, String type, String file) {
        responses.put(requestId, newFileResponse(Status.lookup(code), type, file));
    }

    private Response newFileResponse(Status status, String type, String file) {
        Response res;
        try {
            File fileObj = new File(file);
            long fileLen = fileObj.length();
            res = newFixedLengthResponse(status, type, new FileInputStream(fileObj), fileLen);
        } catch (IOException e) {
            Log.d(TAG, "Exception while reading file: " + e);
            res = newFixedLengthResponse(Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Reading file failed");
        }
        return res;
    }

    private WritableMap fillRequestMap(IHTTPSession session, String requestId) throws Exception {
        Method method = session.getMethod();
        WritableMap request = Arguments.createMap();
        String queryParamsStr = session.getQueryParameterString();
        request.putString("url", session.getUri() + (Objects.isNull(queryParamsStr) ? "" : ("?" + queryParamsStr)));
        request.putString("type", method.name());
        request.putString("requestId", requestId);

        WritableMap files = Arguments.createMap();
        Map<String, String> parsedFiles = new HashMap<>();
        // We should call parseBody before `getParams` for params to be populated
        session.parseBody(parsedFiles);
        for (String key : parsedFiles.keySet()) {
            String tmpFile = parsedFiles.get(key);
            String cachedFile = cachedFiles.createTempFile("").getName();
            copy(new File(tmpFile), new File(cachedFile));
            files.putString(key, cachedFile);
        }
        request.putMap("files", files);

        WritableMap arguments = Arguments.createMap();
        Map<String, String> parameters = session.getParms();
        for (String key : parameters.keySet()) {
            arguments.putString(key, parameters.get(key));
        }
        request.putMap("arguments", arguments);

        return request;
    }

    static private void copy(File src, File dst) throws IOException {
        InputStream in = new FileInputStream(src);
        OutputStream out = new FileOutputStream(dst);

        byte[] buf = new byte[1024];
        int len;
        while ((len = in.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        in.close();
        out.close();
    }

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }
}
