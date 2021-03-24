package me.alwx.HttpServer;

import fi.iki.elonen.NanoHTTPD;
import fi.iki.elonen.NanoHTTPD.Response;
import fi.iki.elonen.NanoHTTPD.Response.Status;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import java.util.Random;

import android.support.annotation.Nullable;
import android.util.Log;

public class Server extends NanoHTTPD {
    private static final String TAG = "HttpServer";
    private static final String SERVER_EVENT_ID = "httpServerResponseReceived";

    private ReactContext reactContext;
    private Map<String, Response> responses;

    public Server(ReactContext context, int port) {
        super(port);
        reactContext = context;
        responses = new HashMap<>();

        Log.d(TAG, "Server started");
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
            return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, e.getMessage()
            );
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

    private WritableMap fillRequestMap(IHTTPSession session, String requestId) throws Exception {
        Method method = session.getMethod();
        WritableMap request = Arguments.createMap();
        request.putString("url", session.getUri());
        request.putString("type", method.name());
        request.putString("requestId", requestId);

        WritableMap files = Arguments.createMap();
        Map<String, String> parsedFiles = new HashMap<>();
        // We should call parseBody before `getParams` for params to be populated
        session.parseBody(parsedFiles);
        for (String key : parsedFiles.keySet()) {
            files.putString(key, "file:" + parsedFiles.get(key));
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

    private void sendEvent(ReactContext reactContext, String eventName, @Nullable WritableMap params) {
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(eventName, params);
    }
}
