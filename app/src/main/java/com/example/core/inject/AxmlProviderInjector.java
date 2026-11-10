package com.example.core.inject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * AxmlProviderInjector
 *
 * Binary AndroidManifest.xml (AXML) patcher that injects a <provider> element
 * (a DocumentsProvider) into an existing APK manifest.
 *
 * Pure JVM, no Android APIs - so it can be unit-tested on a desktop JVM and
 * reused verbatim inside the Android app.
 *
 * Reference: AOSP ResourceTypes.h / frameworks/base/libs/androidfw/include/androidfw/ResourceTypes.h
 */
public class AxmlProviderInjector {

    // ---- chunk types -------------------------------------------------------
    private static final int RES_XML_TYPE = 0x0003;
    private static final int RES_STRING_POOL_TYPE = 0x0001;
    private static final int RES_XML_RESOURCE_MAP_TYPE = 0x0180;
    private static final int RES_XML_START_ELEMENT_TYPE = 0x0102;
    private static final int RES_XML_END_ELEMENT_TYPE = 0x0103;

    // ---- string pool flags -------------------------------------------------
    private static final int SORTED_FLAG = 1 << 0;
    private static final int UTF8_FLAG = 1 << 8;

    // ---- attribute resource ids (android framework) ------------------------
    private static final int ATTR_NAME = 0x01010003;
    private static final int ATTR_EXPORTED = 0x01010010;
    private static final int ATTR_AUTHORITIES = 0x01010018;
    private static final int ATTR_GRANT_URI_PERMISSIONS = 0x0101001b;

    private static final int TYPE_STRING = 0x03;
    private static final int TYPE_BOOLEAN = 0x12;

    private static final int NO_ENTRY = 0xFFFFFFFF;

    public static class Result {
        public byte[] manifest;
        public int addedStrings;
        public boolean injected;
        public String detail = "";
    }

    /** Attribute list to inject. */
    public static class ProviderSpec {
        public String className;       // e.g. com.example.mtprovider.MtDocumentsProvider
        public String authority;       // e.g. com.example.mtprovider.docs
        public boolean exported = true;
        public boolean grantUriPermissions = true;
    }

