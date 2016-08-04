package com.stitchdata.client;

import com.stitchdata.client.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.EOFException;
import java.io.Flushable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.cognitect.transit.Writer;
import com.cognitect.transit.TransitFactory;
import com.cognitect.transit.Reader;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.entity.ContentType;
import org.apache.http.StatusLine;
import org.apache.http.HttpResponse;
import org.apache.http.HttpEntity;

import javax.json.Json;
import javax.json.JsonReader;

/**
 * Client for Stitch. Callers should use {@link StitchClientBuilder}
 * to construct instances of StitchClient. This class provides one
 * function, {@link #push(StitchMessage)}, for pushing records to
 * Stitch.
 */
public class StitchClient implements Flushable, Closeable {

    public static final String PUSH_URL
        =  "https://pipeline-gateway.rjmetrics.com/push";

    private static final int HTTP_CONNECT_TIMEOUT = 1000 * 60 * 2;
    private static final ContentType CONTENT_TYPE =
        ContentType.create("application/transit+json");

    public static enum Action { UPSERT, SWITCH_VIEW };

    public static class Field {
        public static final String CLIENT_ID = "client_id";
        public static final String NAMESPACE = "namespace";
        public static final String ACTION = "action";
        public static final String TABLE_NAME = "table_name";
        public static final String TABLE_VERSION = "table_version";
        public static final String KEY_NAMES = "key_names";
        public static final String SEQUENCE = "sequence";
        public static final String DATA = "data";
    }

    private final int connectTimeout = HTTP_CONNECT_TIMEOUT;
    private final String stitchUrl;
    private final int clientId;
    private final String token;
    private final String namespace;
    private final String tableName;
    private final List<String> keyNames;
    private final int flushIntervalMillis;
    private final int bufferSize;
    private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
    private final Writer writer = TransitFactory.writer(TransitFactory.Format.JSON, buffer);

    private long lastFlushTime = System.currentTimeMillis();

    private static void putWithDefault(Map map, String key, Object value, Object defaultValue) {
        map.put(key, value != null ? value : defaultValue);
    }

    private static void putIfNotNull(Map map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private Map messageToMap(StitchMessage message) {
        HashMap map = new HashMap();

        map.put(Field.CLIENT_ID, clientId);
        map.put(Field.NAMESPACE, namespace);

        putWithDefault(map, Field.TABLE_NAME, message.getTableName(), tableName);
        putWithDefault(map, Field.KEY_NAMES, message.getKeyNames(), keyNames);

        putIfNotNull(map, Field.ACTION, message.getAction());
        putIfNotNull(map, Field.TABLE_VERSION, message.getTableVersion());
        putIfNotNull(map, Field.SEQUENCE, message.getSequence());
        putIfNotNull(map, Field.DATA, message.getData());

        return map;
    }

    StitchClient(
        String stitchUrl,
        int clientId,
        String token,
        String namespace,
        String tableName,
        List<String> keyNames,
        int flushIntervalMillis,
        int bufferSize)
    {
        this.stitchUrl = stitchUrl;
        this.clientId = clientId;
        this.token = token;
        this.namespace = namespace;
        this.tableName = tableName;
        this.keyNames = keyNames;
        this.flushIntervalMillis = flushIntervalMillis;
        this.bufferSize = bufferSize;
    }

    /**
     * This function may or may not send the record to Stitch
     * immediately. If the client was built with a positive buffer
     * size specified (via {@link
     * StitchClientBuilder#withBufferSize}), this function will add
     *
     */
    public void push(StitchMessage message) throws StitchException, IOException {

        writer.write(messageToMap(message));
        if (buffer.size() >= bufferSize ||
            (System.currentTimeMillis() - lastFlushTime ) >= flushIntervalMillis) {
            flush();
        }
    }

    /**
     * Send any messages currently in the buffer to Stitch.
     */
    public void flush() throws IOException {
        System.out.println(buffer.toString());
        if (buffer.size() == 0) {
            return;
        }

        ArrayList<Map> messages = new ArrayList<Map>(buffer.size());
        ByteArrayInputStream bais = new ByteArrayInputStream(buffer.toByteArray());
        Reader reader = TransitFactory.reader(TransitFactory.Format.JSON, bais);
        boolean running = true;
        while (running) {
            try {
                messages.add((Map)reader.read());
            } catch (RuntimeException e) {
                if (e.getCause() instanceof EOFException) {
                    running = false;
                }
                else {
                    throw e;
                }
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Writer writer = TransitFactory.writer(TransitFactory.Format.JSON, baos);
        writer.write(messages);
        String body = baos.toString("UTF-8");
        System.out.println(body);
        try {
            Request request = Request.Post(stitchUrl)
                .connectTimeout(connectTimeout)
                .addHeader("Authorization", "Bearer " + token)
                .bodyString(body, CONTENT_TYPE);

            HttpResponse response = request.execute().returnResponse();

            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            JsonReader rdr = Json.createReader(entity.getContent());
            StitchResponse stitchResponse = new StitchResponse(
                statusLine.getStatusCode(),
                statusLine.getReasonPhrase(),
                rdr.readObject());
            if (!stitchResponse.isOk()) {
                throw new StitchException(stitchResponse);
            }
        } catch (ClientProtocolException e) {
            throw new RuntimeException(e);
        }

        buffer.reset();
        lastFlushTime = System.currentTimeMillis();
    }

    public void close() throws IOException {
        flush();
    }

}
