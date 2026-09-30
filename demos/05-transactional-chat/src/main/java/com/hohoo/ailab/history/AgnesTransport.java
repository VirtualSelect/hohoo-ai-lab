package com.hohoo.ailab.history;
import com.google.gson.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public final class AgnesTransport implements Session.Transport {
    private final String key;
    public AgnesTransport(String key) {
        if(key==null || key.trim().isEmpty()) throw new IllegalArgumentException("AGNES_API_KEY required");
        this.key=key;
    }
    public Session.Reply send(String json) throws Session.Failure {
        HttpURLConnection c=null;
        try {
            c=(HttpURLConnection)new URL("https://apihub.agnes-ai.com/v1/chat/completions").openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(10000); c.setReadTimeout(90000);
            c.setDoOutput(true); c.setRequestProperty("Content-Type","application/json; charset=UTF-8");
            c.setRequestProperty("Authorization","Bearer "+key);
            byte[] bytes=json.getBytes(StandardCharsets.UTF_8); c.setFixedLengthStreamingMode(bytes.length);
            try(OutputStream out=c.getOutputStream()) { out.write(bytes); }
            int status=c.getResponseCode();
            if(status<200 || status>=300) throw new Session.Failure("HTTP_"+status);
            ByteArrayOutputStream body=new ByteArrayOutputStream();
            try(InputStream in=c.getInputStream()) {
                byte[] buffer=new byte[4096]; int n;
                while((n=in.read(buffer))!=-1) {
                    if(body.size()+n>262144) throw new Session.Failure("RESPONSE_SIZE");
                    body.write(buffer,0,n);
                }
            }
            return decode(new String(body.toByteArray(),StandardCharsets.UTF_8));
        } catch(SocketTimeoutException e) { throw new Session.Failure("TIMEOUT"); }
          catch(IOException e) { throw new Session.Failure("IO"); }
        finally { if(c!=null) c.disconnect(); }
    }
    public static Session.Reply decode(String body) throws Session.Failure {
        try {
            JsonObject choice=JsonParser.parseString(body).getAsJsonObject()
                .getAsJsonArray("choices").get(0).getAsJsonObject();
            JsonElement content=choice.getAsJsonObject("message").get("content");
            JsonElement finish=choice.get("finish_reason");
            if(content==null || !content.isJsonPrimitive() || !content.getAsJsonPrimitive().isString())
                throw new Session.Failure("PARSE");
            String reason=finish!=null && finish.isJsonPrimitive() && finish.getAsJsonPrimitive().isString()
                ? finish.getAsString():null;
            return new Session.Reply(content.getAsString(),reason);
        } catch(RuntimeException e) { throw new Session.Failure("PARSE"); }
    }
}
