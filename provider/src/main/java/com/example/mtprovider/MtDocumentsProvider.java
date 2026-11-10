package com.example.mtprovider;

import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.provider.ProviderInfo;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * A framework-only DocumentsProvider that exposes the plain filesystem.
 *
 * It deliberately uses nothing but the Android framework (no androidx, no resources)
 * so the very same dex can be injected into an arbitrary third-party APK: once the
 * provider lives inside that app's process it can read that app's own private data
 * directory (/data/data/&lt;pkg&gt;) - which no other app can reach - and expose it
 * through SAF so a file manager can browse and edit it.
 *
 * Document ids are absolute filesystem paths.
 */
public class MtDocumentsProvider extends DocumentsProvider {

    public static final String ROOT_ID_ROOT = "root";
    public static final String ROOT_ID_HOME = "home";

    private String authority;

    @Override
    public void attachInfo(Context context, ProviderInfo info) {
        super.attachInfo(context, info);
        if (info != null) {
            authority = info.authority;
        }
    }

    public String getAuthority() {
        return authority;
    }

    // ------------------------------------------------------------------ helpers

    private static File resolve(String documentId) {
        if (documentId == null || documentId.isEmpty()) return null;
        if (!documentId.startsWith("/")) return null;
        return new File(documentId);
    }

    private static String mimeOf(File f) {
        if (f.isDirectory()) return DocumentsContract.Document.MIME_TYPE_DIR;
        String ext = f.getName();
        int dot = ext.lastIndexOf('.');
        String mime = null;
        if (dot >= 0 && dot < ext.length() - 1) {
            mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext.substring(dot + 1).toLowerCase());
        }
        return mime != null ? mime : "application/octet-stream";
    }

    private static int modeToMode(String mode) throws FileNotFoundException {
        if (mode == null || mode.isEmpty() || mode.equals("r")) {
            return ParcelFileDescriptor.MODE_READ_ONLY;
        }
        if (mode.contains("w") || mode.contains("t") || mode.contains("a")) {
            return ParcelFileDescriptor.MODE_READ_WRITE | ParcelFileDescriptor.MODE_CREATE | ParcelFileDescriptor.MODE_TRUNCATE;
        }
        throw new FileNotFoundException("Unsupported mode " + mode);
    }

    private static int flagsOf(File f) {
        int flags = 0;
        if (f.isDirectory()) flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
        if (f.canRead()) flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE;
        return flags;
    }

    private void addDocumentRow(MatrixCursor out, File f) {
        out.addRow(new Object[]{
                f.getAbsolutePath(),
                mimeOf(f),
                f.getName(),
                f.isDirectory() ? null : f.length(),
                f.lastModified(),
                flagsOf(f)
        });
    }

    // ------------------------------------------------------------------ queries

    @Override
    public Cursor queryRoots(String[] projection) {
        MatrixCursor out = new MatrixCursor(projection != null ? projection : new String[]{
                DocumentsContract.Root.COLUMN_ROOT_ID,
                DocumentsContract.Root.COLUMN_TITLE,
                DocumentsContract.Root.COLUMN_ICON,
                DocumentsContract.Root.COLUMN_FLAGS,
                DocumentsContract.Root.COLUMN_MIME_TYPE,
                DocumentsContract.Root.COLUMN_SUMMARY,
                DocumentsContract.Root.COLUMN_DOCUMENT_ID
        });
        try {
            Context ctx = getContext();
            File home = ctx != null ? ctx.getFilesDir() : null;
            out.addRow(new Object[]{
                    ROOT_ID_ROOT,
                    "Filesystem",
                    null,
                    DocumentsContract.Root.FLAG_SUPPORTS_CREATE,
                    DocumentsContract.Document.MIME_TYPE_DIR,
                    "Whole filesystem visible to this app",
                    "/"
            });
            if (home != null) {
                out.addRow(new Object[]{
                        ROOT_ID_HOME,
                        "App data",
                        null,
                        DocumentsContract.Root.FLAG_SUPPORTS_CREATE,
                        DocumentsContract.Document.MIME_TYPE_DIR,
                        home.getAbsolutePath(),
                        home.getAbsolutePath()
                });
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) {
        MatrixCursor out = new MatrixCursor(projection != null ? projection : new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS
        });
        File f = resolve(documentId);
        if (f != null && f.exists()) {
            addDocumentRow(out, f);
        }
        return out;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) {
        MatrixCursor out = new MatrixCursor(projection != null ? projection : new String[]{
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                DocumentsContract.Document.COLUMN_FLAGS
        });
        File dir = resolve(parentDocumentId);
        if (dir == null || !dir.isDirectory()) return out;
        File[] children = dir.listFiles();
        if (children == null) return out;
        List<File> list = new ArrayList<>();
        for (File c : children) list.add(c);
        list.sort((a, b) -> {
            if (a.isDirectory() != b.isDirectory()) return a.isDirectory() ? -1 : 1;
            return a.getName().compareToIgnoreCase(b.getName());
        });
        for (File c : list) {
            addDocumentRow(out, c);
        }
        return out;
    }

    @Override
    public String getDocumentType(String documentId) {
        File f = resolve(documentId);
        if (f == null) return DocumentsContract.Document.MIME_TYPE_DIR;
        return mimeOf(f);
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        File parent = resolve(parentDocumentId);
        File child = resolve(documentId);
        if (parent == null || child == null) return false;
        try {
            return child.getCanonicalPath().startsWith(parent.getCanonicalPath() + File.separator);
        } catch (IOException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ io

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        File f = resolve(documentId);
        if (f == null || !f.exists()) throw new FileNotFoundException("No such document: " + documentId);
        return ParcelFileDescriptor.open(f, modeToMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = resolve(parentDocumentId);
        if (parent == null || !parent.isDirectory()) throw new FileNotFoundException("No such directory: " + parentDocumentId);
        File target = new File(parent, displayName);
        try {
            if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType)) {
                if (!target.mkdirs()) throw new FileNotFoundException("Cannot create directory " + target);
            } else {
                if (!target.createNewFile()) throw new FileNotFoundException("Cannot create file " + target);
            }
        } catch (IOException e) {
            throw new FileNotFoundException(e.getMessage());
        }
        return target.getAbsolutePath();
    }

    @Override
    public boolean deleteDocument(String documentId) {
        File f = resolve(documentId);
        if (f == null || !f.exists()) return false;
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) {
                for (File k : kids) deleteDocument(k.getAbsolutePath());
            }
        }
        return f.delete();
    }

    @Override
    public boolean removeDocument(String documentId, String parentDocumentId) {
        return deleteDocument(documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        File f = resolve(documentId);
        if (f == null || !f.exists()) throw new FileNotFoundException("No such document: " + documentId);
        File target = new File(f.getParentFile(), displayName);
        if (!f.renameTo(target)) throw new FileNotFoundException("Cannot rename " + documentId);
        return target.getAbsolutePath();
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        File src = resolve(sourceDocumentId);
        File dstDir = resolve(targetParentDocumentId);
        if (src == null || dstDir == null || !src.exists() || !dstDir.isDirectory()) {
            throw new FileNotFoundException("Cannot move " + sourceDocumentId);
        }
        File dst = new File(dstDir, src.getName());
        if (!src.renameTo(dst)) throw new FileNotFoundException("Cannot move to " + targetParentDocumentId);
        return dst.getAbsolutePath();
    }
}
