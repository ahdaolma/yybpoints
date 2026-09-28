package com.codex.yybpoints;

import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** Deliberately omits body values, headers, credentials, IDs, and response data. */
final class ProbeSummary {
    private ProbeSummary() {}

    static String summarize(JSONObject request) {
        if (request == null) return null;
        String url = request.optString("url", "");
        String path;
        try { path = Uri.parse(url).getPath(); }
        catch (Exception ignored) { path = "unknown"; }
        int commandId = request.optInt("cmdId", -1);
        String normalizedPath = path == null ? "" : path.toLowerCase(Locale.ROOT);
        boolean taskCommand = commandId >= 5400 && commandId <= 5406;
        boolean taskPath = normalizedPath.contains("point") || normalizedPath.contains("task")
                || normalizedPath.contains("reward") || normalizedPath.contains("adbonus");
        if (!taskCommand && !taskPath) return null;
        String body = request.optString("body", "");
        String shape = "opaque";
        try { shape = keys(new JSONObject(body)); }
        catch (Exception ignored) {
            try { shape = "array(" + new JSONArray(body).length() + ")"; }
            catch (Exception ignoredAgain) { /* Opaque payload. */ }
        }
        return "cmd=" + commandId + " path=" + path
                + " bodyKeys=" + shape;
    }

    private static String keys(JSONObject json) {
        List<String> names = new ArrayList<>();
        Iterator<String> iterator = json.keys();
        while (iterator.hasNext() && names.size() < 24) names.add(iterator.next());
        Collections.sort(names);
        return names.toString();
    }
}
