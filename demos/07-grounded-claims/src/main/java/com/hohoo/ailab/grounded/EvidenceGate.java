package com.hohoo.ailab.grounded;

import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.StringReader;
import java.util.*;

/** A closed configuration contract, not general natural-language entailment. */
public final class EvidenceGate {
    public static final class Source {
        public String id, service, environment, revision;
        public Map<String,String> facts;
        public String text;
    }
    public static final class Request {
        public String service, environment, revision;
        public List<String> fields, supplied;
    }
    public static final class Result {
        public final String status;
        public final boolean citationOnly;
        public final Map<String,String> accepted;
        Result(String status, boolean citationOnly, Map<String,String> accepted) {
            this.status=status;this.citationOnly=citationOnly;
            this.accepted=Collections.unmodifiableMap(new TreeMap<String,String>(accepted));
        }
    }
    static Result reject(String status, boolean citation) {
        return new Result(status,citation,Collections.<String,String>emptyMap());
    }
    static List<Map<String,String>> parse(String raw) throws Exception {
        if(raw==null || raw.length()>8192)throw new IllegalArgumentException("length");
        JsonReader r=new JsonReader(new StringReader(raw));r.setLenient(false);
        r.beginObject();if(!r.hasNext() || !"claims".equals(r.nextName()))throw new IllegalArgumentException("claims");
        r.beginArray();List<Map<String,String>> claims=new ArrayList<Map<String,String>>();
        while(r.hasNext()) {
            if(claims.size()>=8)throw new IllegalArgumentException("count");
            Map<String,String> c=new HashMap<String,String>();r.beginObject();
            while(r.hasNext()) {
                String k=r.nextName();
                if(!Arrays.asList("field","value","doc","quote").contains(k) || c.containsKey(k) || r.peek()!=JsonToken.STRING)
                    throw new IllegalArgumentException("field");
                String value=r.nextString();if(value.isEmpty()||value.length()>2048)throw new IllegalArgumentException("value");c.put(k,value);
            }
            r.endObject();if(c.size()!=4)throw new IllegalArgumentException("missing");claims.add(c);
        }
        r.endArray();if(r.hasNext())throw new IllegalArgumentException("additional property");r.endObject();
        if(r.peek()!=JsonToken.END_DOCUMENT)throw new IllegalArgumentException("trailing");r.close();return claims;
    }
    public static Result validate(String raw, Request request, List<Source> registry) {
        List<Map<String,String>> claims;
        try{claims=parse(raw);}catch(Exception e){return reject("INVALID_JSON",false);}
        Map<String,Source> sources=new HashMap<String,Source>();
        for(Source s:registry)if(sources.put(s.id,s)!=null)throw new IllegalArgumentException("duplicate source");
        boolean citation=!claims.isEmpty();
        for(Map<String,String> c:claims) {
            Source s=sources.get(c.get("doc"));
            citation &= s!=null && request.supplied.contains(s.id) && s.text.contains(c.get("quote"));
        }
        if(!citation)return reject("NO_CITATION",false);
        Set<String> fields=new HashSet<String>();Map<String,String> accepted=new TreeMap<String,String>();
        for(Map<String,String> c:claims) {
            String field=c.get("field");Source s=sources.get(c.get("doc"));
            if(!fields.add(field))return reject("DUPLICATE_CLAIM",true);
            if(!request.fields.contains(field))return reject("UNREQUESTED_FIELD",true);
            if(!request.service.equals(s.service)||!request.environment.equals(s.environment))return reject("SCOPE_MISMATCH",true);
            if(!request.revision.equals(s.revision))return reject("STALE_REVISION",true);
            String value=s.facts.get(field);
            if(value==null || !value.equals(c.get("value")))return reject("VALUE_MISMATCH",true);
            if(!(field+"="+value).equals(c.get("quote")))return reject("QUOTE_NOT_SUPPORTING",true);
            accepted.put(field,value);
        }
        if(!fields.equals(new HashSet<String>(request.fields)))return reject("INCOMPLETE",true);
        return new Result("ACCEPT",true,accepted);
    }
}
