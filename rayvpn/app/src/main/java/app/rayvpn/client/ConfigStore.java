package app.rayvpn.client;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ConfigStore {
    public static class Subscription {
        public String id = "";
        public String name = "";
        public String url = "";
        public long updatedAt;
    }

    private final File file;
    public String selectedId = "";
    public final List<Profile> profiles = new ArrayList<>();
    public final List<Subscription> subscriptions = new ArrayList<>();

    public ConfigStore(Context context) {
        file = new File(context.getFilesDir(), "profiles.json");
    }

    public synchronized void load() {
        profiles.clear();
        subscriptions.clear();
        selectedId = "";
        if (!file.exists()) return;
        try {
            byte[] data = readAll(file);
            JSONObject root = new JSONObject(new String(data, StandardCharsets.UTF_8));
            selectedId = root.optString("selectedId");
            JSONArray arr = root.optJSONArray("profiles");
            if (arr != null) {
                for (int i = 0; i < arr.length(); i++) {
                    profiles.add(Profile.fromJson(arr.getJSONObject(i)));
                }
                java.util.HashSet<String> used = new java.util.HashSet<>();
                boolean dirty = false;
                for (Profile p : profiles) {
                    if (p.id == null || p.id.isEmpty() || !used.add(p.id)) {
                        p.id = UUID.randomUUID().toString();
                        used.add(p.id);
                        dirty = true;
                    }
                }
                if (dirty) save();
            }
            JSONArray subs = root.optJSONArray("subscriptions");
            if (subs != null) {
                for (int i = 0; i < subs.length(); i++) {
                    JSONObject o = subs.getJSONObject(i);
                    Subscription s = new Subscription();
                    s.id = o.optString("id");
                    s.name = o.optString("name");
                    s.url = o.optString("url");
                    s.updatedAt = o.optLong("updatedAt");
                    subscriptions.add(s);
                }
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized void save() {
        try {
            JSONObject root = new JSONObject();
            root.put("selectedId", selectedId == null ? "" : selectedId);
            JSONArray arr = new JSONArray();
            for (Profile p : profiles) arr.put(p.toJson());
            root.put("profiles", arr);
            JSONArray subs = new JSONArray();
            for (Subscription s : subscriptions) {
                JSONObject o = new JSONObject();
                o.put("id", s.id);
                o.put("name", s.name);
                o.put("url", s.url);
                o.put("updatedAt", s.updatedAt);
                subs.put(o);
            }
            root.put("subscriptions", subs);
            File tmp = new File(file.getParentFile(), "profiles.json.tmp");
            FileOutputStream out = new FileOutputStream(tmp);
            out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            out.close();
            if (file.exists() && !file.delete()) {
                // overwrite fallback
            }
            if (!tmp.renameTo(file)) {
                FileOutputStream direct = new FileOutputStream(file);
                direct.write(root.toString().getBytes(StandardCharsets.UTF_8));
                direct.close();
                tmp.delete();
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized Profile selected() {
        for (Profile p : profiles) {
            if (p.id.equals(selectedId)) return p;
        }
        return profiles.isEmpty() ? null : profiles.get(0);
    }

    public synchronized Profile byId(String id) {
        if (id == null) return null;
        for (Profile p : profiles) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public synchronized void addAll(List<Profile> incoming, String subscriptionId) {
        for (Profile p : incoming) {
            if (p.id == null || p.id.isEmpty()) p.id = UUID.randomUUID().toString();
            if (p.addedAt == 0) p.addedAt = System.currentTimeMillis();
            if (subscriptionId != null) p.subscriptionId = subscriptionId;
            profiles.add(p);
        }
        if ((selectedId == null || selectedId.isEmpty()) && !profiles.isEmpty()) {
            selectedId = profiles.get(0).id;
        }
        save();
    }

    public synchronized void replaceSubscription(String subscriptionId, List<Profile> incoming) {
        String keepLink = null;
        Profile current = byId(selectedId);
        if (current != null && subscriptionId.equals(current.subscriptionId)) {
            keepLink = current.sourceLink;
        }
        List<Profile> kept = new ArrayList<>();
        for (Profile p : profiles) {
            if (!subscriptionId.equals(p.subscriptionId)) kept.add(p);
        }
        profiles.clear();
        profiles.addAll(kept);
        String newSelected = "";
        for (Profile p : incoming) {
            if (p.id == null || p.id.isEmpty()) p.id = UUID.randomUUID().toString();
            p.subscriptionId = subscriptionId;
            p.addedAt = System.currentTimeMillis();
            profiles.add(p);
            if (keepLink != null && keepLink.equals(p.sourceLink)) newSelected = p.id;
        }
        if (!newSelected.isEmpty()) selectedId = newSelected;
        else if (byId(selectedId) == null) {
            selectedId = profiles.isEmpty() ? "" : profiles.get(0).id;
        }
        save();
    }

    public synchronized Subscription upsertSubscription(String url, String name) {
        for (Subscription s : subscriptions) {
            if (url.equals(s.url)) {
                s.updatedAt = System.currentTimeMillis();
                if (name != null && !name.isEmpty()) s.name = name;
                save();
                return s;
            }
        }
        Subscription s = new Subscription();
        s.id = UUID.randomUUID().toString();
        s.url = url;
        s.name = name == null || name.isEmpty() ? url : name;
        s.updatedAt = System.currentTimeMillis();
        subscriptions.add(s);
        save();
        return s;
    }

    public synchronized void replaceRemote(List<Profile> incoming) {
        Map<String, ArrayDeque<Profile>> old = new HashMap<>();
        for (Profile p : profiles) {
            String key = p.sourceLink == null ? "" : p.sourceLink;
            ArrayDeque<Profile> bucket = old.get(key);
            if (bucket == null) {
                bucket = new ArrayDeque<>();
                old.put(key, bucket);
            }
            bucket.add(p);
        }
        String keepId = selectedId == null ? "" : selectedId;
        String keepLink = "";
        Profile current = byId(selectedId);
        if (current != null && current.sourceLink != null) keepLink = current.sourceLink;
        boolean keepConsumed = false;
        profiles.clear();
        java.util.HashSet<String> used = new java.util.HashSet<>();
        for (Profile p : incoming) {
            String key = p.sourceLink == null ? "" : p.sourceLink;
            ArrayDeque<Profile> bucket = old.get(key);
            Profile prev = bucket == null || bucket.isEmpty() ? null : bucket.poll();
            if (prev != null && prev.id != null && !prev.id.isEmpty() && used.add(prev.id)) {
                p.id = prev.id;
                p.pingMs = prev.pingMs;
                if (prev.id.equals(keepId)) keepConsumed = true;
            } else {
                p.id = UUID.randomUUID().toString();
                used.add(p.id);
                p.pingMs = -2;
            }
            profiles.add(p);
        }
        if (!keepConsumed) {
            selectedId = "";
            if (!keepLink.isEmpty()) {
                for (Profile p : profiles) {
                    if (keepLink.equals(p.sourceLink)) {
                        selectedId = p.id;
                        break;
                    }
                }
            }
            if (selectedId.isEmpty()) selectedId = profiles.isEmpty() ? "" : profiles.get(0).id;
        }
        save();
    }

    public synchronized void sortByPing() {
        profiles.sort((a, b) -> Long.compare(pingRank(a.pingMs), pingRank(b.pingMs)));
        save();
    }

    private static long pingRank(long ping) {
        if (ping >= 0) return ping;
        if (ping == -1) return Long.MAX_VALUE - 1;
        return Long.MAX_VALUE;
    }

    public synchronized void delete(String id) {
        profiles.removeIf(p -> id.equals(p.id));
        if (id.equals(selectedId)) {
            selectedId = profiles.isEmpty() ? "" : profiles.get(0).id;
        }
        save();
    }

    public synchronized void select(String id) {
        selectedId = id;
        save();
    }

    private static byte[] readAll(File f) throws Exception {
        FileInputStream in = new FileInputStream(f);
        byte[] buf = new byte[(int) f.length()];
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) break;
            off += n;
        }
        in.close();
        return buf;
    }
}
