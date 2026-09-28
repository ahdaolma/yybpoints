package com.codex.yybpoints;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Confirmed claims from the current background run, stored only in module app data. */
final class RewardHistory {
    static final class Entry {
        final int sequence;
        final String taskId;
        final int points;

        Entry(int sequence, String taskId, int points) {
            this.sequence = sequence;
            this.taskId = taskId;
            this.points = points;
        }
    }

    private final SharedPreferences preferences;
    private final List<Entry> entries = new ArrayList<>();

    RewardHistory(Context context) {
        preferences = context.getSharedPreferences("reward_history", Context.MODE_PRIVATE);
        try {
            JSONArray saved = new JSONArray(preferences.getString("current_run", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.optJSONObject(i);
                if (item == null) continue;
                entries.add(new Entry(item.optInt("sequence"), item.optString("taskId"),
                        item.optInt("points", -1)));
            }
        } catch (Throwable ignored) { entries.clear(); }
    }

    synchronized void reset() {
        entries.clear();
        persist();
    }

    synchronized void record(int sequence, String taskId, int points) {
        if (sequence <= 0 || taskId == null || taskId.isEmpty()) return;
        for (int i = 0; i < entries.size(); i++) {
            Entry existing = entries.get(i);
            if (existing.sequence == sequence) {
                entries.set(i, new Entry(sequence, taskId,
                        points > 0 ? points : existing.points));
                persist();
                return;
            }
        }
        entries.add(new Entry(sequence, taskId, points));
        if (entries.size() > 100) entries.remove(0);
        persist();
    }

    synchronized List<Entry> snapshot() { return new ArrayList<>(entries); }

    private void persist() {
        JSONArray saved = new JSONArray();
        for (Entry entry : entries) {
            JSONObject item = new JSONObject();
            try {
                item.put("sequence", entry.sequence);
                item.put("taskId", entry.taskId);
                item.put("points", entry.points);
                saved.put(item);
            } catch (Throwable ignored) { }
        }
        preferences.edit().putString("current_run", saved.toString()).apply();
    }
}
