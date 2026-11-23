package org.wwhdrecomp.app;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

final class ContentPacks {
    private ContentPacks() {}

    static final class Pack {
        final File dir;      // holds content/
        final String name;
        Pack(File dir, String name) { this.dir = dir; this.name = name; }
    }

    static File installDir(File base) { return new File(base, "mods/packs"); }

    static File sharedDir() { return new File(SaveVault.root(), "Mods"); }

    /** every pack found, by name */
    static List<Pack> list(Context c, File base) {
        List<Pack> out = new ArrayList<>();
        List<File> roots = new ArrayList<>();
        roots.add(installDir(base));
        if (SaveVault.enabled(c)) roots.add(sharedDir());
        for (File root : roots) {
            File[] dirs = root.listFiles(File::isDirectory);
            if (dirs == null) continue;
            for (File d : dirs) {
                File holder = contentHolder(d, 0);
                if (holder != null) out.add(new Pack(holder, name(d)));
            }
        }
        out.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        return out;
    }

    private static File contentHolder(File d, int depth) {
        File[] kids = d.listFiles(File::isDirectory);
        if (kids == null) return null;
        for (File k : kids)
            if (k.getName().equalsIgnoreCase("content")) return d;
        if (depth == 0)
            for (File k : kids) {
                File h = contentHolder(k, 1);
                if (h != null) return h;
            }
        return null;
    }

    private static String name(File d) {
        for (File f : new File[] {new File(d, "rules.txt"), new File(d, "label.txt")}) {
            if (!f.exists()) continue;
            try {
                String s = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
                for (String line : s.split("\n")) {
                    line = line.trim();
                    if (f.getName().equals("label.txt") && !line.isEmpty()) return line;
                    if (line.toLowerCase(Locale.ROOT).startsWith("name")) {
                        int eq = line.indexOf('=');
                        if (eq < 0) continue;
                        String v = line.substring(eq + 1).trim().replace("\"", "");
                        if (!v.isEmpty()) return v;
                    }
                }
            } catch (IOException ignored) {
            }
        }
        return d.getName();
    }

    private static Set<String> off(SharedPreferences prefs) { return new HashSet<>(prefs.getStringSet("packs_off", new HashSet<>())); }

    static boolean enabled(SharedPreferences prefs, Pack p) { return !off(prefs).contains(p.dir.getAbsolutePath()); }

    static void setEnabled(SharedPreferences prefs, Pack p, boolean on) {
        Set<String> s = off(prefs);
        if (on) s.remove(p.dir.getAbsolutePath());
        else s.add(p.dir.getAbsolutePath());
        prefs.edit().putStringSet("packs_off", s).commit();
    }

    /** the folders of the packs that are on, the first one winning */
    static List<String> overlays(Context c, File base, SharedPreferences prefs) {
        List<String> out = new ArrayList<>();
        for (Pack p : list(c, base))
            if (enabled(prefs, p)) out.add(p.dir.getAbsolutePath());
        return out;
    }

    /** true if `p` was installed by the app (files/mods/packs), so it can be removed from here */
    static boolean removable(File base, Pack p) {
        return p.dir.getAbsolutePath().startsWith(installDir(base).getAbsolutePath() + "/");
    }

    static void remove(File base, Pack p) {
        File top = p.dir;
        while (top.getParentFile() != null && !top.getParentFile().equals(installDir(base))) top = top.getParentFile();
        Backup.deleteTree(top);
    }

    /**
     * Copies the content/ files found under `tree` (a pack the user chose) into files/mods/packs/<name>.
     */
    static String install(Context c, Uri tree, File base) {
        ContentResolver cr = c.getContentResolver();
        File root = installDir(base);
        File tmp = new File(root, ".part");
        Backup.deleteTree(tmp);
        if (!new File(tmp, "content").mkdirs()) return "cannot create " + tmp;
        int[] copied = {0};
        String[] label = {""};
        try {
            walk(cr, tree, DocumentsContract.getTreeDocumentId(tree), new ArrayList<>(), (path, uri) -> {
                String file = path.get(path.size() - 1);
                int ci = -1;
                for (int k = 0; k < path.size() - 1; k++)
                    if (path.get(k).equalsIgnoreCase("content")) { ci = k; break; }
                if (ci >= 0) {
                    File out = new File(tmp, "content/" + String.join("/", path.subList(ci + 1, path.size())));
                    if (out.exists()) return;
                    //noinspection ResultOfMethodCallIgnored
                    out.getParentFile().mkdirs();
                    copy(cr, uri, out);
                    copied[0]++;
                } else if (file.equalsIgnoreCase("rules.txt") && label[0].isEmpty()) {
                    File out = new File(tmp, "rules.txt");
                    copy(cr, uri, out);
                    label[0] = name(tmp);
                }
            });
        } catch (IOException e) {
            Backup.deleteTree(tmp);
            return e.getMessage();
        }
        if (copied[0] == 0) {
            Backup.deleteTree(tmp);
            return c.getString(R.string.packs_not_found);
        }
        String folder = (label[0].isEmpty() ? treeName(cr, tree) : label[0]).replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
        if (folder.isEmpty() || folder.startsWith(".")) folder = "pack";
        File dest = new File(root, folder);
        Backup.deleteTree(dest);
        if (!tmp.renameTo(dest)) return "cannot move the pack into place";
        return null;
    }

