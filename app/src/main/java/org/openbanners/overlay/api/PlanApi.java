package org.openbanners.overlay.api;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Mission Day plans from openbanners.org/planner/{id}: the missions someone picked from one or more banners, in
 * their own order. The overlay follows a plan as one banner made of the missions that aren't done yet, and ticks
 * them off in the plan as you go.
 */
public class PlanApi {
    private static final OkHttpClient client = new OkHttpClient.Builder().build();
    private static final ObjectMapper objectMapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    public static class PlanItem {
        public String bannerId;
        public String missionId;
        public boolean done;
    }

    public static class Plan {
        public String id;
        public String title;
        public List<PlanItem> items = new ArrayList<>();
    }

    private static String planUrl(String site, String planId) {
        return site + "/_openbanners/passphrases/v1/plans/" + planId;
    }

    public static Plan getPlan(String site, String planId) throws IOException {
        Request request = new Request.Builder().url(planUrl(site, planId)).build();
        try (Response response = client.newCall(request).execute()) {
            ResponseBody body = response.body();
            if (!response.isSuccessful() || body == null) {
                throw new IOException(response.code() == 404 ? "This plan doesn't exist" : "Couldn't load the plan");
            }
            return objectMapper.readValue(body.string(), Plan.class);
        }
    }

    /** The plan as one banner: its missions that aren't done, in the plan's order (all of them if all are done). */
    public static Banner getPlanRoute(String site, String planId) throws IOException {
        Plan plan = getPlan(site, planId);
        boolean allDone = plan.items.stream().allMatch(item -> item.done);
        Map<String, Banner> banners = new HashMap<>();
        TreeMap<Integer, Mission> missions = new TreeMap<>();
        for (PlanItem item : plan.items) {
            if (item.done && !allDone) continue;
            Banner source = banners.get(item.bannerId);
            if (source == null) {
                source = BannerApi.getBanner(item.bannerId);
                banners.put(item.bannerId, source);
            }
            for (Mission mission : source.missions.values()) {
                if (mission.id.equals(item.missionId)) {
                    missions.put(missions.size(), mission);
                    break;
                }
            }
        }
        if (missions.isEmpty()) {
            throw new IOException("This plan has no missions yet");
        }
        Banner route = new Banner();
        route.title = plan.title;
        route.missions = missions;
        route.planId = planId;
        route.planSite = site;
        return route;
    }

    /** Ticks a mission off in the plan (everyone with the plan sees it). */
    public static void markDone(String site, String planId, String missionId) throws IOException {
        // Plain maps: Map.of needs Android 11, the app runs on 8 and up.
        Map<String, Object> op = new HashMap<>();
        op.put("op", "done");
        op.put("missionId", missionId);
        op.put("done", true);
        List<Object> ops = new ArrayList<>();
        ops.add(op);
        Map<String, Object> body = new HashMap<>();
        body.put("ops", ops);
        String json = objectMapper.writeValueAsString(body);
        Request request = new Request.Builder().url(planUrl(site, planId)).post(RequestBody.create(json, JSON)).build();
        try (Response ignored = client.newCall(request).execute()) {
            // Best effort: the plan page still lets you tick it off by hand.
        }
    }
}