    // =======================================================================
    public static Result inject(byte[] manifest, ProviderSpec spec) throws IOException {
        Result res = new Result();
        if (manifest == null || manifest.length < 16) throw new IOException("manifest too short");

        ByteReader in = new ByteReader(manifest);
        int fileType = in.u16();
        int headerSize = in.u16();
        long fileSize = in.u32();
        if (fileType != RES_XML_TYPE) throw new IOException("not an AXML file (type=0x" + Integer.toHexString(fileType) + ")");
        in.skip(4); // fileSize re-read below

        // ---- locate chunks ------------------------------------------------
        int poolStart = -1, poolSize = 0;
        int mapStart = -1, mapSize = 0;
        int nodesStart = -1;

        List<int[]> nodeSlices = new ArrayList<>(); // start,end of each node chunk
        int cursor = headerSize;
        while (cursor + 8 <= manifest.length) {
            int cType = readU16(manifest, cursor);
            int cHeader = readU16(manifest, cursor + 2);
            long cSize = readU32(manifest, cursor + 4);
            if (cSize <= 0 || cursor + cSize > manifest.length) break;

            if (cType == RES_STRING_POOL_TYPE) {
                poolStart = cursor; poolSize = (int) cSize;
            } else if (cType == RES_XML_RESOURCE_MAP_TYPE) {
                mapStart = cursor; mapSize = (int) cSize;
            } else {
                if (nodesStart < 0) nodesStart = cursor;
                nodeSlices.add(new int[]{cursor, cursor + (int) cSize});
            }
            cursor += cSize;
        }
        if (poolStart < 0) throw new IOException("string pool not found");

        // ---- parse string pool --------------------------------------------
        StringPool pool = StringPool.parse(manifest, poolStart, poolSize);

        // ---- parse resource map -------------------------------------------
        int[] resMap = new int[0];
        if (mapStart >= 0) {
            int count = (mapSize - 8) / 4;
            resMap = new int[count];
            for (int i = 0; i < count; i++) resMap[i] = (int) readU32(manifest, mapStart + 8 + i * 4);
        }

        // ---- allocate string indices for what we need ----------------------
        int idxProvider = pool.indexOf("provider");
        if (idxProvider < 0) idxProvider = pool.add("provider");
        int idxClass = pool.indexOf(spec.className);
        if (idxClass < 0) idxClass = pool.add(spec.className);
        int idxAuthority = pool.indexOf(spec.authority);
        if (idxAuthority < 0) idxAuthority = pool.add(spec.authority);
        int idxAttrName = pool.indexOf("name");
        if (idxAttrName < 0) idxAttrName = pool.add("name");
        int idxAttrAuth = pool.indexOf("authorities");
        if (idxAttrAuth < 0) idxAttrAuth = pool.add("authorities");
        int idxAttrExported = pool.indexOf("exported");
        if (idxAttrExported < 0) idxAttrExported = pool.add("exported");
        int idxAttrGrant = pool.indexOf("grantUriPermissions");
        if (idxAttrGrant < 0) idxAttrGrant = pool.add("grantUriPermissions");
        int idxIntentFilter = pool.indexOf("intent-filter");
        if (idxIntentFilter < 0) idxIntentFilter = pool.add("intent-filter");
        int idxAction = pool.indexOf("action");
        if (idxAction < 0) idxAction = pool.add("action");
        int idxActionName = pool.indexOf("name");
        if (idxActionName < 0) idxActionName = pool.add("name");
        int idxDocsAction = pool.indexOf("android.content.action.DOCUMENTS_PROVIDER");
        if (idxDocsAction < 0) idxDocsAction = pool.add("android.content.action.DOCUMENTS_PROVIDER");

        // resource ids: ensure the resource map covers our attribute names
        int newMapCount = pool.count();
        int[] newResMap = new int[newMapCount];
        Arrays.fill(newResMap, 0);
        System.arraycopy(resMap, 0, newResMap, 0, Math.min(resMap.length, newMapCount));
        newResMap[idxAttrName] = ATTR_NAME;
        newResMap[idxAttrAuth] = ATTR_AUTHORITIES;
        newResMap[idxAttrExported] = ATTR_EXPORTED;
        newResMap[idxAttrGrant] = ATTR_GRANT_URI_PERMISSIONS;

        // ---- find android namespace + application end element ---------------
        int androidNs = -1;
        int appEndChunk = -1;
        for (int[] sl : nodeSlices) {
            int type = readU16(manifest, sl[0]);
            if (type == 0x0100) { // START_NAMESPACE
                int prefix = (int) readU32(manifest, sl[0] + 16);
                int uri = (int) readU32(manifest, sl[0] + 20);
                String u = pool.get(uri);
                if (u != null && u.equals("http://schemas.android.com/apk/res/android")) androidNs = prefix;
            } else if (type == RES_XML_END_ELEMENT_TYPE) {
                String n = pool.get((int) readU32(manifest, sl[0] + 20));
                if ("application".equals(n)) appEndChunk = sl[0];
            }
        }
        if (androidNs < 0) throw new IOException("android namespace not found");
        if (appEndChunk < 0) throw new IOException("</application> not found");

        // ---- rebuild string pool ------------------------------------------
        byte[] newPool = pool.build();

        // ---- build provider element ---------------------------------------
        byte[] provider = buildProviderElement(androidNs, idxProvider,
                new int[]{idxAttrName, idxAttrAuth, idxAttrExported, idxAttrGrant},
                new int[]{TYPE_STRING, TYPE_STRING, TYPE_BOOLEAN, TYPE_BOOLEAN},
                new int[]{idxClass, idxAuthority, spec.exported ? 1 : 0, spec.grantUriPermissions ? 1 : 0},
                idxIntentFilter, idxAction, idxActionName, idxDocsAction);

        // ---- assemble ------------------------------------------------------
        ByteArrayOutputStream out = new ByteArrayOutputStream(manifest.length + 4096);
        int nodesEnd = nodesEnd(manifest, nodeSlices);
        // file header
        out.write(0x03); out.write(0x00); // type RES_XML
        out.write(0x08); out.write(0x00); // header size
        long total = 8L + newPool.length + (8L + newResMap.length * 4L) + (nodesEnd - nodesStart) + provider.length;
        writeU32(out, total);

        out.write(newPool);
        // resource map chunk
        writeU16(out, RES_XML_RESOURCE_MAP_TYPE);
        writeU16(out, 8);
        writeU32(out, 8L + newResMap.length * 4L);
        for (int v : newResMap) writeU32(out, v);

        // nodes, with the <provider> element injected right before </application>
        out.write(manifest, nodesStart, appEndChunk - nodesStart);
        out.write(provider);
        out.write(manifest, appEndChunk, nodesEnd - appEndChunk);

        res.manifest = out.toByteArray();
        res.addedStrings = pool.count() - pool.originalCount;
        res.injected = true;
        res.detail = "provider=" + spec.className + " authority=" + spec.authority;
        return res;
    }

