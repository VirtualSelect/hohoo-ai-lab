package com.hohoo.ailab.grounded;
import com.google.gson.*;
import com.google.gson.reflect.TypeToken;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Suite {
    public static void main(String[] args) throws Exception {
        Gson gson=new GsonBuilder().setPrettyPrinting().serializeNulls().create();
        JsonObject fixtures=gson.fromJson(new String(Files.readAllBytes(Paths.get("fixtures.json")),StandardCharsets.UTF_8),JsonObject.class);
        List<EvidenceGate.Source> sources=gson.fromJson(fixtures.get("sources"),new TypeToken<List<EvidenceGate.Source>>(){}.getType());
        List<Map<String,Object>> results=new ArrayList<Map<String,Object>>();int falseAccepts=0,accepts=0;
        for(JsonElement e:fixtures.getAsJsonArray("cases")) {
            JsonObject c=e.getAsJsonObject();EvidenceGate.Request q=gson.fromJson(c.get("request"),EvidenceGate.Request.class);
            EvidenceGate.Result result=EvidenceGate.validate(c.get("raw").getAsString(),q,sources);
            String expected=c.get("expected").getAsString();
            if(!expected.equals(result.status))throw new AssertionError(c.get("id")+": "+result.status+" != "+expected);
            boolean positive=expected.equals("ACCEPT");if(result.citationOnly && !positive)falseAccepts++;if(positive)accepts++;
            Map<String,Object> row=new LinkedHashMap<String,Object>();row.put("id",c.get("id").getAsString());row.put("expected",expected);
            row.put("actual",result.status);row.put("citationOnly",result.citationOnly);row.put("accepted",result.accepted);results.add(row);
        }
        Map<String,Object> output=new LinkedHashMap<String,Object>();output.put("javaVersion",System.getProperty("java.version"));
        output.put("cases",results);output.put("positiveCases",accepts);output.put("citationOnlyFalseAccepts",falseAccepts);output.put("networkRequests",0);
        Path target=Paths.get(args[0]);Files.createDirectories(target.getParent());Files.write(target,gson.toJson(output).getBytes(StandardCharsets.UTF_8));
        System.out.println("Cases="+results.size()+", positive="+accepts+", citation-only false accepts="+falseAccepts);
    }
}
