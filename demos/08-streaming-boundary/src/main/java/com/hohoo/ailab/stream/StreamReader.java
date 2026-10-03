package com.hohoo.ailab.stream;

import com.google.gson.*;
import com.google.gson.stream.*;
import java.io.*;
import java.nio.charset.*;
import java.util.*;
import java.util.function.Consumer;

/** A bounded SSE reader and a deliberately narrow text-completion protocol. */
public final class StreamReader {
    public static final class Failure extends IOException {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }
    static JsonElement value(JsonReader r,int depth) throws IOException {
        if(depth>16)throw new Failure("INVALID_JSON");
        switch(r.peek()) {
        case BEGIN_OBJECT:
            JsonObject o=new JsonObject();r.beginObject();
            while(r.hasNext()) {String k=r.nextName();if(o.has(k))throw new Failure("INVALID_JSON");o.add(k,value(r,depth+1));}
            r.endObject();return o;
        case BEGIN_ARRAY:
            JsonArray a=new JsonArray();r.beginArray();while(r.hasNext())a.add(value(r,depth+1));r.endArray();return a;
        case STRING:return new JsonPrimitive(r.nextString());
        case NUMBER:return new JsonPrimitive(new java.math.BigDecimal(r.nextString()));
        case BOOLEAN:return new JsonPrimitive(r.nextBoolean());
        case NULL:r.nextNull();return JsonNull.INSTANCE;
        default:throw new Failure("INVALID_JSON");
        }
    }
    static JsonObject object(String raw) throws Failure {
        try(JsonReader r=new JsonReader(new StringReader(raw))) {
            r.setLenient(false);JsonElement e=value(r,0);
            if(r.peek()!=JsonToken.END_DOCUMENT||!e.isJsonObject())throw new Failure("INVALID_JSON");
            return e.getAsJsonObject();
        } catch(Exception e){throw new Failure("INVALID_JSON");}
    }
    static String text(JsonElement v) throws Failure {
        if(v==null||!v.isJsonPrimitive()||!v.getAsJsonPrimitive().isString())throw new Failure("PROTOCOL");
        return v.getAsString();
    }
    private final StringBuilder preview=new StringBuilder();
    private boolean stopped=false;
    private int events=0;
    private boolean event(String data,Consumer<String> onPreview) throws IOException {
        if(++events>256)throw new Failure("LIMIT");
        if(data.equals("[DONE]")) {
            if(!stopped||preview.length()==0)throw new Failure("PROTOCOL");
            return true;
        }
        JsonObject root=object(data);
        if(root.has("error"))throw new Failure("PROVIDER_ERROR");
        try {
            JsonArray choices=root.getAsJsonArray("choices");
            if(choices.size()==0&&root.has("usage")&&root.get("usage").isJsonObject())return false;
            if(choices.size()!=1)throw new Failure("PROTOCOL");
            JsonObject c=choices.get(0).getAsJsonObject();
            if(!c.get("index").isJsonPrimitive()||!c.get("index").getAsJsonPrimitive().isNumber()||c.get("index").getAsBigDecimal().compareTo(java.math.BigDecimal.ZERO)!=0)throw new Failure("PROTOCOL");
            JsonObject delta=c.getAsJsonObject("delta");
            for(String key:delta.keySet())if(!key.equals("role")&&!key.equals("content"))throw new Failure("PROTOCOL");
            if(delta.has("role")&&!text(delta.get("role")).equals("assistant"))throw new Failure("PROTOCOL");
            String addition=delta.has("content")&&!delta.get("content").isJsonNull()?text(delta.get("content")):"";
            if(stopped&&(!addition.isEmpty()||c.has("finish_reason")&&!c.get("finish_reason").isJsonNull()))throw new Failure("PROTOCOL");
            if(preview.length()+addition.length()>4096)throw new Failure("LIMIT");
            if(!addition.isEmpty()){preview.append(addition);onPreview.accept(preview.toString());}
            if(c.has("finish_reason")&&!c.get("finish_reason").isJsonNull()) {
                if(!text(c.get("finish_reason")).equals("stop"))throw new Failure("INCOMPLETE_FINISH");
                stopped=true;
            }
            return false;
        }catch(Failure e){throw e;}catch(RuntimeException e){if(e instanceof Cancelled)throw e;throw new Failure("PROTOCOL");}
    }
    public static final class Cancelled extends RuntimeException { }
    public String preview(){return preview.toString();}
    public String read(InputStream input,Consumer<String> onPreview) throws IOException {
        // Decoder state crosses read boundaries; malformed UTF-8 is rejected, not replaced.
        Reader r=new InputStreamReader(input,StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
        StringBuilder line=new StringBuilder(),data=new StringBuilder();boolean afterCR=false,first=true;
        int total=0;long deadline=System.nanoTime()+2_000_000_000L;
        for(int ch;(ch=r.read())!=-1;) {
            if(++total>65536||System.nanoTime()>deadline)throw new Failure("LIMIT");
            if(first){first=false;if(ch==0xfeff)continue;}
            if(afterCR&&ch=='\n'){afterCR=false;continue;}afterCR=false;
            if(ch=='\r'||ch=='\n') {
                afterCR=ch=='\r';String s=line.toString();line.setLength(0);
                if(s.isEmpty()) {
                    if(data.length()>0){data.setLength(data.length()-1);boolean done=event(data.toString(),onPreview);data.setLength(0);if(done)return preview.toString();}
                } else if(!s.startsWith(":")) {
                    int colon=s.indexOf(':');String field=colon<0?s:s.substring(0,colon);String v=colon<0?"":s.substring(colon+1);if(v.startsWith(" "))v=v.substring(1);
                    if(field.equals("data")){if(data.length()+v.length()+1>8192)throw new Failure("LIMIT");data.append(v).append('\n');}
                    // event/id/retry fields have no application meaning in this single text turn.
                }
            } else {if(line.length()>=8192)throw new Failure("LIMIT");line.append((char)ch);}
        }
        // No automatic reconnect or acceptance of an unterminated final SSE event.
        throw new Failure("INCOMPLETE_STREAM");
    }
}