    private static int nodesEnd(byte[] m, List<int[]> slices) {
        int end = 0;
        for (int[] s : slices) end = Math.max(end, s[1]);
        return end;
    }

    /**
     * Builds:
     * <provider android:name=.. android:authorities=.. android:exported=.. android:grantUriPermissions=..>
     *   <intent-filter><action android:name="android.content.action.DOCUMENTS_PROVIDER"/></intent-filter>
     * </provider>
     */
    private static byte[] buildProviderElement(int androidNs, int nameIdx,
                                               int[] attrNameIdx, int[] attrTypes, int[] attrData,
                                               int idxIntentFilter, int idxAction, int idxActionName, int idxDocsAction) {
        int attrCount = attrNameIdx.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(512);

        // --- <provider ...>
        int startSize = 36 + attrCount * 20;
        writeU16(o, RES_XML_START_ELEMENT_TYPE);
        writeU16(o, 16);
        writeU32(o, startSize);
        writeU32(o, 0);        // line number
        writeU32(o, NO_ENTRY); // comment
        writeU32(o, NO_ENTRY); // element namespace (no prefix)
        writeU32(o, nameIdx);
        writeU16(o, 20);       // attributeStart
        writeU16(o, 20);       // attributeSize
        writeU16(o, attrCount);
        writeU16(o, 0);        // idIndex
        writeU16(o, 0);        // classIndex
        writeU16(o, 0);        // styleIndex
        for (int i = 0; i < attrCount; i++) {
            writeU32(o, androidNs);          // attribute namespace
            writeU32(o, attrNameIdx[i]);     // attribute name
            writeU32(o, NO_ENTRY);            // raw value
            writeU16(o, 8);                  // typed value size
            o.write(0);                      // res0
            o.write(attrTypes[i]);           // data type
            writeU32(o, attrData[i]);        // data
        }

        // --- <intent-filter>
        writeU16(o, RES_XML_START_ELEMENT_TYPE);
        writeU16(o, 16);
        writeU32(o, 36);
        writeU32(o, 0);
        writeU32(o, NO_ENTRY);
        writeU32(o, NO_ENTRY); // no namespace
        writeU32(o, idxIntentFilter);
        writeU16(o, 20);
        writeU16(o, 20);
        writeU16(o, 0);        // no attributes
        writeU16(o, 0);
        writeU16(o, 0);
        writeU16(o, 0);

        // --- <action android:name="android.content.action.DOCUMENTS_PROVIDER"/>
        writeU16(o, RES_XML_START_ELEMENT_TYPE);
        writeU16(o, 16);
        writeU32(o, 56);
        writeU32(o, 0);
        writeU32(o, NO_ENTRY);
        writeU32(o, NO_ENTRY);
        writeU32(o, idxAction);
        writeU16(o, 20);
        writeU16(o, 20);
        writeU16(o, 1);        // one attribute
        writeU16(o, 0);
        writeU16(o, 0);
        writeU16(o, 0);
        writeU32(o, androidNs);
        writeU32(o, idxActionName);
        writeU32(o, NO_ENTRY);
        writeU16(o, 8);
        o.write(0);
        o.write(TYPE_STRING);
        writeU32(o, idxDocsAction);

        // --- </action>
        writeU16(o, RES_XML_END_ELEMENT_TYPE);
        writeU16(o, 16);
        writeU32(o, 24);
        writeU32(o, 0);
        writeU32(o, NO_ENTRY);
        writeU32(o, NO_ENTRY);
        writeU32(o, idxAction);

        // --- </intent-filter>
        writeU16(o, RES_XML_END_ELEMENT_TYPE);
        writeU16(o, 16);
        writeU32(o, 24);
        writeU32(o, 0);
        writeU32(o, NO_ENTRY);
        writeU32(o, NO_ENTRY);
        writeU32(o, idxIntentFilter);

        // --- </provider>
        writeU16(o, RES_XML_END_ELEMENT_TYPE);
        writeU16(o, 16);
        writeU32(o, 24);
        writeU32(o, 0);
        writeU32(o, NO_ENTRY);
        writeU32(o, NO_ENTRY);
        writeU32(o, nameIdx);

        return o.toByteArray();
    }

    // =======================================================================
    // String pool
    // =======================================================================
    static class StringPool {
        int chunkStart, chunkSize;
        int stringCount, styleCount, flags;
        int stringsStart, stylesStart;
        int[] offsets;
        int[] styleOffsets;
        byte[] rawData;      // string data region (from chunkStart+stringsStart .. chunkStart+chunkSize)
        byte[] styleData;    // may be empty
        int originalCount;

