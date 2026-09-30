package com.hohoo.ailab.history;
import com.google.gson.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

public final class TurnDemo {
    private static final List<String> checks=new ArrayList<String>();
    private static final List<String> requests=new ArrayList<String>();
    private static void check(String name, boolean ok) {
        if(!ok) throw new AssertionError(name); checks.add(name);
    }
    private static Session.Transport ok(final String text) {
        return request -> { requests.add(request); return new Session.Reply(text,"stop"); };
    }
    private static String state(Session s) { return new Gson().toJson(s.snapshot()); }
    private static void fails(String code, Session s, String q, Session.Transport t) throws Exception {
        String before=state(s); int n=requests.size();
        try { s.ask(q,t); throw new AssertionError("expected "+code); }
        catch(Session.Failure f) { check(code+" classified",code.equals(f.code)); }
        check(code+" history unchanged",before.equals(state(s)));
        check(code+" at most one attempt",requests.size()-n<=1);
    }
    private static String hash(Path p) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(p));
        StringBuilder b=new StringBuilder(); for(byte v:digest)b.append(String.format("%02x",v)); return b.toString();
    }
    private static void selfTest(Path out) throws Exception {
        Session s=new Session(2,10000);
        s.ask("我在学习 Java",ok("记录的是 Java"));
        check("first pair committed",s.snapshot().size()==2);
        for(String code:Arrays.asList("TIMEOUT","HTTP_429","PARSE")) {
            fails(code,s,"这条失败消息不应保留",request->{ requests.add(request); throw new Session.Failure(code); });
        }
        fails("EMPTY",s,"空响应",request->{requests.add(request);return new Session.Reply(" ","stop");});
        fails("INCOMPLETE",s,"截断响应",request->{requests.add(request);return new Session.Reply("半句话","length");});
        fails("INPUT",s," ",ok("不可调用"));
        s.ask("接下来学什么",ok("可以学习接口"));
        JsonArray second=JsonParser.parseString(requests.get(requests.size()-1)).getAsJsonObject().getAsJsonArray("messages");
        check("request excludes failed questions",!second.toString().contains("失败消息"));
        check("history order plus current",second.size()==4 && second.get(1).getAsJsonObject().get("role").getAsString().equals("user")
            && second.get(2).getAsJsonObject().get("role").getAsString().equals("assistant"));
        check("new model configured",requests.get(0).contains("agnes-3.0-flash"));
        s.ask("第三轮",ok("第三轮回答"));
        check("pair retention limit",s.snapshot().size()==4 && s.snapshot().get(0).content.equals("接下来学什么"));
        List<Session.Message> snapshot=s.snapshot(); boolean immutable=false;
        try { snapshot.clear(); } catch(UnsupportedOperationException e) { immutable=true; }
        check("snapshot immutable",immutable);
        s.ask("第四轮",ok("第四轮回答")); check("snapshot detached",snapshot.get(0).content.equals("接下来学什么"));
        Session limited=new Session(2,320);
        limited.ask("旧问题",ok(String.join("",Collections.nCopies(140,"答"))));
        limited.ask("当前",ok("答"));
        JsonArray sent=JsonParser.parseString(requests.get(requests.size()-1)).getAsJsonObject().getAsJsonArray("messages");
        check("budget removes whole old pair",sent.size()==2 && sent.get(1).getAsJsonObject().get("content").getAsString().equals("当前"));
        check("actual UTF8 wire budget",requests.get(requests.size()-1).getBytes(StandardCharsets.UTF_8).length<=320);
        check("evicted pair does not resurface",limited.snapshot().size()==2 && limited.snapshot().get(0).content.equals("当前"));
        fails("BUDGET",limited,String.join("",Collections.nCopies(200,"长")),ok("不可调用"));
        check("budget no transport call",!requests.get(requests.size()-1).contains("长长长"));
        Session rollback=new Session(2,320); rollback.ask("保留",ok(String.join("",Collections.nCopies(140,"答"))));
        fails("TIMEOUT",rollback,"需要剪裁的候选",request->{requests.add(request);throw new Session.Failure("TIMEOUT");});
        check("failed trimmed candidate preserves original pair",rollback.snapshot().get(0).content.equals("保留"));
        for(String invalid:Arrays.asList("{}","not-json","{\"choices\":[]}","{\"choices\":[{\"message\":{\"content\":42}}]}")) {
            try { AgnesTransport.decode(invalid); throw new AssertionError("expected parse rejection"); }
            catch(Session.Failure f) { check("malformed envelope rejected",f.code.equals("PARSE")); }
        }
        Session.Reply r=AgnesTransport.decode("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"离线响应\"}}]}");
        check("envelope content parsed",r.content.equals("离线响应"));
        JsonObject report=new JsonObject(); report.addProperty("kind","offline-synthetic-contract-tests");
        report.addProperty("networkRequests",0); report.addProperty("javaVersion",System.getProperty("java.version"));
        report.addProperty("checksPassed",checks.size()); report.add("checks",new Gson().toJsonTree(checks));
        report.addProperty("transportInvocations",requests.size()); report.add("syntheticRequests",new Gson().toJsonTree(requests));
        JsonObject hashes=new JsonObject();
        for(String f:Arrays.asList("Session.java","AgnesTransport.java","TurnDemo.java")) {
            Path p=Paths.get("src/main/java/com/hohoo/ailab/history/"+f); hashes.addProperty(p.toString().replace('\\','/'),hash(p));
        }
        hashes.addProperty("pom.xml",hash(Paths.get("pom.xml"))); report.add("sourceSha256",hashes);
        Files.createDirectories(out.getParent());
        Files.write(out,new GsonBuilder().setPrettyPrinting().create().toJson(report).getBytes(StandardCharsets.UTF_8),StandardOpenOption.CREATE_NEW);
        System.out.println("Passed "+checks.size()+" checks; "+requests.size()+" synthetic transport calls; zero network requests.");
    }
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("--self-test")) { selfTest(Paths.get(args[1])); return; }
        if(args.length!=1 || !args[0].equals("--live")) { System.out.println("Use --self-test evidence/<new-report>.json or --live"); return; }
        AgnesTransport transport=new AgnesTransport(System.getenv("AGNES_API_KEY"));
        Session s=new Session(4,16000);
        try(Scanner in=new Scanner(System.in,"UTF-8")) {
            System.out.println("Java 8 / Agnes 3.0; exit to quit. No automatic retries.");
            while(in.hasNextLine()) {
                String q=in.nextLine(); if(q.trim().equalsIgnoreCase("exit"))break;
                try { System.out.println(s.ask(q,transport).content); }
                catch(Session.Failure f) { System.out.println("Not committed: "+f.code+"; history unchanged. Timeout does not establish whether the provider processed the request."); }
            }
        }
    }
}
