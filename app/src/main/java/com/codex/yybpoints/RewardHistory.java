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

    static final class Run {
        final long startedAt;
        final long finishedAt;
        final List<Entry> entries;

        Run(long startedAt, long finishedAt, List<Entry> entries) {
            this.startedAt = startedAt;
            this.finishedAt = finishedAt;
            this.entries = entries;
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
        preferences.edit().putBoolean("finished", false)
                .putLong("started_at", System.currentTimeMillis())
                .putLong("finished_at", 0L).apply();
        persist();
    }

    synchronized void finish() {
        if (!entries.isEmpty() && !isFinished()) {
            preferences.edit().putBoolean("finished", true)
                    .putLong("finished_at", System.currentTimeMillis()).apply();
            archive();
        }
    }

    boolean isFinished() { return preferences.getBoolean("finished", false); }

    long startedAt() { return preferences.getLong("started_at", 0L); }

    long finishedAt() { return preferences.getLong("finished_at", 0L); }

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

    synchronized List<Run> archiveSnapshot() {
        List<Run> runs = new ArrayList<>();
        try {
            JSONArray stored = new JSONArray(preferences.getString("archive_runs", "[]"));
            for (int i = stored.length() - 1; i >= 0; i--) {
                JSONObject item = stored.optJSONObject(i);
                if (item == null) continue;
                List<Entry> items = new ArrayList<>();
                JSONArray claims = item.optJSONArray("entries");
                if (claims != null) for (int j = 0; j < claims.length(); j++) {
                    JSONObject claim = claims.optJSONObject(j);
                    if (claim != null) items.add(new Entry(claim.optInt("sequence"),
                            claim.optString("taskId"), claim.optInt("points", -1)));
                }
                runs.add(new Run(item.optLong("startedAt"), item.optLong("finishedAt"), items));
            }
        } catch (Throwable ignored) { runs.clear(); }
        return runs;
    }

    private void archive() {
        long startedAt = startedAt();
        long finishedAt = finishedAt();
        if (startedAt <= 0 || finishedAt < startedAt || entries.isEmpty()) return;
        try {
            JSONArray old = new JSONArray(preferences.getString("archive_runs", "[]"));
            JSONArray updated = new JSONArray();
            int first = Math.max(0, old.length() - 13);
            for (int i = first; i < old.length(); i++) {
                JSONObject item = old.optJSONObject(i);
                if (item != null && item.optLong("startedAt") != startedAt) updated.put(item);
            }
            JSONObject run = new JSONObject();
            run.put("startedAt", startedAt);
            run.put("finishedAt", finishedAt);
            run.put("entries", serializeEntries());
            updated.put(run);
            preferences.edit().putString("archive_runs", updated.toString()).apply();
        } catch (Throwable ignored) { }
    }

    private void persist() {
        preferences.edit().putString("current_run", serializeEntries().toString()).apply();
        if (isFinished()) archive();
    }

    private JSONArray serializeEntries() {
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
        return saved;
    }
}