    /**
     * Extracts the content/ files found inside a .zip archive into files/mods/packs/<name>.
     */
    static String installZip(Context c, Uri zipUri, File base) {
        ContentResolver cr = c.getContentResolver();
        File root = installDir(base);
        File tmp = new File(root, ".part");
        Backup.deleteTree(tmp);
        if (!new File(tmp, "content").mkdirs()) return "cannot create " + tmp;
        int copied = 0;
        String label = "";

        try (InputStream in = cr.openInputStream(zipUri);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(in))) {
            if (in == null) return "cannot read zip";
            ZipEntry entry;
            byte[] buf = new byte[1 << 16];
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    zis.closeEntry();
                    continue;
                }
                String entryName = entry.getName().replace('\\', '/');
                String[] parts = entryName.split("/");
                String fileName = parts[parts.length - 1];

                int ci = -1;
                for (int k = 0; k < parts.length - 1; k++) {
                    if (parts[k].equalsIgnoreCase("content")) {
                        ci = k;
                        break;
                    }
                }

                if (ci >= 0) {
                    StringBuilder rel = new StringBuilder("content");
                    for (int k = ci + 1; k < parts.length; k++) {
                        rel.append("/").append(parts[k]);
                    }
                    File out = new File(tmp, rel.toString());
                    if (!out.getCanonicalPath().startsWith(tmp.getCanonicalPath() + File.separator)) {
                        throw new SecurityException("Invalid zip path");
                    }
                    if (!out.exists()) {
                        out.getParentFile().mkdirs();
                        try (OutputStream fos = new FileOutputStream(out)) {
                            int n;
                            while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                        }
                        copied++;
                    }
                } else if (fileName.equalsIgnoreCase("rules.txt") && label.isEmpty()) {
                    File out = new File(tmp, "rules.txt");
                    try (OutputStream fos = new FileOutputStream(out)) {
                        int n;
                        while ((n = zis.read(buf)) > 0) fos.write(buf, 0, n);
                    }
                    label = name(tmp);
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            Backup.deleteTree(tmp);
            return e.getMessage();
        }

        if (copied == 0) {
            Backup.deleteTree(tmp);
            return c.getString(R.string.packs_not_found);
        }

        String zipName = "pack";
        try (Cursor cur = cr.query(zipUri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                zipName = cur.getString(0).replaceFirst("(?i)\\.zip$", "");
            }
        } catch (Exception ignored) {}

        String folder = (label.isEmpty() ? zipName : label).replaceAll("[^A-Za-z0-9 ._()-]", "_").trim();
        if (folder.isEmpty() || folder.startsWith(".")) folder = "pack";
        File dest = new File(root, folder);
        Backup.deleteTree(dest);
        if (!tmp.renameTo(dest)) return "cannot move the pack into place";
        return null;
    }

    private static String treeName(ContentResolver cr, Uri tree) {
        Uri doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        try (Cursor cur = cr.query(doc, new String[] {DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) return cur.getString(0);
        } catch (Exception ignored) {
        }
        return "pack";
    }

    private interface Visitor { void file(List<String> path, Uri uri) throws IOException; }

    private static void walk(ContentResolver cr, Uri tree, String docId, List<String> path, Visitor v) throws IOException {
        if (path.size() > 16) return;
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId);
        try (Cursor cur = cr.query(children, new String[] {DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            while (cur != null && cur.moveToNext()) {
                String id = cur.getString(0), name = cur.getString(1), mime = cur.getString(2);
                List<String> p = new ArrayList<>(path);
                p.add(name);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) walk(cr, tree, id, p, v);
                else v.file(p, DocumentsContract.buildDocumentUriUsingTree(tree, id));
            }
        }
    }

    private static void copy(ContentResolver cr, Uri from, File to) throws IOException {
        try (InputStream in = cr.openInputStream(from); OutputStream out = new FileOutputStream(to)) {
            if (in == null) throw new IOException("cannot read " + from);
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
        }
    }

    static String joinOverlays(List<String> packs, String rest) {
        List<String> all = new ArrayList<>(packs);
        if (rest != null && !rest.isEmpty()) all.addAll(Arrays.asList(rest.split("\n")));
        return String.join("\n", all);
    }
}
