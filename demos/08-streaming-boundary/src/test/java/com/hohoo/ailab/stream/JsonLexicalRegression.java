package com.hohoo.ailab.stream;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

/** Offline regression; no provider, credentials or network required. */
public final class JsonLexicalRegression {
    static void rejected(String raw) throws Exception {
        try { StreamReader.object(raw); }
        catch(StreamReader.Failure e) {
            if(!"INVALID_JSON".equals(e.code))throw new AssertionError(e.code);
            return;
        }
        throw new AssertionError("Accepted invalid JSON: "+raw);
    }
    public static void main(String[] args) throws Exception {
        int checked=0;
        for(int c=0;c<32;c++) { rejected("{\"x\":\"a"+(char)c+"b\"}");checked++; }
        for(String raw:new String[]{"{\"x\":TRUE}","{\"x\":False}","{\"x\":NULL}",
            "{\"x\":NaN}","{\"x\":01}","{\"x\":+1}","{\"x\":.1}","{\"x\":1.}",
            "{\"x\":\"\\'\"}","{\"x\":\"\\v\"}","{\"x\":\"\\uZZZZ\"}","{'x':1}",
            "{\"x\":1,\"x\":2}","{\"x\":1}{}","{\"x\":1,}","{\"x\":/*comment*/1}"}) {
            rejected(raw);checked++;
        }
        for(String raw:new String[]{"{\"x\":\"a\\nb\\tc\"}","{\"x\":\"\\\"\\\\\\/\\b\\f\\n\\r\\t\\u0000\"}",
            "{\"x\":\"中文🌱\"}"," {\"x\":[true,false,null,-1,0,1.5,1e-3]} \r\n"}) {
            StreamReader.object(raw);checked++;
        }
        String broken="data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"a\ndata: b\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n";
        StreamReader reader=new StreamReader();
        try {reader.read(new ByteArrayInputStream(broken.getBytes(StandardCharsets.UTF_8)),p->{throw new AssertionError("invalid preview");});throw new AssertionError("invalid SSE accepted");}
        catch(StreamReader.Failure e){if(!"INVALID_JSON".equals(e.code))throw new AssertionError(e.code);}
        if(!reader.preview().isEmpty())throw new AssertionError("partial preview");
        System.out.println("JSON lexical regression: "+(checked+1)+" passed; provider requests: 0");
    }
}