        static StringPool parse(byte[] m, int start, int size) throws IOException {
            StringPool p = new StringPool();
            p.chunkStart = start; p.chunkSize = size;
            p.stringCount = (int) readU32(m, start + 8);
            p.styleCount = (int) readU32(m, start + 12);
            p.flags = (int) readU32(m, start + 16);
            p.stringsStart = (int) readU32(m, start + 20);
            p.stylesStart = (int) readU32(m, start + 24);
            p.offsets = new int[p.stringCount];
            for (int i = 0; i < p.stringCount; i++) p.offsets[i] = (int) readU32(m, start + 28 + i * 4);
            p.styleOffsets = new int[p.styleCount];
            for (int i = 0; i < p.styleCount; i++) p.styleOffsets[i] = (int) readU32(m, start + 28 + p.stringCount * 4 + i * 4);
            int stringsEnd = (p.styleCount > 0 && p.stylesStart > 0) ? p.stylesStart : p.chunkSize;
            p.rawData = Arrays.copyOfRange(m, start + p.stringsStart, start + stringsEnd);
            if (p.styleCount > 0 && p.stylesStart > 0) {
                p.styleData = Arrays.copyOfRange(m, start + p.stylesStart, start + p.chunkSize);
            } else {
                p.styleData = new byte[0];
            }
            p.originalCount = p.stringCount;
            return p;
        }

        boolean isUtf8() { return (flags & UTF8_FLAG) != 0; }

        String get(int idx) {
            if (idx < 0 || idx >= stringCount) return null;
            int off = offsets[idx];
            try {
                if (isUtf8()) {
                    int p = off;
                    int len1 = rawData[p++] & 0xFF;
                    if ((len1 & 0x80) != 0) len1 = ((len1 & 0x7F) << 8) | (rawData[p++] & 0xFF);
                    int len2 = rawData[p++] & 0xFF;
                    if ((len2 & 0x80) != 0) len2 = ((len2 & 0x7F) << 8) | (rawData[p++] & 0xFF);
                    return new String(rawData, p, len2, StandardCharsets.UTF_8);
                } else {
                    // UTF-16: length is a u16 (two words if the high bit is set)
                    int p = off;
                    int len = (rawData[p] & 0xFF) | ((rawData[p + 1] & 0xFF) << 8);
                    p += 2;
                    if ((len & 0x8000) != 0) {
                        len = ((len & 0x7FFF) << 16) | (((rawData[p] & 0xFF) << 8) | (rawData[p + 1] & 0xFF));
                        p += 2;
                    }
                    return new String(rawData, p, len * 2, StandardCharsets.UTF_16LE);
                }
            } catch (Exception e) {
                return null;
            }
        }

        int indexOf(String s) {
            for (int i = 0; i < stringCount; i++) {
                if (s.equals(get(i))) return i;
            }
            return -1;
        }

        int count() { return stringCount; }

        /** Appends a string, returns its index. */
        int add(String s) {
            byte[] enc = encode(s);
            // append to rawData, tracking offsets
            int[] newOffsets = Arrays.copyOf(offsets, stringCount + 1);
            newOffsets[stringCount] = rawData.length;
            offsets = newOffsets;
            rawData = concat(rawData, enc);
            stringCount++;
            return stringCount - 1;
        }

        private byte[] encode(String s) {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (isUtf8()) {
                byte[] b = s.getBytes(StandardCharsets.UTF_8);
                int utf16Len = s.length();
                writeUtf8Len(o, utf16Len);
                writeUtf8Len(o, b.length);
                o.write(b, 0, b.length);
                o.write(0);
            } else {
                int utf16Len = s.length();
                writeUtf16Len(o, utf16Len);
                o.write(s.getBytes(StandardCharsets.UTF_16LE), 0, utf16Len * 2);
                o.write(0); o.write(0);
            }
            return o.toByteArray();
        }

        private static void writeUtf8Len(ByteArrayOutputStream o, int len) {
            if (len > 0x7F) {
                o.write(((len >> 8) & 0x7F) | 0x80);
                o.write(len & 0xFF);
            } else {
                o.write(len);
            }
        }

