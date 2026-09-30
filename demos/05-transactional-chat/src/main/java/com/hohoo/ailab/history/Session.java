package com.hohoo.ailab.history;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Sequential in-memory session; commit only a validated complete pair. */
public final class Session {
    public static final String MODEL = "agnes-3.0-flash";
    public static final class Message {
        public final String role, content;
        Message(String role, String content) { this.role=role; this.content=content; }
    }
    public static final class Reply {
        public final String content, finishReason;
        public Reply(String content, String finishReason) { this.content=content; this.finishReason=finishReason; }
    }
    public static final class Failure extends Exception {
        public final String code;
        public Failure(String code) { super(code); this.code=code; }
    }
    public interface Transport { Reply send(String requestJson) throws Failure; }
    private final int maxPairs, maxRequestBytes;
    private List<Message> committed = new ArrayList<Message>();
    public Session(int maxPairs, int maxRequestBytes) {
        if (maxPairs < 1 || maxRequestBytes < 1) throw new IllegalArgumentException("positive limits required");
        this.maxPairs=maxPairs; this.maxRequestBytes=maxRequestBytes;
    }
    public synchronized List<Message> snapshot() {
        return Collections.unmodifiableList(new ArrayList<Message>(committed));
    }
    private String serialize(List<Message> candidate) {
        JsonObject root=new JsonObject(); root.addProperty("model",MODEL);
        JsonArray messages=new JsonArray();
        JsonObject system=new JsonObject(); system.addProperty("role","system");
        system.addProperty("content","Answer clearly. If the provided messages do not contain a fact, say you do not know.");
        messages.add(system);
        for (Message m:candidate) {
            JsonObject item=new JsonObject(); item.addProperty("role",m.role); item.addProperty("content",m.content);
            messages.add(item);
        }
        root.add("messages",messages); return root.toString();
    }
    public synchronized Reply ask(String question, Transport transport) throws Failure {
        if(question==null || question.trim().isEmpty()) throw new Failure("INPUT");
        List<Message> candidate=new ArrayList<Message>(committed);
        candidate.add(new Message("user",question));
        String request=serialize(candidate);
        while(request.getBytes(StandardCharsets.UTF_8).length>maxRequestBytes && candidate.size()>1) {
            candidate.subList(0,2).clear(); // Remove a complete user/assistant pair.
            request=serialize(candidate);
        }
        if(request.getBytes(StandardCharsets.UTF_8).length>maxRequestBytes) throw new Failure("BUDGET");
        Reply reply=transport.send(request); // Failure leaves committed untouched.
        if(reply==null || reply.content==null || reply.content.trim().isEmpty()) throw new Failure("EMPTY");
        if(!"stop".equals(reply.finishReason)) throw new Failure("INCOMPLETE");
        candidate.add(new Message("assistant",reply.content));
        while(candidate.size()>maxPairs*2) candidate.subList(0,2).clear();
        committed=candidate; // The only state-changing statement in a successful turn.
        return reply;
    }
}