        private static void writeUtf16Len(ByteArrayOutputStream o, int len) {
            // UTF-16 string lengths are stored little-endian
            if (len > 0x7FFF) {
                o.write(((len >> 16) & 0x7F) | 0x80);
                o.write((len >> 8) & 0xFF);
                o.write(len & 0xFF);
            } else if (len > 0x7FF) {
                o.write(((len >> 8) & 0x7F) | 0x80);
                o.write(len & 0xFF);
            } else {
                o.write(len & 0xFF);
                o.write((len >> 8) & 0xFF);
            }
        }

        byte[] build() {
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            int headerSize = 28;
            int offsetsSize = stringCount * 4 + styleCount * 4;
            int stringsStartNew = headerSize + offsetsSize;
            int stylesStartNew = styleData.length > 0 ? stringsStartNew + rawData.length : 0;
            int chunkSize = stringsStartNew + rawData.length + styleData.length;
            int padding = (4 - (chunkSize % 4)) % 4;   // ResChunk sizes must be 4-byte aligned
            chunkSize += padding;

            writeU16(o, RES_STRING_POOL_TYPE);
            writeU16(o, headerSize);
            writeU32(o, chunkSize);
            writeU32(o, stringCount);
            writeU32(o, styleCount);
            writeU32(o, flags);
            writeU32(o, stringsStartNew);
            writeU32(o, stylesStartNew);
            for (int i = 0; i < stringCount; i++) writeU32(o, offsets[i]);
            for (int i = 0; i < styleCount; i++) writeU32(o, styleOffsets[i]);
            o.write(rawData, 0, rawData.length);
            if (styleData.length > 0) o.write(styleData, 0, styleData.length);
            for (int i = 0; i < padding; i++) o.write(0);
            return o.toByteArray();
        }

        private static byte[] concat(byte[] a, byte[] b) {
            byte[] r = new byte[a.length + b.length];
            System.arraycopy(a, 0, r, 0, a.length);
            System.arraycopy(b, 0, r, a.length, b.length);
            return r;
        }
    }

    // =======================================================================
    /**
     * Reads the "package" attribute of the &lt;manifest&gt; element from a binary
     * AndroidManifest.xml. Used to derive a unique authority for the injected provider.
     */
    public static String readPackageName(byte[] manifest) throws IOException {
        if (manifest == null || manifest.length < 16) return null;
        int headerSize = readU16(manifest, 2);
        int poolStart = -1, poolSize = 0;
        int cursor = headerSize;
        while (cursor + 8 <= manifest.length) {
            int cType = readU16(manifest, cursor);
            long cSize = readU32(manifest, cursor + 4);
            if (cSize <= 0 || cursor + cSize > manifest.length) break;
            if (cType == RES_STRING_POOL_TYPE) {
                poolStart = cursor; poolSize = (int) cSize;
            } else if (cType == RES_XML_START_ELEMENT_TYPE) {
                StringPool pool = StringPool.parse(manifest, poolStart, poolSize);
                String name = pool.get((int) readU32(manifest, cursor + 20));
                if ("manifest".equals(name)) {
                    int attrStart = readU16(manifest, cursor + 16 + 8);
                    int attrSize = readU16(manifest, cursor + 16 + 10);
                    int attrCount = readU16(manifest, cursor + 16 + 12);
                    int attrBase = cursor + 16 + attrStart;
                    for (int i = 0; i < attrCount; i++) {
                        int a = attrBase + i * attrSize;
                        String attrName = pool.get((int) readU32(manifest, a + 4));
                        if ("package".equals(attrName)) {
                            int data = (int) readU32(manifest, a + 16);
                            return pool.get(data);
                        }
                    }
                }
            }
            cursor += cSize;
        }
        return null;
    }

    // =======================================================================
    static int readU16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    static long readU32(byte[] b, int off) {
        return (b[off] & 0xFFL) | ((b[off + 1] & 0xFFL) << 8) | ((b[off + 2] & 0xFFL) << 16) | ((b[off + 3] & 0xFFL) << 24);
    }

    static void writeU16(ByteArrayOutputStream o, int v) { o.write(v & 0xFF); o.write((v >> 8) & 0xFF); }

    static void writeU32(ByteArrayOutputStream o, long v) {
        o.write((int) (v & 0xFF)); o.write((int) ((v >> 8) & 0xFF));
        o.write((int) ((v >> 16) & 0xFF)); o.write((int) ((v >> 24) & 0xFF));
    }

    static class ByteReader {
        final byte[] b; int p;
        ByteReader(byte[] b) { this.b = b; }
        int u16() { int v = readU16(b, p); p += 2; return v; }
        long u32() { long v = readU32(b, p); p += 4; return v; }
        void skip(int n) { p += n; }
    }
}
